"""Fake streaming STT server for the Daily Whisper WebSocket harness (box only, not part of the app).

Speaks the generic protocol the app's Live dictation settings describe:
  client -> optional JSON start message, binary PCM16 audio frames, optional JSON end message
  server -> JSON transcripts {"text": ..., "is_final": bool}; closes 1000 after the end message.
Also exercises: auth header check (401), ping, fragmented text, a >64 KiB message (64-bit length),
a 126..65535 message (16-bit length), a nested Deepgram-shaped message and non-transcript messages.
"""
import asyncio, base64, json, sys
from websockets.asyncio.server import serve
from websockets.http11 import Response
from websockets.datastructures import Headers

EXPECTED = "Bearer harness-test-key"  # dummy test value, not a real key


DG_QUERY = ("model=nova-3", "language=en", "smart_format=true", "encoding=linear16", "sample_rate=16000", "channels=1", "interim_results=true")


SARVAM_QUERY = ("language_code=", "model=saaras", "endpointing=vad", "silence_duration_ms=", "encoding=linear16", "sample_rate=16000")


def check_auth(conn, request):
    path = request.path
    if path.startswith("/speech-to-text-realtime/ws"):
        # Sarvam-like: api-subscription-key header. A missing key is refused at HTTP level (403); a wrong key is
        # accepted, then gets a fatal error event + close 1003 (what Sarvam documents for invalid keys / quota).
        key = request.headers.get("api-subscription-key")
        if key is None:
            return Response(403, "Forbidden", Headers(), b'{"error":"missing key"}')
        if not all(q in path for q in SARVAM_QUERY):
            return Response(400, "Bad Request", Headers(), b"missing params")
        return None
    if path.startswith("/v1/listen"):
        # Deepgram-like: Authorization: Token <key>, and the raw-audio query params must be present.
        if request.headers.get("Authorization") != "Token harness-test-key":
            return Response(401, "Unauthorized", Headers(), b'{"err_code":"INVALID_AUTH"}')
        if not all(q in path for q in DG_QUERY):
            return Response(400, "Bad Request", Headers(), b"missing raw audio params")
        return None
    if path.startswith("/query"):
        ok = "api_key=harness-test-key" in path
    else:
        ok = request.headers.get("Authorization") == EXPECTED
    if not ok:
        return Response(401, "Unauthorized", Headers(), b"no")
    return None


def dg_results(text, is_final, start, dur):
    return json.dumps({"type": "Results", "channel_index": [0, 1], "duration": dur, "start": start,
                       "is_final": is_final, "speech_final": is_final,
                       "channel": {"alternatives": [{"transcript": text, "confidence": 0.99, "words": []}]},
                       "metadata": {"request_id": "harness"}, "from_finalize": False})


async def deepgram(ws):
    """Behaves like Deepgram /v1/listen: Metadata-free Results with interim + final, SpeechStarted,
    UtteranceEnd, closes 1000 after CloseStream (sending the last final + a Metadata summary first),
    and closes 1011-ish (NET-0001 style) if no audio/KeepAlive arrives for 2 s (Deepgram: 10 s)."""
    audio = 0
    frames = 0
    while True:
        try:
            msg = await asyncio.wait_for(ws.recv(), 2.0)
        except asyncio.TimeoutError:
            await ws.close(1011, "NET-0001 no audio received")
            return
        if isinstance(msg, bytes):
            audio += len(msg); frames += 1
            if frames == 2:
                await ws.send(json.dumps({"type": "SpeechStarted", "channel": [0], "timestamp": 0.1}))
                await ws.send(dg_results("note to", False, 0, 0.5))
            if frames == 4:
                await ws.send(dg_results("note to self", False, 0, 0.9))
            if frames == 6:
                await ws.send(dg_results("Note to self, buy milk.", True, 0, 1.2))
                await ws.send(json.dumps({"type": "UtteranceEnd", "channel": [0, 1], "last_word_end": 1.1}))
            if frames == 8:
                await ws.send(dg_results("", False, 1.2, 0.2))          # silence interim (empty)
                await ws.send(dg_results("and eggs", False, 1.2, 0.6))
        else:
            data = json.loads(msg)
            if data.get("type") == "KeepAlive":
                continue
            if data.get("type") == "CloseStream":
                await ws.send(dg_results(f"And eggs. [audio={audio} frames={frames}]", True, 1.2, 0.8))
                await ws.send(json.dumps({"type": "Metadata", "transaction_key": "deprecated", "request_id": "harness",
                                          "duration": audio / 32000, "channels": 1}))
                await ws.close(1000, "")
                return


async def sarvam(ws):
    """Behaves like Sarvam /speech-to-text-realtime/ws (docs.sarvam.ai, Oct 2026): session.begin first, JSON
    audio_input frames (base64 PCM), transcript.partial / transcript.final events, VAD events, and on {"event":"end"}
    the last final + session.end, then close 1000. Binary frames get a non-fatal error (Sarvam wants JSON)."""
    if ws.request.headers.get("api-subscription-key") != "harness-test-key":
        await ws.send(json.dumps({"event": "error", "code": "unauthorized", "is_fatal": True,
                                  "message": "Invalid API subscription key", "status_code": 401}))
        await ws.close(1003, "invalid subscription key")
        return
    q = ws.request.path.split("?", 1)[1]
    params = dict(kv.split("=", 1) for kv in q.split("&"))
    mode = params.get("mode", "transcribe")
    if mode not in ("transcribe", "translate", "verbatim", "translit", "codemix"):
        await ws.send(json.dumps({"event": "error", "code": "invalid_config", "is_fatal": True,
                                  "message": f"invalid mode {mode}", "status_code": 4000}))
        await ws.close(4000, "invalid mode")
        return
    await ws.send(json.dumps({"event": "session.begin", "request_id": "harness",
                              "config": {"model": params["model"].replace("%3A", ":"), "language_code": params["language_code"],
                                         "encoding": "linear16", "sample_rate": 16000, "turn_detection": "vad",
                                         "silence_duration_ms": int(params["silence_duration_ms"]), "mode": mode}}))
    audio = 0
    chunks = 0
    # language_code=auto: partials and finals carry the detected "language"; finals add language_confidence.
    auto = params["language_code"] == "auto"
    def lang(final, code="ml-IN"):
        return ({"language": code, **({"language_confidence": 0.93} if final else {})}) if auto else {}
    async for msg in ws:
        if isinstance(msg, bytes):
            await ws.send(json.dumps({"event": "error", "code": "invalid_message", "is_fatal": False, "message": "send JSON"}))
            continue
        data = json.loads(msg)
        if data.get("event") == "audio_input":
            audio += len(base64.b64decode(data["audio"])); chunks += 1
            if chunks == 1:
                await ws.send(json.dumps({"event": "vad.speech_start", "utterance_idx": 0, "confidence": 0.9}))
            if chunks == 2:
                await ws.send(json.dumps({"event": "transcript.partial", "utterance_idx": 0, "text": "\u0d28\u0d2e\u0d38\u0d4d\u0d15\u0d3e\u0d30\u0d02", **lang(False)}))
            if chunks == 4:
                await ws.send(json.dumps({"event": "vad.speech_end", "utterance_idx": 0, "confidence": 0.8}))
                await ws.send(json.dumps({"event": "transcript.final", "utterance_idx": 0, "text": "\u0d28\u0d2e\u0d38\u0d4d\u0d15\u0d3e\u0d30\u0d02.", **lang(True)}))
        elif data.get("event") == "end":
            await ws.send(json.dumps({"event": "transcript.final", "utterance_idx": 1,
                                      "text": f"lang={params['language_code']} silence={params['silence_duration_ms']} audio={audio} chunks={chunks}" + (f" mode={mode}" if mode != "transcribe" else ""),
                                      **lang(True, "en-IN")}))
            await ws.send(json.dumps({"event": "session.end", "request_id": "harness", "total_utterances": 2,
                                      "audio_duration_s": audio / 32000}))
            await ws.close(1000, "")
            return


async def handler(ws):
    if ws.request.path.startswith("/v1/listen"):
        return await deepgram(ws)
    if ws.request.path.startswith("/speech-to-text-realtime/ws"):
        return await sarvam(ws)
    audio_bytes = 0
    frames = 0
    start = None
    pong_ok = False
    async for msg in ws:
        if isinstance(msg, bytes):
            audio_bytes += len(msg)
            frames += 1
            if frames == 1:
                await ws.send(json.dumps({"type": "ack"}))                          # no text -> ignored
                waiter = await ws.ping(b"hi")                                     # client must pong
                try:
                    await asyncio.wait_for(waiter, 2); pong_ok = True
                except asyncio.TimeoutError:
                    pong_ok = False
                await ws.send(json.dumps({"text": "hello", "is_final": False}))   # partial
                await ws.send(["{\"text\": \"hello the", "re\", \"is_final\": false}"])  # fragmented partial
            if frames == 2:
                await ws.send(json.dumps({"text": "hello there", "is_final": True}))
                await ws.send(json.dumps({"text": "big " + "x" * 300, "is_final": False}))      # 16-bit length
                await ws.send(json.dumps({"text": "huge " + "y" * 70000, "is_final": False}))  # 64-bit length
                await ws.send(json.dumps({"type": "Results", "is_final": True,
                    "channel": {"alternatives": [{"transcript": "nested ok"}]}}))
                await ws.send(b"\x00\x01binary")
        else:
            data = json.loads(msg)
            if data.get("type") == "start":
                start = data
            elif data.get("type") == "end":
                await ws.send(json.dumps({"text": f"final after end; audio={audio_bytes} frames={frames} start_rate={start and start.get('rate')} pong={pong_ok}",
                                          "is_final": True}))
                await ws.close(1000, "bye")
                return


async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    async with serve(handler, "127.0.0.1", port, process_request=check_auth, ping_interval=None, max_size=None):
        print("ready", flush=True)
        await asyncio.Future()

asyncio.run(main())
