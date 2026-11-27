"""Expose only the synthetic restored provider on runner loopback to an adb-reversed QA emulator."""
import json
import os
import socketserver
import subprocess
import sys
from threading import Lock

worker = subprocess.Popen(
    ["node", "scripts/verify-ascension-forms-recovery.cjs", os.environ["ASTRAVAR_MINIFLARE_PATH"], "--serve", "--installed-preservation"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
pairing = json.loads(worker.stdout.readline())
assert pairing["origin"] == "https://form-recovery.example.test" and pairing["continuity"] == "qa-whole-form-recovery"
guard = Lock()

class ReadOnlyRestoredProvider(socketserver.StreamRequestHandler):
    def handle(self):
        raw = self.rfile.readline(500_000)
        request = json.loads(raw)
        if request["call"] == "pair":
            result = pairing
        else:
            assert request["call"] in ("request", "dm", "media") and "body" not in request
            if request["call"] == "dm":
                assert request["name"].startswith("get_cloud_")
            with guard:
                worker.stdin.write(json.dumps(request) + "\n")
                worker.stdin.flush()
                result = json.loads(worker.stdout.readline())
        self.wfile.write(json.dumps(result).encode("utf-8") + b"\n")

with socketserver.ThreadingTCPServer(("127.0.0.1",38271), ReadOnlyRestoredProvider) as service:
    print("RESTORED_PROVIDER_READY", flush=True)
    try:
        service.serve_forever()
    finally:
        worker.stdin.close()
        worker.wait(timeout=30)
