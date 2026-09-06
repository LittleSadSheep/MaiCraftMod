# SPDX-License-Identifier: GPL-3.0-only
import base64
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock

from PIL import Image

from bridge import Bridge, handler_for


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / "run"
        self.client = Mock(model="nav")
        self.client.predict.side_effect = lambda req: ({"action": "next", "data": {
            "rc": 0, "seq": req["seq"], "session": req["session"], "latency_ms": 4,
            "stop": False, "raw_text": "<act_l0_17><act_l1_34><act_l2_88>"}}, {"actual_model": "raw"})
        self.bridge = Bridge(self.client, self.output)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler_for(self.bridge))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        image = io.BytesIO()
        Image.new("RGB", (32, 18)).save(image, "PNG")
        self.request = {"seq": 3, "session": "run-a", "instruction": "Go to bell",
                        "image": base64.b64encode(image.getvalue()).decode()}

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def send(self, method, path, body=None):
        connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            connection.request(method, path, None if body is None else json.dumps(body), {"Content-Type": "application/json"})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_health_prediction_and_persisted_raw_report(self):
        status, health = self.send("GET", "/health")
        self.assertEqual((status, health["mode"], health["model"]), (200, "observation_only", "nav"))
        status, reply = self.send("POST", "/predict", self.request)
        self.assertEqual((status, reply["data"]["seq"], reply["data"]["session"]), (200, 3, "run-a"))
        record = json.loads((self.output / "predictions.jsonl").read_text())
        self.assertEqual(record["model_response"], {"actual_model": "raw"})
        self.assertEqual(record["session"], "run-a")
        self.assertIn("data:image/png;base64,", (self.output / "report.html").read_text(encoding="utf-8"))

    def test_busy_returns_429_and_failure_releases_lock(self):
        with self.bridge.lock:
            self.assertEqual(self.send("POST", "/predict", self.request)[0], 429)
        self.client.predict.side_effect = ValueError("empty model output")
        status, reply = self.send("POST", "/predict", self.request)
        self.assertEqual((status, reply["data"]["rc"], reply["data"]["seq"]), (200, 500, 3))
        self.assertFalse(self.bridge.lock.locked())
        self.assertIn("empty model output", (self.output / "errors.jsonl").read_text())


if __name__ == "__main__":
    unittest.main()
