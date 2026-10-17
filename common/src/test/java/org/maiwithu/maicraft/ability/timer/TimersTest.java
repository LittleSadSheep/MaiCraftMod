// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.timer;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.event.TaskEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 计时表：按游戏时间到点发事件、先到先发、挂满拒、取消与换世界清空。 */
class TimersTest {

    /** 记下发出的事件：种类与消息。 */
    private final List<String> published = new ArrayList<>();
    private final Timers timers = new Timers((kind, message) -> {
        assertEquals(TaskEvent.Kind.TIMER_DUE, kind);
        published.add(message);
    });

    @Test
    void firesOnceWhenTheGameClockReachesTheDueTick() {
        // 第 1000 刻设"30 秒后看炉子"：第 1599 刻还不到，第 1600 刻正好到点，发一条后不再重复。
        timers.set(30, "看炉子烧好没", 1000);
        timers.fireDue(1599, "白天");
        assertTrue(published.isEmpty(), "没到点不该发");
        timers.fireDue(1600, "白天");
        timers.fireDue(1700, "白天");
        assertEquals(List.of("计时 1 到点：看炉子烧好没（30 秒前设的；此刻白天）"), published);
        assertTrue(timers.pending().isEmpty(), "发过的拿掉");
    }

    @Test
    void dueTimersFireInDueOrder() {
        // 后设的先到点：同一刻都到了时按到点先后发。
        timers.set(60, "收作物", 0);
        timers.set(10, "看熔炉", 0);
        timers.fireDue(2000, "夜晚，距天亮约 3 分钟");
        assertEquals(2, published.size());
        assertTrue(published.get(0).startsWith("计时 2 到点：看熔炉"), published.get(0));
        assertTrue(published.get(1).startsWith("计时 1 到点：收作物"), published.get(1));
        assertTrue(published.get(1).contains("此刻夜晚，距天亮约 3 分钟"), "带到点时的游戏时间");
    }

    @Test
    void aClockThatJumpsBackwardFiresNothing() {
        // 刚换维度时新世界的钟还没同步过来，游戏时间短暂读成 0：什么都不到点。
        timers.set(5, "回去看看", 5000);
        timers.fireDue(0, "白天");
        assertTrue(published.isEmpty());
        assertEquals(1, timers.pending().size());
    }

    @Test
    void refusesTheNinthAndCancelFreesASlot() {
        for (int i = 0; i < Timers.MAX_PENDING; i++) {
            assertTrue(timers.set(10 + i, "第 " + i + " 件", 0).isPresent());
        }
        assertTrue(timers.set(5, "多出来的", 0).isEmpty(), "挂满了要拒");
        assertTrue(timers.cancel(3).isPresent());
        assertTrue(timers.cancel(3).isEmpty(), "取消过的再取消找不到");
        assertTrue(timers.set(5, "腾出位置后再设", 0).isPresent());
    }

    @Test
    void leavingTheWorldDropsPendingButIdsKeepGrowing() {
        timers.set(10, "旧世界的事", 0);
        timers.clear();
        timers.fireDue(10_000, "白天");
        assertTrue(published.isEmpty(), "换过世界不该再叫");
        // 编号只增不减：新世界的计时不会和旧编号撞上，LLM 手里的旧编号取消不到新计时。
        assertEquals(2, timers.set(10, "新世界的事", 0).orElseThrow().id());
    }

    @Test
    void remainingSecondsRoundsUpAndNeverGoesNegative() {
        Timers.Timer timer = timers.set(10, "看看", 0).orElseThrow();
        assertEquals(10, timer.remainingSeconds(0));
        assertEquals(1, timer.remainingSeconds(199));
        assertEquals(0, timer.remainingSeconds(500));
    }
}
