// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 水里追猎物：水面上漂着先按潜行沉下去，眼睛进了水按疾跑顺着镜头游，它在头顶上按跳；浅水里站着不蹲；
 * 氧气不够从这个深度浮上去就先上去，头出了水踩着水等氧气补满再回来追。
 */
class SwimChaseTest {

    private static final int FULL = 300;

    private static SwimChase.Scene scene(long tick, double dx, double dy, double dz, double belowFeet,
            boolean eyesInWater, boolean onGround, int air, double depth) {
        return new SwimChase.Scene(tick, dx, dy, dz, belowFeet, eyesInWater, onGround, air, FULL, depth);
    }

    @Test
    void 水面上漂着_鱼在斜下方深处_往前挪按潜行沉下去_镜头往下看() {
        SwimChase.Stroke stroke = new SwimChase().next(scene(1, 3, -4, 0, 2.4, false, false, FULL, -0.5), 0f);
        assertEquals(1.0f, stroke.forward());
        assertTrue(stroke.sneak());
        assertFalse(stroke.jump());
        // 眼睛没进水时原版不让切游泳姿势，按疾跑也没用。
        assertFalse(stroke.sprint());
        assertEquals(-90.0f, stroke.yaw(), 0.01f);
        assertTrue(stroke.pitch() > 50 && stroke.pitch() < 56, "朝下约 53 度：" + stroke.pitch());
    }

    @Test
    void 眼睛进了水_按着疾跑顺着镜头游_它在正下方也往前游() {
        SwimChase.Stroke stroke = new SwimChase().next(scene(1, 0.1, -3, 0, 1.3, true, false, FULL, 2), 0f);
        assertEquals(1.0f, stroke.forward());
        assertTrue(stroke.sprint());
        assertTrue(stroke.sneak());
        assertTrue(stroke.pitch() > 85, "几乎正朝下：" + stroke.pitch());
    }

    @Test
    void 它在头顶上_按跳往上游_镜头往上看() {
        SwimChase.Stroke stroke = new SwimChase().next(scene(1, 1, 2, 0, -3.5, true, false, FULL, 4), 0f);
        assertTrue(stroke.jump());
        assertFalse(stroke.sneak());
        assertTrue(stroke.pitch() < 0, "往上看：" + stroke.pitch());
    }

    @Test
    void 浅水里站在水底_鱼在脚边_不蹲不跳往前走() {
        SwimChase.Stroke stroke = new SwimChase().next(scene(1, 2, -1.3, 0, -0.2, false, true, FULL, -0.6), 0f);
        assertEquals(1.0f, stroke.forward());
        assertFalse(stroke.sneak(), "站着蹲下只会走得更慢");
        assertFalse(stroke.jump());
        assertFalse(stroke.sprint());
    }

    @Test
    void 水面上它就在正下方_不往前冲_只往下沉() {
        SwimChase.Stroke stroke = new SwimChase().next(scene(1, 0.2, -4, 0.1, 2.5, false, false, FULL, -0.5), 0f);
        assertEquals(0.0f, stroke.forward());
        assertTrue(stroke.sneak());
    }

    @Test
    void 氧气不够从这么深浮上去_先上去换气_头出水踩着水等补满再回来追() {
        SwimChase chase = new SwimChase();
        // 六格深，上浮要五十刻再留三秒：只剩一百刻的氧气就该上去了。
        SwimChase.Stroke up = chase.next(scene(1, 2, -1, 0, 0.5, true, false, 100, 6), 0f);
        assertTrue(up.surfacing());
        assertTrue(up.jump());
        assertEquals(0.0f, up.forward());
        assertTrue(up.pitch() < 0, "抬头往上游：" + up.pitch());
        // 还在水里、氧气接着掉：照旧往上。
        assertTrue(chase.next(scene(11, 2, -1, 0, 0.5, true, false, 90, 3), 0f).surfacing());
        // 头出了水但氧气没满：踩着水不沉下去。
        SwimChase.Stroke tread = chase.next(scene(20, 2, -3, 0, 2, false, false, 200, -0.5), 0f);
        assertTrue(tread.surfacing());
        assertTrue(tread.jump());
        // 补满了：回去接着追。
        SwimChase.Stroke back = chase.next(scene(30, 2, -3, 0, 2, false, false, FULL, -0.5), 0f);
        assertFalse(back.surfacing());
        assertEquals(1.0f, back.forward());
        assertTrue(back.sneak());
    }

    @Test
    void 氧气还够_接着追不上去() {
        SwimChase.Stroke stroke = new SwimChase().next(scene(1, 2, -1, 0, 0.5, true, false, 200, 6), 0f);
        assertFalse(stroke.surfacing());
        assertTrue(stroke.sprint());
    }
}
