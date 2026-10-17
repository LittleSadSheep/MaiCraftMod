// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/** 随行补光的会话设置：默认开（阈值 8）、进世界重置为默认、仍暗的路线格只记最近的。 */
class FollowLightingTest {

    @Test
    void 默认开_阈值八() {
        FollowLighting follow = new FollowLighting();
        assertTrue(follow.enabled());
        assertEquals(8, follow.minimumLight());
    }

    @Test
    void 开关与阈值_按给的来() {
        FollowLighting follow = new FollowLighting();
        follow.enable(13);
        assertEquals(13, follow.minimumLight());
        follow.disable();
        assertFalse(follow.enabled());
    }

    @Test
    void 阈值夹在一到十三() {
        FollowLighting follow = new FollowLighting();
        follow.enable(0);
        assertEquals(1, follow.minimumLight());
        follow.enable(99);
        assertEquals(13, follow.minimumLight(), "火把相邻格最高 13，再高永远点不满");
    }

    @Test
    void 进世界重置为默认() {
        FollowLighting follow = new FollowLighting();
        follow.disable();
        follow.noteDark(new BlockPos(0, 64, 0), 2, 10);
        follow.reset();
        assertTrue(follow.enabled());
        assertEquals(8, follow.minimumLight());
        assertTrue(follow.darkOnRoute().isEmpty(), "仍暗的路线格作废");
    }

    @Test
    void 仍暗的路线格按时间排_同一格只留最新一次() {
        FollowLighting follow = new FollowLighting();
        follow.noteDark(new BlockPos(1, 64, 1), 3, 10);
        follow.noteDark(new BlockPos(2, 64, 1), 4, 20);
        follow.noteDark(new BlockPos(1, 64, 1), 2, 30);
        assertEquals(2, follow.darkOnRoute().size());
        assertEquals(30, follow.darkOnRoute().get(1).tick(), "同一格更新时间与亮度");
    }

    @Test
    void 仍暗的路线格只记最近的几条() {
        FollowLighting follow = new FollowLighting();
        for (int x = 0; x < 100; x++) {
            follow.noteDark(new BlockPos(x, 64, 0), 1, x);
        }
        assertEquals(32, follow.darkOnRoute().size(), "状态查询报最近的，不是全程记录");
    }
}
