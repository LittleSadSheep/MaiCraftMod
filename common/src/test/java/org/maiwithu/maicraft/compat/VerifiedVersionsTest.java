// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证过的版本范围：下界含、上界不含，多出来的段算在后面。 */
class VerifiedVersionsTest {

    private final VerifiedVersions range = new VerifiedVersions("3.25.69", "3.26");

    @Test
    void 下界算在范围内上界不算() {
        assertTrue(range.contains("3.25.69"));
        assertFalse(range.contains("3.26"));
        assertFalse(range.contains("3.26.0"));
    }

    @Test
    void 带构建号的版本在下界之后上界之前() {
        assertTrue(range.contains("3.25.69.1979"));
        assertTrue(range.contains("3.25.70"));
        assertFalse(range.contains("3.24.99"));
        assertFalse(range.contains("3.26.1"));
    }

    @Test
    void 版本不明算不在范围内() {
        assertFalse(range.contains(null));
        assertFalse(range.contains(" "));
    }

    @Test
    void 下界不小于上界的范围写错了() {
        assertThrows(IllegalArgumentException.class, () -> new VerifiedVersions("3.26", "3.25.69"));
        assertThrows(IllegalArgumentException.class, () -> new VerifiedVersions("3.26", "3.26"));
    }

    @Test
    void 日志里写成方括号圆括号() {
        assertEquals("[3.25.69, 3.26)", range.describe());
    }
}
