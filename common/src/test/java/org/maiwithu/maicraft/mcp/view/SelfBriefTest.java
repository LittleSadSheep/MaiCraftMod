// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.menu.OpenScreen;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;
import org.maiwithu.maicraft.game.menu.ScreenOpening;

/** 结束事件附的一行自身摘要：位置含维度、血、饱食、水里才写氧气、手上、开着的界面；不附背包。 */
class SelfBriefTest {

    private static SceneSelf self(boolean inWater, int air, String held, String offhand) {
        return new SceneSelf(12.7, 64.0, -7.2, 180f, "北", 18, 15, air, 300, held, List.of(), List.of(),
                !inWater, inWater, List.of(), offhand);
    }

    @Test
    void 陆上一行写位置血饱食和手上() {
        String line = SelfBrief.line(self(false, 300, "minecraft:iron_pickaxe", null), "minecraft:overworld",
                Optional.empty());

        assertEquals("在 minecraft:overworld 12, 64, -8；血 18，饱食 15；手上 minecraft:iron_pickaxe", line);
        assertFalse(line.contains("氧气"), "陆上氧气总是满的，不写");
        assertFalse(line.contains("\n"), "只一行");
    }

    @Test
    void 水里写氧气副手有别的东西一并写() {
        String line = SelfBrief.line(self(true, 120, "minecraft:iron_sword", "minecraft:shield"),
                "minecraft:overworld", Optional.empty());

        assertTrue(line.contains("在水里，氧气 120/300"), line);
        assertTrue(line.contains("手上 minecraft:iron_sword（副手 minecraft:shield）"), line);
    }

    @Test
    void 两手空着开着界面时说界面和谁开的() {
        OpenScreen chest = new OpenScreen(OpenScreen.Kind.STORAGE, "minecraft:generic_9x3", "箱子", null, null,
                "界面刚打开，里面的东西还没从服务器同步过来，过一会儿再看", null, ScreenOpening.OpenedBy.TASK);
        String line = SelfBrief.line(self(false, 300, null, null), "minecraft:the_nether", Optional.of(chest));

        assertEquals("在 minecraft:the_nether 12, 64, -8；血 18，饱食 15；两手空着；"
                + "开着储物界面（minecraft:generic_9x3，任务点开的）", line);
    }
}
