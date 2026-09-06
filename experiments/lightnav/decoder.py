# SPDX-License-Identifier: GPL-3.0-only
"""Decode LightOriginsHQ/LightNav-0 grid/RVQ output without torch.

Arithmetic and token semantics follow upstream commit 33a1b994793bd47bcb8836b8690a871f12623455:
https://github.com/lightorigins/LightNav-0/blob/main/src/lightnav/traj_vocab.py
https://github.com/lightorigins/LightNav-0/blob/main/src/lightnav/serving/protocol.py
This is output decoding only; a generic GGUF image input does not reproduce SlowFast.
"""

import json
import re
from pathlib import Path

import numpy as np


def _asset(directory: Path, name: str) -> Path:
    path = (directory / name).resolve()
    if not path.is_relative_to(directory.resolve()):
        raise ValueError("Decoder asset must remain inside its bundle")
    return path


def _waypoints(codes: list[int], directory: Path) -> np.ndarray:
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if (manifest.get("method") != "rvq" or manifest.get("horizon") != 10
            or manifest.get("feature_dim") != 30 or manifest.get("levels") != [256] * 3
            or manifest.get("representation") != "se2_diff"):
        raise ValueError("Expected the released LightNav-0 3-level, 10-waypoint RVQ bundle")
    files = manifest["codebook_files"]
    if len(files) != 3:
        raise ValueError("Expected three codebooks")
    reconstruction = np.zeros(30, dtype=np.float32)
    for code, filename in zip(codes, files):
        book = np.load(_asset(directory, filename), allow_pickle=False).astype(np.float32)
        if book.shape != (256, 30) or not np.isfinite(book).all():
            raise ValueError("Codebook must contain 256 x 30 finite values")
        reconstruction += book[code]
    weights = np.load(_asset(directory, manifest["jacobian_weights_file"]),
                      allow_pickle=False).astype(np.float32)
    if weights.shape != (30,) or not np.isfinite(weights).all() or (weights <= 0).any():
        raise ValueError("Jacobian weights must contain 30 positive finite values")
    deltas = (reconstruction / weights).reshape(10, 3).astype(np.float64)
    result = np.empty((10, 3), dtype=np.float64)
    x = y = yaw = 0.0
    for index, (dx, dy, dyaw) in enumerate(deltas):
        cos_yaw, sin_yaw = np.cos(yaw), np.sin(yaw)
        x += cos_yaw * dx - sin_yaw * dy
        y += sin_yaw * dx + cos_yaw * dy
        yaw = (yaw + dyaw + np.pi) % (2 * np.pi) - np.pi
        result[index] = (x, y, yaw)
    result = result.astype(np.float32)
    if not np.isfinite(result).all():
        raise ValueError("Decoded trajectory is nonfinite")
    # Explicit l0 stop overrides the small residuals, as in TrackingAgent.
    if codes[0] == (manifest.get("stop") or {}).get("l0") or (np.abs(result) <= 0.005).all():
        result.fill(0)
    return result


def decode_text(text: str, assets: Path, frame_size: tuple[int, int]) -> dict:
    """Return report-compatible response data, or raise on absent/malformed actions.

    assets is the checkpoint root or its action_tokenizer directory. No downloading
    or code execution occurs. The caller supplies rc/seq transport metadata as needed.
    Three action levels are mandatory; a pointing stop never overrides RVQ movement.
    """
    if not isinstance(text, str):
        raise ValueError("Model output must be text with special tokens preserved")
    if len(frame_size) != 2 or any(type(v) is not int or v <= 0 for v in frame_size):
        raise ValueError("frame_size must be positive integer (width, height)")
    matches = re.findall(r"<act_l(\d+)_(\d+)>", text)
    if [int(level) for level, _ in matches] != [0, 1, 2]:
        raise ValueError("Expected one each of <act_l0_N><act_l1_N><act_l2_N>; "
                         "ensure the inference backend preserves special tokens")
    codes = [int(code) for _, code in matches]
    if any(code >= 256 for code in codes):
        raise ValueError("RVQ codes must be in 0..255")
    directory = Path(assets)
    if not (directory / "manifest.json").is_file():
        directory /= "action_tokenizer"
    waypoints = _waypoints(codes, directory)
    pointing = {"mode": "grid", "frame_size": list(frame_size)}
    ids = {}
    for channel, maximum in (("apos", 1299), ("opos", 1296)):
        values = re.findall(rf"<{channel}_(\d+)>", text)
        if len(values) > 1 or (values and int(values[0]) > maximum):
            raise ValueError(f"Invalid or repeated {channel} token")
        value = int(values[0]) if values else None
        ids[channel] = value
        pixel, clamped, state = None, False, "none"
        if value is not None and 1 <= value <= 1296:
            row, col = divmod(value - 1, 48)
            pixel = [round((col + 0.5) * frame_size[0] / 48, 2),
                     round((row + 0.5) * frame_size[1] / 27, 2)]
            clamped, state = col in (0, 47) or row in (0, 26), "point"
        elif channel == "apos":
            state = {1297: "rot_left", 1298: "rot_right", 1299: "stop"}.get(value, "none")
        elif value == 0:
            state = "not_visible"
        pointing.update({f"{channel}_px": pixel, f"{channel}_clamped": clamped,
                         f"{channel}_state": state})
    return {"rc": 0, "actions": {"step": 0, "actions": waypoints.tolist()},
            "stop": bool((waypoints == 0).all()),
            "visible": None if ids["opos"] is None else ids["opos"] > 0,
            "pointing": pointing if any(v is not None for v in ids.values()) else None,
            "rvq_codes": codes, "raw_text": text}
