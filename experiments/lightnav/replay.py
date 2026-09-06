# SPDX-License-Identifier: GPL-3.0-only
"""Replay an ordered Minecraft image sequence against a running LightNav server."""

import argparse
import base64
import json
import math
from pathlib import Path
import re
import time
from urllib.parse import urlsplit

from protocol import exchange


def frame_paths(directory: Path) -> list[Path]:
    if not directory.is_dir():
        raise ValueError(f"Frame directory does not exist: {directory}")
    paths = [p for p in directory.iterdir() if p.is_file()
             and p.suffix.lower() in (".jpg", ".jpeg", ".png")]
    paths.sort(key=lambda p: [int(part) if part.isdigit() else part
                             for part in re.split(r"(\d+)", p.name.lower())])
    if not paths:
        raise ValueError("No PNG/JPEG frames found")
    return paths


def image_payload(path: Path) -> tuple[str, str]:
    # Leave headroom for base64 and JSON under the upstream 64 MiB request limit.
    if path.stat().st_size > 40 * 1024 * 1024:
        raise ValueError(f"Frame exceeds 40 MiB: {path.name}")
    content = path.read_bytes()
    if content.startswith(b"\x89PNG\r\n\x1a\n"):
        mime = "image/png"
    elif content.startswith(b"\xff\xd8\xff"):
        mime = "image/jpeg"
    else:
        raise ValueError(f"Frame is not a PNG/JPEG: {path.name}")
    return mime, base64.b64encode(content).decode("ascii")


def replay(ws, frames, instruction, output, timeout=120.0, warmup_frames=0):
    """One connection and reset per clip; preserve every successful raw response."""
    from report import write_report

    records = []
    exchange(ws, "login", {"clientId": "maicraft-lightnav-replay"}, timeout)
    exchange(ws, "reset", {}, timeout)
    with (output / "predictions.jsonl").open("x", encoding="utf-8") as log:
        try:
            for seq, frame in enumerate(frames):
                mime, encoded = image_payload(frame)
                started = time.perf_counter()
                response = exchange(ws, "next", {
                    "seq": seq, "image": encoded,
                    "instruction": "" if seq < warmup_frames else instruction,
                }, timeout)
                record = {"seq": seq, "frame": frame.name,
                          "roundtrip_ms": (time.perf_counter() - started) * 1000,
                          "response": response}
                log.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
                log.flush()
                records.append({**record, "image": f"data:{mime};base64,{encoded}"})
                print(f"[{seq + 1}/{len(frames)}] {frame.name}: "
                      f"{record['roundtrip_ms']:.0f} ms", flush=True)
        finally:
            write_report(records, output / "report.html", instruction)
    return len(records)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frames", type=Path, required=True)
    parser.add_argument("--instruction", required=True)
    parser.add_argument("--output", type=Path, required=True,
                        help="New directory for JSONL and a self-contained HTML report")
    parser.add_argument("--server", default="ws://127.0.0.1:8050")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--warmup-frames", type=int, default=0,
                        help="Initial frames to buffer without inference")
    args = parser.parse_args()
    try:
        frames = frame_paths(args.frames)
        if not args.instruction.strip():
            raise ValueError("Instruction must not be empty")
        if not math.isfinite(args.timeout) or args.timeout <= 0:
            raise ValueError("Timeout must be positive and finite")
        if not 0 <= args.warmup_frames < len(frames):
            raise ValueError("Warmup must leave at least one frame for prediction")
        server = urlsplit(args.server)
        if server.scheme not in ("ws", "wss") or not server.hostname:
            raise ValueError("Server must be a ws:// or wss:// URL")
        from websockets.sync.client import connect

        args.output.mkdir(parents=True, exist_ok=False)
        manifest = {"server": args.server, "instruction": args.instruction,
                    "frames": [p.name for p in frames], "warmup_frames": args.warmup_frames,
                    "mode": "offline replay; predictions only; no game control"}
        (args.output / "session.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        with connect(args.server, open_timeout=args.timeout, close_timeout=5,
                     max_size=4 * 1024 * 1024) as ws:
            count = replay(ws, frames, args.instruction, args.output,
                           args.timeout, args.warmup_frames)
        print(f"Saved {count} responses: {args.output.resolve() / 'report.html'}")
    except ImportError:
        parser.exit(1, "Install dependencies: python -m pip install -r "
                    "experiments/lightnav/requirements.txt\n")
    except KeyboardInterrupt:
        parser.exit(130, "Interrupted; completed responses are retained.\n")
    except Exception as exc:
        parser.exit(1, f"Replay failed: {exc}\n")


if __name__ == "__main__":
    main()
