# SPDX-License-Identifier: GPL-3.0-only
import hashlib
from pathlib import Path
import struct
import tempfile
import unittest

from patch_gguf_tokens import inspect, patch_copy


def text(value):
    value = value.encode()
    return struct.pack("<Q", len(value)) + value


def fixture(nav_type=3, reverse=False, eos=6):
    tokens = ["normal", "<act_l0_0>", "<act_l1_5>", "<act_l2_9>", "<apos_1299>",
              "<opos_0>", "<|im_end|>", "<|vision_start|>", "prefix<apos_3>", "<act_l3_0>"]
    entries = [text("tokenizer.ggml.tokens") + struct.pack("<IIQ", 9, 8, len(tokens))
               + b"".join(text(token) for token in tokens),
               text("tokenizer.ggml.token_type") + struct.pack("<IIQ", 9, 5, len(tokens))
               + struct.pack("<10i", 1, *([nav_type] * 5), 3, 3, 1, 3)]
    if reverse:
        entries.reverse()
    entries.extend([text("tokenizer.ggml.eos_token_id") + struct.pack("<II", 4, eos),
                    text("tokenizer.ggml.bos_token_id") + struct.pack("<II", 4, 7),
                    text("general.alignment") + struct.pack("<II", 4, 32)])
    return b"GGUF" + struct.pack("<IQQ", 3, 0, len(entries)) + b"".join(entries) + bytes(range(256)) * 8192


class PatchGgufTokensTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.source = Path(self.directory.name) / "source.gguf"
        self.output = Path(self.directory.name) / "copy.gguf"
        self.source.write_bytes(fixture())

    def test_copy_changes_only_named_type_fields_and_leaves_source(self):
        original = self.source.read_bytes()
        plan = inspect(self.source)
        self.assertEqual(len(plan["patches"]), 5)
        result = patch_copy(self.source, self.output, plan["header_sha256"])
        self.assertEqual(self.source.read_bytes(), original)
        self.assertEqual(result["source_sha256"], hashlib.sha256(original).hexdigest())
        patched = bytearray(self.output.read_bytes())
        for item in plan["patches"]:
            offset = item["offset"]
            self.assertEqual(patched[offset:offset + 4], struct.pack("<i", 4))
            patched[offset:offset + 4] = struct.pack("<i", 3)
        self.assertEqual(bytes(patched), original)  # Includes tensor-tail and EOS/vision bytes.
        self.assertEqual({p["old_type"] for p in inspect(self.output)["patches"]}, {4})

    def test_arrays_may_appear_in_reverse_order(self):
        self.source.write_bytes(fixture(reverse=True))
        self.assertEqual(len(inspect(self.source)["patches"]), 5)

    def test_mismatched_review_hash_never_creates_output(self):
        with self.assertRaises(ValueError):
            patch_copy(self.source, self.output, "0" * 64)
        self.assertFalse(self.output.exists())

    def test_existing_output_and_source_cannot_be_overwritten(self):
        self.output.write_bytes(b"existing")
        for output in (self.source, self.output):
            with self.assertRaises(ValueError):
                patch_copy(self.source, output, inspect(self.source)["header_sha256"])
        self.assertEqual(self.output.read_bytes(), b"existing")

    def test_noncontrol_navigation_tokens_are_rejected(self):
        self.source.write_bytes(fixture(nav_type=4))
        with self.assertRaises(ValueError):
            patch_copy(self.source, self.output, inspect(self.source)["header_sha256"])
        self.assertFalse(self.output.exists())

    def test_designated_eos_navigation_token_is_never_patched(self):
        self.source.write_bytes(fixture(eos=1))
        with self.assertRaises(ValueError):
            inspect(self.source)

    def test_bad_header_and_truncated_arrays_are_rejected(self):
        for data in (b"not GGUF", fixture()[:120], b"GGUF" + struct.pack(">IQQ", 3, 0, 0)):
            self.source.write_bytes(data)
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                inspect(self.source)


if __name__ == "__main__":
    unittest.main()
