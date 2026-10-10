// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 跑跳的轨迹预测：原版平地一跳十二刻；顶棚两格高时撞头、更矮更短（低顶连跳更密）；参数不合理不跳。 */
class TravelJumpPhysicsTest {

    private static final TravelJumpPhysics.Launch VANILLA = new TravelJumpPhysics.Launch(.08, .42, .48, .13, .546, 1.8);

    @Test
    void 平地一跳十二刻_顶棚两格高撞头三刻() {
        var open = TravelJumpPhysics.project(VANILLA, false);
        var low = TravelJumpPhysics.project(VANILLA, true);
        assertNotNull(open);
        assertNotNull(low);
        assertEquals(12, open.airborneTicks(), "原版平地一跳在空中十二刻");
        assertEquals(3, low.airborneTicks(), "撞头那一刻重力照样算，三刻就落地");
        assertEquals(.2, low.apexHeight(), 1e-6, "两格高的顶棚把成年人的上升压到两格减身高");
        assertTrue(low.forwardDistance() < open.forwardDistance(), "顶棚矮的走廊这一跳更短");
    }

    @Test
    void 起跳与空中的摩擦按原版顺序算() {
        var low = TravelJumpPhysics.project(VANILLA, true);
        assertNotNull(low);
        // 第一刻地面移动按 (.48 + .13) * .546 计算，随后在位移前应用空中加速度。
        double first = .48 + .13, second = first * .546 + .026, third = second * .91 + .026;
        assertEquals(first + second + third, low.forwardDistance(), 1e-9);
    }

    @Test
    void 参数不合理就不跳() {
        assertNull(TravelJumpPhysics.project(new TravelJumpPhysics.Launch(0, .42, .48, .13, .546, 1.8), false),
                "没有重力就落不回地面");
        assertNull(TravelJumpPhysics.project(new TravelJumpPhysics.Launch(.0001, .42, .48, .13, .546, 1.8), false),
                "飞个没完的不当成普通一跳");
        assertNull(TravelJumpPhysics.project(new TravelJumpPhysics.Launch(.08, Double.NaN, .48, .13, .546, 1.8), false),
                "属性读不出来就不批这一跳");
    }
}
