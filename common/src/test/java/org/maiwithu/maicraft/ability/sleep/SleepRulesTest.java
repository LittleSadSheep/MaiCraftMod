// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.world.WorldTime;

/**
 * 睡觉时间规则的离线场景：晴天中午要等一下才到可睡窗口，傍晚已经能睡，
 * 固定时间的维度不等待；等多少刻的探查在游戏接口层的世界时间规则里，这里测它的答案形状。
 */
class SleepRulesTest {

    private static final boolean NOT_FIXED = false;
    private static final float CLEAR = 0.0F;

    @Test
    void noonWaitsUntilTheVanillaWindowOpens() {
        // 晴天中午（6000 刻）到窗口起点还有半天不到：给出正的、不超过半天的等待。
        long ticks = WorldTime.ticksUntilSleepable(6_000, NOT_FIXED, CLEAR, CLEAR);
        assertTrue(ticks > 0 && ticks <= 12_000, "等到 " + ticks + " 刻");
    }

    @Test
    void duskIsAlreadyInsideTheWindow() {
        // 13000 刻已过窗口起点：现在就能睡，等 0 刻。
        assertEquals(0, WorldTime.ticksUntilSleepable(13_000, NOT_FIXED, CLEAR, CLEAR));
    }

    @Test
    void dawnJustMissesTheWindowEnd() {
        // 23500 刻刚过窗口终点：要等下一天的开窗，等待不超过一整天。
        long ticks = WorldTime.ticksUntilSleepable(23_500, NOT_FIXED, CLEAR, CLEAR);
        assertTrue(ticks > 0 && ticks <= 24_000, "刚过窗口终点要等下一窗：" + ticks);
    }

    @Test
    void fixedTimeDimensionsNeverReportAWait() {
        // 固定时间的维度（下界、末地）时间规则上"随时可睡"，但床会爆炸——那一关归维度检查，这里不等待。
        assertEquals(0, WorldTime.ticksUntilSleepable(6_000, true, CLEAR, CLEAR));
    }
}
