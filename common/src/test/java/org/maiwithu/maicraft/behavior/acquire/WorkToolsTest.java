// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 成批干活先备效率工具：砍一片树先做石斧，木镐挖三块石头就换石镐；开局先徒手弄够做第一把工具的料。 */
class WorkToolsTest {

    @Test
    void 基准工具至少是石制_要铁镐的矿按铁镐() {
        assertEquals(Optional.of("minecraft:stone_axe"), WorkTools.reference("axe", 0));
        assertEquals(Optional.of("minecraft:stone_pickaxe"), WorkTools.reference("pickaxe", 0));
        assertEquals(Optional.of("minecraft:iron_pickaxe"), WorkTools.reference("pickaxe", 2));
        assertEquals(Optional.empty(), WorkTools.reference("", 0), "不对口的方块不备");
    }

    @Test
    void 活少又没拿木制工具在干_直接干不备工具() {
        assertEquals(Optional.empty(), WorkTools.toPrepare(WorkTools.reference("axe", 0), 3, false, false));
    }

    @Test
    void 成批干活先备石制工具() {
        assertEquals(Optional.of("minecraft:stone_axe"), WorkTools.toPrepare(WorkTools.reference("axe", 0), 12, false, false));
    }

    @Test
    void 拿着木镐挖石头_活再少也换石镐() {
        assertEquals(Optional.of("minecraft:stone_pickaxe"),
                WorkTools.toPrepare(WorkTools.reference("pickaxe", 0), 1, true, false));
    }

    @Test
    void 身上已有够快够耐久的_不备() {
        assertEquals(Optional.empty(), WorkTools.toPrepare(WorkTools.reference("axe", 0), 12, false, true));
    }

    @Test
    void 两手空空砍树_先徒手砍够工作台木镐木棍的木头() {
        // 工作台 4 板 + 木镐 3 板 + 木棍 2 板 = 9 板，要先砍 3 根原木。
        assertEquals(3, WorkTools.bootstrap(true, false, 0, 0, 0, false, false, 0));
        // 手上已有 3 根原木（12 板）就够了，接着去备石斧。
        assertEquals(0, WorkTools.bootstrap(true, false, 3, 0, 0, false, false, 0));
        // 有工作台和镐、木棍够：不用先砍。
        assertEquals(0, WorkTools.bootstrap(true, false, 0, 0, 2, true, true, 0));
    }

    @Test
    void 有镐挖石头_先挖够三块石料再换石镐() {
        assertEquals(3, WorkTools.bootstrap(false, true, 0, 0, 0, false, true, 0));
        assertEquals(1, WorkTools.bootstrap(false, true, 0, 0, 0, false, true, 2));
        assertEquals(0, WorkTools.bootstrap(false, true, 0, 0, 0, false, true, 3));
    }

    @Test
    void 没有镐的石头_不算开局料() {
        assertEquals(0, WorkTools.bootstrap(false, true, 0, 0, 0, false, false, 0));
    }
}
