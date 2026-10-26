"""Fake streaming STT server for the Daily Whisper WebSocket harness (box only, not part of the app).

Speaks the generic protocol the app's Live dictation settings describe:
  client -> optional JSON start message, binary PCM16 audio frames, optional JSON end message
  server -> JSON transcripts {"text": ..., "is_final": bool}; closes 1000 after the end message.
Also exercises: auth header check (401), ping, fragmented text, a >64 KiB message (64-bit length),
a 126..65535 message (16-bit length), a nested Deepgram-shaped message and non-transcript messages.
"""
import asyncio, json, sys
from websockets.asyncio.server import serve
from websockets.http11 import Response
from websockets.datastructures import Headers

EXPECTED = "Bearer harness-test-key"  # dummy test value, not a real key


DG_QUERY = ("model=nova-3", "language=en", "smart_format=true", "encoding=linear16", "sample_rate=16000", "channels=1", "interim_results=true")


def check_auth(conn, request):
    path = request.path
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


async def handler(ws):
    if ws.request.path.startswith("/v1/listen"):
        return await deepgram(ws)
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
