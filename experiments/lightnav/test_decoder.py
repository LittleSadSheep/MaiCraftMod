# SPDX-License-Identifier: GPL-3.0-only
"""Synthetic decoder checks independent of downloaded checkpoint files."""

import json
from pathlib import Path
import tempfile
import unittest

import numpy as np

from decoder import decode_text


class DecoderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bundle = self.root / "action_tokenizer"
        self.bundle.mkdir()
        self.manifest = {"method": "rvq", "horizon": 10, "feature_dim": 30,
                         "levels": [256] * 3, "representation": "se2_diff",
                         "codebook_files": [f"codebook_l{i}.npy" for i in range(3)],
                         "jacobian_weights_file": "weights.npy", "stop": {"l0": 6}}
        (self.bundle / "manifest.json").write_text(json.dumps(self.manifest))
        np.save(self.bundle / "weights.npy", np.full(30, 2, dtype=np.float32))
        for level in range(3):
            book = np.zeros((256, 30), dtype=np.float32)
            # Weight division yields two 1m local-forward steps, with a left turn
            # after the first. Residual levels each add another 0.25m second step.
            book[0, :6] = [2, 0, np.pi, 2, 0, 0] if level == 0 else [0, 0, 0, 0.5, 0, 0]
            np.save(self.bundle / f"codebook_l{level}.npy", book)
        self.tokens = "<act_l0_0><act_l1_0><act_l2_0>"

    def test_se2_composition_and_residual_deweighting(self):
        result = decode_text(self.tokens, self.root, (480, 270))
        path = result["actions"]["actions"]
        np.testing.assert_allclose(path[0], [1, 0, np.pi / 2], atol=1e-6)
        np.testing.assert_allclose(path[1], [1, 1.5, np.pi / 2], atol=1e-6)
        self.assertFalse(result["stop"])
        self.assertIsNone(result["visible"])

    def test_pointing_cells_and_sentinels(self):
        result = decode_text("<apos_50><opos_1296>" + self.tokens, self.root, (480, 270))
        self.assertEqual(result["pointing"]["apos_px"], [15, 15])
        self.assertEqual(result["pointing"]["opos_px"], [475, 265])
        self.assertFalse(result["pointing"]["apos_clamped"])
        self.assertTrue(result["pointing"]["opos_clamped"])
        result = decode_text("<apos_1299><opos_0>" + self.tokens, self.root, (480, 270))
        self.assertEqual(result["pointing"]["apos_state"], "stop")
        self.assertFalse(result["stop"])  # Pointing does not override trajectory.
        self.assertFalse(result["visible"])

    def test_explicit_and_near_zero_stop(self):
        for token in ("<act_l0_6><act_l1_0><act_l2_0>", "<act_l0_1><act_l1_1><act_l2_1>"):
            self.assertTrue(decode_text(token, self.bundle, (480, 270))["stop"])

    def test_rejects_missing_duplicate_out_of_range_tokens(self):
        for text in ("", "Here is a path", self.tokens[:-10], self.tokens + "<act_l0_0>",
                     self.tokens.replace("l2_0", "l2_256"), "<apos_1300>" + self.tokens):
            with self.subTest(text=text), self.assertRaises(ValueError):
                decode_text(text, self.root, (480, 270))

    def test_rejects_invalid_assets(self):
        np.save(self.bundle / "weights.npy", np.zeros(30))
        with self.assertRaises(ValueError):
            decode_text(self.tokens, self.root, (480, 270))


if __name__ == "__main__":
    unittest.main()
