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


def check_auth(conn, request):
    path = request.path
    if path.startswith("/query"):
        ok = "api_key=harness-test-key" in path
    else:
        ok = request.headers.get("Authorization") == EXPECTED
    if not ok:
        return Response(401, "Unauthorized", Headers(), b"no")
    return None


async def handler(ws):
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
