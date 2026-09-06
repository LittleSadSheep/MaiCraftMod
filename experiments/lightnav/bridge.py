# SPDX-License-Identifier: GPL-3.0-only
"""Minecraft /predict -> local LM Studio -> verified RVQ decoder and live report."""

import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import time

from lmstudio_client import LMStudioClient
from prepare_assets import FILES
from report import write_report


class Bridge:
    def __init__(self, client, output: Path):
        self.client, self.output = client, output
        self.lock = threading.Lock()
        self.records = []
        self.count = 0
        self.started = time.time()
        output.mkdir(parents=True, exist_ok=False)

    def predict(self, request):
        if not self.lock.acquire(blocking=False):
            raise BlockingIOError("An inference request is already running")
        try:
            received = time.time()
            reply, raw = self.client.predict(request)
            record = {"seq": request["seq"], "session": request.get("session"),
                      "instruction": request["instruction"], "received_at": received,
                      "completed_at": time.time(), "frame": f"frame-{self.count:06d}.png",
                      "roundtrip_ms": reply["data"]["latency_ms"], "response": reply,
                      "model_response": raw}
            # The preview is bounded to the latest 30 frames; the JSONL retains all predictions.
            with (self.output / "predictions.jsonl").open("a", encoding="utf-8") as log:
                log.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
            image = base64.b64decode(request["image"], validate=True)
            mime = "image/png" if image.startswith(b"\x89PNG") else "image/jpeg"
            self.records.append({**record, "image": f"data:{mime};base64,{request['image']}"})
            self.count += 1
            self.records = self.records[-30:]
            temporary = self.output / "report.tmp.html"
            write_report(self.records, temporary, request["instruction"])
            temporary.replace(self.output / "report.html")
            print(f"seq={request['seq']} {reply['data']['latency_ms']:.0f}ms "
                  f"stop={reply['data']['stop']} tokens={reply['data']['raw_text']}", flush=True)
            return reply
        finally:
            self.lock.release()


def handler_for(bridge):
    class Handler(BaseHTTPRequestHandler):
        def respond(self, status, body):
            data = json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass  # Client stop cancels the request; this never triggers game actions.

        def do_GET(self):
            if self.path != "/health":
                return self.respond(404, {"error": "Not found"})
            self.respond(200, {"mode": "observation_only", "model": bridge.client.model,
                               "predictions": bridge.count, "started": bridge.started})

        def do_POST(self):
            request = {}
            try:
                if self.path != "/predict":
                    return self.respond(404, {"error": "Not found"})
                if self.headers.get("Origin") or self.headers.get_content_type() != "application/json":
                    return self.respond(415, {"error": "Expected native JSON client"})
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 4 * 1024 * 1024:
                    return self.respond(413, {"error": "Request must fit within 4 MiB"})
                self.connection.settimeout(10)
                request = json.loads(self.rfile.read(size))
                if not isinstance(request, dict):
                    raise ValueError("Expected request object")
                self.respond(200, bridge.predict(request))
            except BlockingIOError as error:
                self.respond(429, {"error": str(error)})
            except Exception as error:
                print(f"Prediction failed: {error}", flush=True)
                with (bridge.output / "errors.jsonl").open("a", encoding="utf-8") as log:
                    log.write(json.dumps({"at": time.time(), "error": str(error)}, ensure_ascii=False) + "\n")
                seq = request.get("seq") if isinstance(request, dict) else None
                self.respond(200, {"action": "next", "data": {"rc": 500, "seq": seq, "msg": str(error)}})

        def log_message(self, *_):
            pass
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default="lightnav-0")
    parser.add_argument("--endpoint", default="http://127.0.0.1:1234/v1/chat/completions")
    parser.add_argument("--port", type=int, default=8050)
    parser.add_argument("--assets", type=Path, default=Path(__file__).parent / "checkpoints/LightNav-0")
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "output" / time.strftime("live-%Y%m%d-%H%M%S"))
    args = parser.parse_args()
    from prepare_assets import prepare
    # Existing assets are verified before serving; fetch missing assets explicitly with prepare_assets.py.
    if not all((args.assets / "action_tokenizer" / name).is_file() for name in FILES):
        parser.error("Run python experiments/lightnav/prepare_assets.py first")
    prepare(args.assets / "action_tokenizer")
    bridge = Bridge(LMStudioClient(args.endpoint, args.model, args.assets), args.output)
    with ThreadingHTTPServer(("127.0.0.1", args.port), handler_for(bridge)) as server:
        print(f"LightNav bridge: http://127.0.0.1:{args.port}; model={args.model}; "
              f"report={args.output.resolve() / 'report.html'}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
