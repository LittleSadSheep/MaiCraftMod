// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.Target;

/**
 * 出行现场读端的朝向换算：游戏视角角（0 朝南，顺时针增大）换成东南西北。
 */
class ClientTravelWorldViewTest {

    @Test
    void 四个正方位各对应一个方向() {
        assertEquals(Target.Toward.SOUTH, ClientTravelWorldView.towardOf(0.0F));
        assertEquals(Target.Toward.WEST, ClientTravelWorldView.towardOf(90.0F));
        assertEquals(Target.Toward.NORTH, ClientTravelWorldView.towardOf(180.0F));
        assertEquals(Target.Toward.EAST, ClientTravelWorldView.towardOf(270.0F));
    }

    @Test
    void 负角度与跨圈角度照常折算() {
        assertEquals(Target.Toward.EAST, ClientTravelWorldView.towardOf(-90.0F));
        assertEquals(Target.Toward.SOUTH, ClientTravelWorldView.towardOf(360.0F));
    }

    @Test
    void 斜向靠到分量大的那一边() {
        // 朝南偏西 45 度：东西与南北分量相等，靠到南边。
        assertEquals(Target.Toward.SOUTH, ClientTravelWorldView.towardOf(45.0F));
        // 偏斜不到 45 度时按主分量。
        assertEquals(Target.Toward.SOUTH, ClientTravelWorldView.towardOf(30.0F));
        assertEquals(Target.Toward.WEST, ClientTravelWorldView.towardOf(60.0F));
    }
}
