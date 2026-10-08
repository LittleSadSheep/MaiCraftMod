// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可以睡的时间按原版天空变暗公式现算，相位只是状态标签，不能拿来当能不能睡的依据。 */
class WorldTimeTest {

    @Test
    void phaseSplitsTheDayIntoFourFixedParts() {
        assertEquals(WorldTime.Phase.DAWN, WorldTime.phase(0), "0～999 是凌晨");
        assertEquals(WorldTime.Phase.DAWN, WorldTime.phase(23_500), "23_000～23_999 也是凌晨");
        assertEquals(WorldTime.Phase.DAY, WorldTime.phase(1_000), "1_000 起是白天");
        assertEquals(WorldTime.Phase.DAY, WorldTime.phase(11_999));
        assertEquals(WorldTime.Phase.DUSK, WorldTime.phase(12_000), "12_000～12_999 是傍晚");
        assertEquals(WorldTime.Phase.NIGHT, WorldTime.phase(13_000), "13_000 起是夜晚");
        assertEquals(WorldTime.Phase.NIGHT, WorldTime.phase(22_999));
    }

    @Test
    void timeOfDayWrapsAcrossDaysAndNegativeTimes() {
        assertEquals(500L, WorldTime.timeOfDayOf(24_500L), "跨天只取当天走到了哪一刻");
        assertEquals(23_999L, WorldTime.timeOfDayOf(-1L), "负一时刻折回前一天的最后一段");
        assertEquals(-1L, WorldTime.dayIndexOf(-1L), "负时间按地板除计整天数");
    }

    @Test
    void sleepWindowFollowsTheVanillaSkyDarknessFormula() {
        // 纯函数吃的是维度 timeOfDay 比例（0 对应日出）。晴朗天气：正午附近天空最亮，不能睡；
        // 比例转过一半之后天空变暗值达到 4，进入可睡窗口（晴朗世界对应 day_time 12542 起）。
        assertFalse(WorldTime.canSleepAt(false, 0.0f, 0f, 0f), "天空最亮时不能睡");
        assertFalse(WorldTime.canSleepAt(false, 0.23f, 0f, 0f), "天空仍亮时不能睡");
        assertTrue(WorldTime.canSleepAt(false, 0.24f, 0f, 0f), "天空变暗到 4 后进入可睡窗口");
        assertTrue(WorldTime.canSleepAt(false, 0.75f, 0f, 0f), "深夜可以睡");
        // 雷暴压低天空变暗值，白天雷暴时就能睡：这也是原版行为，不再单列雷暴条件。
        assertTrue(WorldTime.canSleepAt(false, 0.0f, 1f, 1f), "雷暴压低天空亮度后允许尝试入睡");
        // 固定时间的维度（下界、末地）的天空没有昼夜：原版会因维度拒绝入睡，这里只回答“现在不是原版白天”。
        assertTrue(WorldTime.canSleepAt(true, 0f, 0f, 0f), "固定时间维度允许尝试，由原版判断维度安全");
        // 相位是状态标签：13_000 起就是 NIGHT，与可睡窗口各算各的。
        assertEquals(WorldTime.Phase.NIGHT, WorldTime.phase(13_000));
    }
}
