# SPDX-License-Identifier: GPL-3.0-only
import base64
import io
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError

from PIL import Image

from lmstudio_client import LMStudioClient


class LMStudioTests(unittest.TestCase):
    def setUp(self):
        image = io.BytesIO()
        Image.new("RGB", (640, 360), "red").save(image, "PNG")
        self.request = {"seq": 9, "session": "episode-a", "instruction": "Go to the bell",
                        "image": base64.b64encode(image.getvalue()).decode(), "frame_size": [640, 360]}
        self.client = LMStudioClient("http://127.0.0.1:1234/v1/chat/completions", "nav", Path("assets"))
        self.client.opener = Mock()
        self.text = "<apos_50><opos_700><act_l0_17><act_l1_34><act_l2_88>"

    def reply(self, text=None, finish="stop"):
        data = {"choices": [{"message": {"content": self.text if text is None else text},
                             "finish_reason": finish}], "usage": {"completion_tokens": 6}}
        self.client.opener.open.return_value = io.BytesIO(json.dumps(data).encode())
        return data

    @patch("lmstudio_client.decode_text", return_value={"rc": 0, "stop": False})
    def test_preserves_model_tokens_and_preprocesses_actual_image(self, decoder):
        expected = self.reply()
        reply, raw = self.client.predict(self.request)
        self.assertEqual(raw, expected)
        decoder.assert_called_once_with(self.text, Path("assets"), (640, 360))
        self.assertEqual((reply["data"]["seq"], reply["data"]["session"]), (9, "episode-a"))
        payload = json.loads(self.client.opener.open.call_args.args[0].data)
        parts = payload["messages"][0]["content"]
        self.assertIn("<navigation_task>Go to the bell</navigation_task>", parts[2]["text"])
        uri = parts[1]["image_url"]["url"]
        with Image.open(io.BytesIO(base64.b64decode(uri.split(",", 1)[1]))) as image:
            self.assertEqual((image.size, image.format), ((448, 256), "PNG"))

    def test_empty_truncated_and_server_error_are_rejected(self):
        for text, finish in (("", "stop"), (self.text, "length")):
            self.reply(text, finish)
            with self.assertRaises(ValueError):
                self.client.predict(self.request)
        self.client.opener.open.side_effect = HTTPError("local", 503, "unavailable", {}, io.BytesIO(b"model failed"))
        with self.assertRaisesRegex(RuntimeError, "503.*model failed"):
            self.client.predict(self.request)

    def test_invalid_frames_never_reach_model(self):
        for change in ({"image": "%%%"}, {"image": "aGVsbG8="}, {"frame_size": [1, 1]}, {"seq": True}):
            with self.subTest(change=change), self.assertRaises((ValueError, OSError)):
                self.client.predict({**self.request, **change})
        self.client.opener.open.assert_not_called()

    def test_rejects_nonlocal_endpoint(self):
        for endpoint in ("https://example.com", "http://192.168.1.5:1234", "file:///tmp/model"):
            with self.assertRaises(ValueError):
                LMStudioClient(endpoint, "nav", Path("assets"))


if __name__ == "__main__":
    unittest.main()
