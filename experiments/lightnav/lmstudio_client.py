# SPDX-License-Identifier: GPL-3.0-only
"""Single-frame LightNav inference through LM Studio's local image API."""

import base64
import io
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

from PIL import Image

from decoder import decode_text


PREFIX = ("You are a mobile robot. You are given visual observations over time, "
          "ordered from earliest to most recent: <0.0 seconds>")
SUFFIX = (". Your assigned task is: <navigation_task>{}</navigation_task>. "
          "You may be at the beginning, middle, or end of the task. "
          "Predict your future trajectory as a sequence of coarse-to-fine trajectory tokens. ")


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("LM Studio endpoint must not redirect image requests")


class LMStudioClient:
    def __init__(self, endpoint: str, model: str, assets: Path, timeout=45.0):
        location = urlsplit(endpoint)
        if location.scheme != "http" or location.hostname not in ("127.0.0.1", "localhost", "::1"):
            raise ValueError("This experimental adapter only sends images to a local HTTP server")
        self.endpoint, self.model, self.assets, self.timeout = endpoint, model, assets, timeout
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())

    def predict(self, request: dict) -> tuple[dict, dict]:
        seq = request.get("seq")
        instruction = request.get("instruction")
        if type(seq) is not int or seq < 0:
            raise ValueError("seq must be a non-negative integer")
        if not isinstance(instruction, str) or not instruction.strip() or len(instruction) > 512:
            raise ValueError("instruction must contain 1..512 characters")
        encoded = request.get("image")
        if not isinstance(encoded, str) or len(encoded) > 4 * 1024 * 1024:
            raise ValueError("image must be a bounded base64 PNG/JPEG")
        content = base64.b64decode(encoded, validate=True)
        with Image.open(io.BytesIO(content)) as original:
            if original.format not in ("PNG", "JPEG") or original.width * original.height > 8_000_000:
                raise ValueError("image must be a PNG/JPEG of at most 8 megapixels")
            size = original.size
            if "frame_size" in request and request["frame_size"] != list(size):
                raise ValueError("frame_size does not match the decoded image")
            output = io.BytesIO()
            # Explicitly approximate the released training dimensions, without claiming SlowFast.
            original.convert("RGB").resize((448, 256), Image.Resampling.BICUBIC).save(output, "PNG")
        uri = "data:image/png;base64," + base64.b64encode(output.getvalue()).decode("ascii")
        payload = {"model": self.model, "temperature": 0, "max_tokens": 16, "stream": False,
                   "messages": [{"role": "user", "content": [
                       {"type": "text", "text": PREFIX},
                       {"type": "image_url", "image_url": {"url": uri}},
                       {"type": "text", "text": SUFFIX.format(instruction)},
                   ]}]}
        started = time.perf_counter()
        message = urllib.request.Request(self.endpoint, data=json.dumps(payload).encode("utf-8"),
                                         headers={"Content-Type": "application/json"})
        try:
            with self.opener.open(message, timeout=self.timeout) as response:
                raw = json.loads(response.read(1024 * 1024))
        except urllib.error.HTTPError as failure:
            with failure:
                detail = failure.read(4096).decode("utf-8", errors="replace")
            raise RuntimeError(f"LM Studio HTTP {failure.code}: {detail}") from failure
        elapsed = (time.perf_counter() - started) * 1000
        choices = raw.get("choices")
        if not isinstance(choices, list) or len(choices) != 1:
            raise ValueError("LM Studio did not return one completion")
        choice = choices[0]
        text = choice.get("message", {}).get("content")
        if not isinstance(text, str) or not text.strip():
            count = raw.get("usage", {}).get("completion_tokens")
            raise ValueError(f"LM Studio returned empty text after {count} tokens; "
                             "navigation special tokens may be hidden by the runtime")
        if choice.get("finish_reason") != "stop":
            raise ValueError(f"Incomplete model output: {choice.get('finish_reason')}")
        data = decode_text(text, self.assets, size)
        data.update(seq=seq, latency_ms=elapsed, model=self.model,
                    input_mode="single_frame_448x256; not official SlowFast",
                    session=request.get("session"))
        return {"action": "next", "data": data}, raw
