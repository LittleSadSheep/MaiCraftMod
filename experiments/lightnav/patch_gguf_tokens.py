# SPDX-License-Identifier: GPL-3.0-only
"""Inspect by default; optionally copy GGUF with only nav CONTROL tokens made USER_DEFINED.

GGUF spec: https://github.com/ggml-org/ggml/blob/master/docs/gguf.md
Detokenization: https://github.com/ggml-org/llama.cpp/blob/master/src/llama-vocab.cpp
No tensor, token ID, token string, EOS/BOS or vision-token changes are permitted.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct

FORMATS = {0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i", 6: "f",
           7: "?", 10: "Q", 11: "q", 12: "d"}
NAV = re.compile(rb"<(apos|opos|act_l[012])_[0-9]+>")
OLD, NEW = struct.pack("<i", 3), struct.pack("<i", 4)


class Reader:
    def __init__(self, stream):
        self.f = stream
        self.limit = min(os.fstat(stream.fileno()).st_size, 64 * 1024 * 1024)

    def read(self, count):
        if count < 0 or self.f.tell() + count > self.limit:
            raise ValueError("Invalid or oversized GGUF metadata")
        value = self.f.read(count)
        if len(value) != count:
            raise ValueError("Truncated GGUF metadata")
        return value

    def num(self, fmt):
        return struct.unpack("<" + fmt, self.read(struct.calcsize(fmt)))[0]

    def string(self):
        return self.read(self.num("Q"))

    def skip(self, kind, depth=0):
        if depth > 4:
            raise ValueError("Unsupported nested metadata")
        if kind in FORMATS:
            self.read(struct.calcsize(FORMATS[kind]))
        elif kind == 8:
            self.string()
        elif kind == 9:
            element, count = self.num("I"), self.num("Q")
            if count > 2_000_000:
                raise ValueError("Oversized metadata array")
            for _ in range(count):
                self.skip(element, depth + 1)
        else:
            raise ValueError("Unknown GGUF value type")


def hash_bytes(stream, count, digest=None):
    digest = digest if digest is not None else hashlib.sha256()
    while count:
        data = stream.read(min(count, 1024 * 1024))
        if not data:
            raise ValueError("File truncated during hashing")
        digest.update(data)
        count -= len(data)
    return digest


def inspect(source):
    with Path(source).open("rb") as stream:
        r = Reader(stream)
        if r.read(4) != b"GGUF" or r.num("I") not in (2, 3):
            raise ValueError("Expected little-endian GGUF v2 or v3")
        tensors, count = r.num("Q"), r.num("Q")
        if count > 100_000:
            raise ValueError("Too many metadata keys")
        nav, special, seen, vocab_count, types_count, types_offset = [], {}, set(), None, None, None
        for _ in range(count):
            key, kind = r.string().decode("utf-8"), r.num("I")
            if key in seen:
                raise ValueError("Duplicate GGUF metadata key")
            seen.add(key)
            if key in ("tokenizer.ggml.tokens", "tokenizer.ggml.token_type"):
                if kind != 9:
                    raise ValueError("Token metadata must be arrays")
                element, length = r.num("I"), r.num("Q")
                if length > 2_000_000:
                    raise ValueError("Oversized vocabulary")
                if key.endswith(".tokens") and element == 8:
                    vocab_count = length
                    for token_id in range(length):
                        token = r.string()
                        if NAV.fullmatch(token):
                            nav.append((token_id, token.decode("ascii")))
                elif key.endswith(".token_type") and element == 5:
                    types_count, types_offset = length, stream.tell()
                    r.read(4 * length)
                else:
                    raise ValueError("Unexpected token metadata element type")
            elif key.endswith("_token_id") and kind in FORMATS:
                special[key] = r.num(FORMATS[kind])
            else:
                r.skip(kind)
        end = stream.tell()
        if not nav or types_offset is None or vocab_count != types_count:
            raise ValueError("Missing navigation vocabulary or mismatched token types")
        patches = []
        for token_id, token in nav:
            if token_id in special.values():
                raise ValueError("Navigation token is designated as a protected special token")
            offset = types_offset + 4 * token_id
            stream.seek(offset)
            patches.append({"id": token_id, "token": token, "offset": offset, "old_type": r.num("i")})
        stream.seek(0)
        header_hash = hash_bytes(stream, end).hexdigest()
        return {"source_size": os.fstat(stream.fileno()).st_size, "tensors": tensors,
                "vocab_count": vocab_count, "types_offset": types_offset, "metadata_end": end,
                "header_sha256": header_hash, "protected_ids": special, "patches": patches}


def patch_copy(source, output, expected_header_sha256):
    source, output = Path(source).resolve(), Path(output).resolve()
    if source == output or output.exists():
        raise ValueError("Output must be a new, separate file")
    plan = inspect(source)
    if plan["header_sha256"] != expected_header_sha256:
        raise ValueError("Source metadata hash differs from the reviewed dry run")
    if any(item["old_type"] != 3 for item in plan["patches"]):
        raise ValueError("Every navigation token must originally be CONTROL (3)")
    created = False
    try:
        with source.open("rb") as src, output.open("xb+") as dst:
            created = True
            before = os.fstat(src.fileno())
            if hash_bytes(src, plan["metadata_end"]).hexdigest() != expected_header_sha256:
                raise ValueError("Source metadata changed after inspection")
            src.seek(0)
            digest = hashlib.sha256()
            for chunk in iter(lambda: src.read(1024 * 1024), b""):
                digest.update(chunk)
                dst.write(chunk)
            after = os.fstat(src.fileno())
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise ValueError("Source changed during copy")
            for item in plan["patches"]:
                dst.seek(item["offset"])
                if dst.read(4) != OLD:
                    raise ValueError("Original token type differs at the reviewed offset")
                dst.seek(item["offset"])
                dst.write(NEW)
            dst.flush()
            dst.seek(0)
            verified = hashlib.sha256()
            for item in plan["patches"]:
                hash_bytes(dst, item["offset"] - dst.tell(), verified)
                if dst.read(4) != NEW:
                    raise ValueError("Patched type verification failed")
                verified.update(OLD)
            hash_bytes(dst, before.st_size - dst.tell(), verified)
            if verified.digest() != digest.digest() or dst.tell() != before.st_size:
                raise ValueError("Copy differs outside the authorized four-byte fields")
        return {"output": str(output), "patched_tokens": len(plan["patches"]),
                "source_sha256": digest.hexdigest(), "all_other_bytes_verified": True}
    except BaseException:
        if created:
            output.unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--write", action="store_true", help="Create a separate patched copy")
    parser.add_argument("--expect-header-sha256", help="Metadata hash from a reviewed dry run")
    args = parser.parse_args()
    if args.write:
        if args.output is None or not args.expect_header_sha256:
            parser.error("--write requires --output and --expect-header-sha256")
        result = patch_copy(args.source, args.output, args.expect_header_sha256)
    else:
        result = inspect(args.source)
        patches = result.pop("patches")
        result.update(patched_tokens=len(patches), examples=patches[:3] + patches[-3:], dry_run=True)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
