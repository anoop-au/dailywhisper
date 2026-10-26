#!/usr/bin/env python3
"""Tiny HTTP file server for the download harness. Modes (path prefix):
  /range/<f>      honours Range (206 + Content-Range)
  /norange/<f>    ignores Range (always 200, whole file)
  /redirect/<f>   302 to /range/<f> (like Hugging Face -> CDN)
  /drop/<f>       honours Range but cuts the connection after ~1 MB (first request only per start offset 0)
  /badstart/<f>   206 with a Content-Range starting at 0 whatever was asked
Logs each request's Range header to stdout."""
import os, sys, http.server, socketserver
ROOT = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        mode, _, name = self.path.lstrip('/').partition('/')
        rng = self.headers.get('Range')
        print(f"GET {mode} range={rng}", flush=True)
        if mode == 'redirect':
            self.send_response(302); self.send_header('Location', f'/range/{name}'); self.send_header('Content-Length', '0'); self.end_headers(); return
        p = os.path.join(ROOT, name)
        data = open(p, 'rb').read()
        start = 0
        if rng and mode in ('range', 'drop', 'badstart'):
            start = int(rng.split('=')[1].split('-')[0])
        if start >= len(data) and rng and mode == 'range':
            self.send_response(416); self.end_headers(); return
        if rng and mode in ('range', 'drop', 'badstart'):
            cr = 0 if mode == 'badstart' else start
            body = data[cr:]
            self.send_response(206); self.send_header('Content-Range', f'bytes {cr}-{len(data)-1}/{len(data)}')
        else:
            body = data; self.send_response(200)
        self.send_header('Content-Length', str(len(body))); self.end_headers()
        if mode == 'drop' and start == 0:
            self.wfile.write(body[:1_000_000]); self.wfile.flush()
            self.connection.shutdown(2); return
        self.wfile.write(body)
class S(socketserver.ThreadingMixIn, http.server.HTTPServer): daemon_threads = True
S(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
