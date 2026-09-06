# SPDX-License-Identifier: GPL-3.0-only
"""Fetch only the verified ~94 KB LightNav RVQ decoder bundle, never model weights."""

import argparse
import hashlib
from pathlib import Path
import urllib.request


FILES = {
    "manifest.json": "00d77ae028edf59e0ce68b49122dd0a60da527f48508f5ee335a580993a12864",
    "codebook_l0.npy": "daaea83b3ae4637a042d1f61b34a687870d735ef709689e02c1e43d39aab6e16",
    "codebook_l1.npy": "dfde603f96b333cda4c676613ac2f772cec3906a8dbbb7a7415bfeb4aa04e4bf",
    "codebook_l2.npy": "2a7b186159bc9375997535008028930efe09c9b239a522639aff6febfac6c51c",
    "jacobian_weights.npy": "43872af8deba9986c40da98b8511f5b94b3353a053132b22f7a190b2d7cf9feb",
}
BASE = "https://huggingface.co/LightOriginsHQ/LightNav-0/resolve/main/action_tokenizer/"


def prepare(directory: Path):
    directory.mkdir(parents=True, exist_ok=True)
    for name, digest in FILES.items():
        destination = directory / name
        if destination.exists():
            content = destination.read_bytes()
        else:
            with urllib.request.urlopen(BASE + name, timeout=60) as response:
                content = response.read(1024 * 1024)
        if hashlib.sha256(content).hexdigest() != digest:
            raise ValueError(f"Unexpected asset contents: {name}; review upstream changes first")
        if not destination.exists():
            destination.write_bytes(content)
        print(f"Verified {name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path,
                        default=Path(__file__).parent / "checkpoints/LightNav-0")
    args = parser.parse_args()
    prepare(args.assets / "action_tokenizer")
