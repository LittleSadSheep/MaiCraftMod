// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 无线红石打字机键名：写给无线红石打字机的才认；字母数字按大写字符码，方向键与空格中英文都认；键码写回名字。 */
class TypewriterKeysTest {

    @Test
    void 只认写给无线红石打字机的输入() {
        assertTrue(TypewriterKeys.addressed("打字机:W"));
        assertTrue(TypewriterKeys.addressed("typewriter：w"));
        assertFalse(TypewriterKeys.addressed("遥控器:上"));
        assertFalse(TypewriterKeys.addressed("W"));
        assertEquals(TypewriterKeys.UNKNOWN, TypewriterKeys.key("遥控器:上"));
    }

    @Test
    void 字母数字与有名字的键() {
        assertEquals('W', TypewriterKeys.key("打字机:w"));
        assertEquals('7', TypewriterKeys.key("打字机: 7"));
        assertEquals(32, TypewriterKeys.key("打字机:空格"));
        assertEquals(265, TypewriterKeys.key("typewriter:UP"));
        assertEquals(263, TypewriterKeys.key("打字机：左"));
        assertEquals(TypewriterKeys.UNKNOWN, TypewriterKeys.key("打字机:F13"));
    }

    @Test
    void 键码写回名字() {
        assertEquals("W", TypewriterKeys.name('W'));
        assertEquals("空格", TypewriterKeys.name(32));
        assertEquals("键码 300", TypewriterKeys.name(300));
        assertEquals("S、W、空格", TypewriterKeys.names(List.of(32, (int) 'W', (int) 'S')));
    }
}
