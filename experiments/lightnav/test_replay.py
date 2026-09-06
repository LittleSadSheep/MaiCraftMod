# SPDX-License-Identifier: GPL-3.0-only
"""Offline replay regressions, including a real local WebSocket round trip."""

import base64
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from protocol import ProtocolError
from replay import frame_paths, image_payload, main, replay
from test_protocol import FakeSocket

PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")


class ReplayTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.frames = self.root / "frames"
        self.frames.mkdir()
        for name in ("frame10.png", "frame2.png"):
            (self.frames / name).write_bytes(PNG)
        self.output = self.root / "result"

    def test_numeric_frame_order_and_native_bytes(self):
        paths = frame_paths(self.frames)
        self.assertEqual([p.name for p in paths], ["frame2.png", "frame10.png"])
        mime, content = image_payload(paths[0])
        self.assertEqual(mime, "image/png")
        self.assertEqual(base64.b64decode(content), PNG)

    def test_invalid_frames_are_rejected(self):
        for path in self.frames.iterdir():
            path.unlink()
        with self.assertRaises(ValueError):
            frame_paths(self.frames)
        path = self.frames / "invalid.jpg"
        path.write_text("not an image", encoding="utf-8")
        with self.assertRaises(ValueError):
            image_payload(path)

    def test_failed_frame_preserves_completed_responses_and_report(self):
        self.output.mkdir()
        responses = [
            {"action": name, "data": {"rc": 0}} for name in ("login", "reset")]
        responses += [
            {"action": "next", "data": {"rc": 0, "seq": 0, "msg": "image received"}},
            {"action": "next", "data": {"rc": 500, "seq": 1, "msg": "model failed"}}]
        ws = FakeSocket(*(json.dumps(reply) for reply in responses))
        with redirect_stdout(io.StringIO()), self.assertRaises(ProtocolError):
            replay(ws, frame_paths(self.frames), "test fixture", self.output, warmup_frames=1)
        lines = (self.output / "predictions.jsonl").read_text().splitlines()
        self.assertEqual(len(lines), 1)
        self.assertEqual(json.loads(lines[0])["response"], responses[2])
        self.assertTrue((self.output / "report.html").is_file())

    def test_cli_replays_one_session_over_websocket(self):
        from websockets.sync.server import serve

        received = []

        def handler(ws):
            for raw in ws:
                request = json.loads(raw)
                received.append(request)
                action, data = request["action"], request["data"]
                result = {"rc": 0}
                if action == "next":
                    result["seq"] = data["seq"]
                    if data["instruction"]:
                        result.update(actions={"actions": [[0, 0, 0]]}, stop=True,
                                      visible=None, latency_ms=3.0)
                ws.send(json.dumps({"action": action, "data": result}))

        with serve(handler, "127.0.0.1", 0) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            address = f"ws://127.0.0.1:{server.socket.getsockname()[1]}"
            arguments = ["replay.py", "--frames", str(self.frames), "--output", str(self.output),
                         "--instruction", "TEST FIXTURE </script>", "--server", address,
                         "--warmup-frames", "1", "--timeout", "2"]
            try:
                with patch("sys.argv", arguments), redirect_stdout(io.StringIO()):
                    main()
            finally:
                server.shutdown()
                worker.join(timeout=5)
        self.assertEqual([r["action"] for r in received], ["login", "reset", "next", "next"])
        self.assertEqual(received[2]["data"]["instruction"], "")
        self.assertEqual(received[3]["data"]["instruction"], "TEST FIXTURE </script>")
        self.assertEqual(base64.b64decode(received[3]["data"]["image"]), PNG)
        rows = [json.loads(line) for line in
                (self.output / "predictions.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual([r["frame"] for r in rows], ["frame2.png", "frame10.png"])
        self.assertTrue(rows[1]["response"]["data"]["stop"])
        report = (self.output / "report.html").read_text(encoding="utf-8")
        self.assertIn("data:image/png;base64,", report)
        self.assertNotIn("TEST FIXTURE </script>", report)

    def test_existing_output_is_never_overwritten(self):
        self.output.mkdir()
        sentinel = self.output / "session.json"
        sentinel.write_text("keep", encoding="utf-8")
        arguments = ["replay.py", "--frames", str(self.frames), "--output", str(self.output),
                     "--instruction", "test"]
        with patch("sys.argv", arguments), redirect_stdout(io.StringIO()), \
                patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit) as exc:
            main()
        self.assertEqual(exc.exception.code, 1)
        self.assertEqual(sentinel.read_text(), "keep")


if __name__ == "__main__":
    unittest.main()
