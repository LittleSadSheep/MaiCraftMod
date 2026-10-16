// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 炉子正忙换一台：近的在前、连第一台最多三台、都没用上时逐台说清。 */
class FurnaceChoicesTest {

    private static WorldPosition at(int x) {
        return new WorldPosition(x, 64, 0, "minecraft:overworld");
    }

    @Test
    void busyFurnacesAreSkippedInOrderUpToThree() {
        // 记得四台：近的那台正忙换第二台，第二台走不到换第三台，第三台还忙就不再找第四台。
        FurnaceChoices choices = new FurnaceChoices(List.of(at(1), at(2), at(3), at(4)));
        assertEquals(at(1), choices.current());
        assertTrue(choices.onFirst());

        assertTrue(choices.skip("正忙，产出格里放着熟牛肉"));
        assertEquals(at(2), choices.current());
        assertFalse(choices.onFirst());

        assertTrue(choices.skip("走不到熔炉跟前"));
        assertEquals(at(3), choices.current());

        assertFalse(choices.skip("正忙，投入口里在烧生鸡肉"), "连第一台试满三台就收场");
        assertEquals(3, choices.tried());
        assertEquals(at(3), choices.current(), "试满了眼下这台不变");
    }

    @Test
    void theSummaryNamesEveryFurnaceAndWhy() {
        FurnaceChoices choices = new FurnaceChoices(List.of(at(1), at(2)));
        choices.skip("正忙，产出格里放着熟牛肉");
        choices.skip("正忙，投入口里在烧沙子");
        String summary = choices.summary("熔炉");
        assertTrue(summary.startsWith("试了 2 台熔炉都没用上"), summary);
        assertTrue(summary.contains("(1, 64, 0) 正忙，产出格里放着熟牛肉"), summary);
        assertTrue(summary.contains("(2, 64, 0) 正忙，投入口里在烧沙子"), summary);
    }

    @Test
    void onlyOneRememberedFurnaceHasNothingToSwitchTo() {
        FurnaceChoices choices = new FurnaceChoices(List.of(at(1)));
        assertFalse(choices.skip("正忙，产出格里放着熟牛肉"));
        assertEquals(1, choices.tried());
    }
}
