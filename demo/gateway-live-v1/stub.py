#!/usr/bin/env python3
"""Two local HTTP backends to prove actual LivingDocs Gateway routing.

S01 :18082 handles GET /api/v1/users/me
S04 :18084 handles GET /api/v1/review-queue
Only for localhost integration demo; it DOES NOT implement domain authorization.
"""
import json
import signal
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
LOG = ROOT / ".local" / "backend_requests.jsonl"
LOCK = threading.Lock()
SPECS = [
    ("S01-DEMO", 18082, "/api/v1/users/me"),
    ("S04-DEMO", 18084, "/api/v1/review-queue"),
]


def handler_for(service, allowed_path):
    class Handler(BaseHTTPRequestHandler):
        def process(self):
            path = urlsplit(self.path).path
            cid = self.headers.get("X-Correlation-ID", "")
            record = {
                "utc": datetime.now(timezone.utc).isoformat(),
                "service": service,
                "method": self.command,
                "path": path,
                "correlationId": cid,
            }
            with LOCK:
                with LOG.open("a", encoding="utf-8") as logfile:
                    logfile.write(json.dumps(record, ensure_ascii=False) + "\n")
                print(f"[{service}] RECEIVED {self.command} {path} correlationId={cid}", flush=True)

            if self.command == "GET" and path == allowed_path:
                status = 200
                body = {"service": service, "demo": True, "message": "Gateway forwarded to correct backend", "path": path, "correlationId": cid}
            else:
                status = 404
                body = {"service": service, "demo": True, "error": "Not implemented by demo stub", "path": path}
            raw = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("X-Backend-Service", service)
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            self.process()

        def do_POST(self):
            self.process()

        def log_message(self, fmt, *args):
            pass

    return Handler


def main():
    LOG.parent.mkdir(parents=True, exist_ok=True)
    LOG.touch(exist_ok=True)
    servers = []
    threads = []
    for service, port, path in SPECS:
        server = ThreadingHTTPServer(("127.0.0.1", port), handler_for(service, path))
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        servers.append(server)
        threads.append(thread)
        print(f"READY {service} http://127.0.0.1:{port}{path}", flush=True)
    print("Request log: demo/gateway-live-v1/.local/backend_requests.jsonl", flush=True)
    print("Leave this terminal open while running Gateway tests. Press Ctrl+C to stop.", flush=True)
    try:
        signal.pause()
    except KeyboardInterrupt:
        pass
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
