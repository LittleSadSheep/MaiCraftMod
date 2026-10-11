// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 露天判断与平台复核：树叶不算遮挡、未加载不判、平台要脚下与四邻三格同高支撑。 */
class OpenSkyTest {

    @Test
    void 顶盖正好在脚底算露天() {
        // 站在地表：忽略树叶的最高遮挡物就是脚下那块（顶盖 y = 脚 y − 1）。
        ColumnSample surface = new ColumnSample(true, "minecraft:plains", 63, false, true,
                "minecraft:grass_block");
        assertInstanceOf(OpenSky.Verdict.Open.class, OpenSky.judge(surface, 64));
    }

    @Test
    void 树冠底下照样算露天_树叶不算遮挡() {
        // 高度图忽略树叶：树冠不把树底变成"被埋"，顶盖是树底下的泥土。
        ColumnSample underCanopy = new ColumnSample(true, "minecraft:forest", 63, false, true,
                "minecraft:dirt");
        assertInstanceOf(OpenSky.Verdict.Open.class, OpenSky.judge(underCanopy, 64));
    }

    @Test
    void 顶盖在脚下之上是被埋_带顶盖事实() {
        // 矿道里：头顶三十格才是地表。
        ColumnSample buried = new ColumnSample(true, "minecraft:dripstone_caves", 93, false, true,
                "minecraft:stone");
        OpenSky.Verdict.Buried verdict = assertInstanceOf(OpenSky.Verdict.Buried.class,
                OpenSky.judge(buried, 62));
        assertTrue(verdict.coverSolid());
        assertFalse(verdict.coverFluid());
    }

    @Test
    void 顶盖是液体时上掘会灌进来() {
        ColumnSample underWater = new ColumnSample(true, "minecraft:river", 62, true, false,
                "minecraft:water");
        OpenSky.Verdict.Buried verdict = assertInstanceOf(OpenSky.Verdict.Buried.class,
                OpenSky.judge(underWater, 20));
        assertTrue(verdict.coverFluid());
        assertFalse(verdict.coverSolid(), "液体不是实体方块，上掘立不住");
    }

    @Test
    void 未加载的列既不判露天也不判封闭() {
        assertInstanceOf(OpenSky.Verdict.Unloaded.class, OpenSky.judge(ColumnSample.unloaded(), 64));
    }

    @Test
    void 平台复核要脚下与四邻至少三格同高支撑() {
        // 脚下踩得住、四邻三格同高：是平台。
        assertTrue(LevelGroundCheck.holds(true, true, true, true, false));
        assertTrue(LevelGroundCheck.holds(true, true, true, false, true));
        // 只有两格四邻同高：尖角不是平台。
        assertFalse(LevelGroundCheck.holds(true, true, true, false, false));
        // 脚下踩不住：悬崖边悬空的半格不算。
        assertFalse(LevelGroundCheck.holds(false, true, true, true, true));
    }
}
