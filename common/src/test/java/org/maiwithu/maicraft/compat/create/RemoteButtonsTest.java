// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 遥控器的键名：上下左右、跳、潜行，中英文都认，冒号全角半角都行。 */
class RemoteButtonsTest {

    @Test
    void 键名换成遥控器的键序号() {
        assertEquals(0, RemoteButtons.button("遥控器:上"));
        assertEquals(1, RemoteButtons.button("遥控器：后"));
        assertEquals(4, RemoteButtons.button("遥控器:Jump"));
        assertEquals(5, RemoteButtons.button("遥控器:潜行"));
        assertEquals(-1, RemoteButtons.button("遥控器:W"));
        assertEquals(-1, RemoteButtons.button("遥控器"));
    }
}
