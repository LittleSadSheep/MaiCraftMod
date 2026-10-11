// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.event.TaskEvent;

/**
 * 提醒板与几条规则：第一次出现立刻叫醒、来回变的文案不刷屏、撤下稳定了才通知；
 * 溺水、背包、没睡的阈值与滞回：快到才提醒，好透了才撤，贴着阈值来回抖不反复打扰。
 */
class ReminderBoardTest {

    private final List<String> events = new ArrayList<>();
    private long nanos;
    private final ReminderBoard board = new ReminderBoard((kind, message) -> {
        assertEquals(TaskEvent.Kind.REMINDER, kind);
        events.add(message);
    }, () -> nanos);

    private void advance(Duration duration) {
        nanos += duration.toNanos();
    }

    @Test
    void 第一次出现立刻叫醒_文案来回变不刷屏() {
        board.update("x", "甲", Map.of(), List.of(), 1);
        assertEquals(List.of("提醒：甲"), events);
        // 两秒后文案变了，但离上次通知不到三十秒：只更新板上的内容，不发。
        advance(Duration.ofSeconds(3));
        board.update("x", "乙", Map.of(), List.of(), 2);
        board.flush();
        assertEquals(1, events.size());
        assertEquals("乙", board.snapshot().getFirst().message());
        // 过了三十秒、文案也稳定了：发最新的。
        advance(Duration.ofSeconds(30));
        board.flush();
        assertEquals(List.of("提醒：甲", "提醒：乙"), events);
    }

    @Test
    void 撤下立刻从板上拿掉_重新出现不冒充第一次() {
        board.update("x", "甲", Map.of(), List.of(), 1);
        board.remove("x", "头出水了", 2);
        assertTrue(board.snapshot().isEmpty());
        // 马上又遇险：通知记录还在，不立刻再发一条。
        board.update("x", "甲", Map.of(), List.of(), 3);
        assertEquals(1, events.size());
    }

    @Test
    void 撤下的通知等冷却过了再发() {
        board.update("x", "甲", Map.of(), List.of(), 1);
        board.remove("x", "头出水了", 2);
        advance(Duration.ofSeconds(31));
        board.flush();
        assertEquals(List.of("提醒：甲", "提醒撤下：头出水了"), events);
    }

    @Test
    void 溺水_氧气过半才提醒_出水就撤() {
        DrowningReminder drowning = new DrowningReminder(board);
        drowning.observe(new DrowningReminder.Observation(1, true, 200, 300));
        assertTrue(board.snapshot().isEmpty());
        drowning.observe(new DrowningReminder.Observation(2, true, 150, 300));
        assertEquals(DrowningReminder.ID, board.snapshot().getFirst().id());
        drowning.observe(new DrowningReminder.Observation(3, false, 160, 300));
        assertTrue(board.snapshot().isEmpty());
    }

    @Test
    void 背包快满持续三秒才提醒_空出六格才撤() {
        FullInventoryReminder space = new FullInventoryReminder(board);
        space.observe(new FullInventoryReminder.Observation(0, 1, 36));
        assertTrue(board.snapshot().isEmpty());
        space.observe(new FullInventoryReminder.Observation(60, 1, 36));
        assertEquals(FullInventoryReminder.ID, board.snapshot().getFirst().id());
        // 空出四格还不撤：一两格来回抖不反复打扰。
        space.observe(new FullInventoryReminder.Observation(80, 4, 36));
        assertEquals(1, board.snapshot().size());
        space.observe(new FullInventoryReminder.Observation(100, 6, 36));
        assertTrue(board.snapshot().isEmpty());
    }

    @Test
    void 三天没睡才提醒() {
        SleeplessReminder sleepless = new SleeplessReminder(board);
        sleepless.observe(new SleeplessReminder.Observation(100, 2 * 24_000, 90, 6_000, true, true));
        assertTrue(board.snapshot().isEmpty());
        sleepless.observe(new SleeplessReminder.Observation(100, 3 * 24_000, 90, 6_000, true, true));
        Reminder reminder = board.snapshot().getFirst();
        assertEquals(3L, reminder.evidence().get("whole_days_since_rest"));
        assertTrue(reminder.message().startsWith("建议提前备好床"), reminder.message());
    }

    @Test
    void 黑暗里一分钟内被打三次才提醒_亮了就撤() {
        DarkAttacksReminder dark = new DarkAttacksReminder(board);
        var spot = new BlockPos(0, 64, 0);
        for (int i = 0; i < 2; i++) {
            dark.observe(new DarkAttacksReminder.Observation(i, "minecraft:overworld", spot, 0, 0, 3), "minecraft:zombie");
        }
        assertTrue(board.snapshot().isEmpty());
        dark.observe(new DarkAttacksReminder.Observation(3, "minecraft:overworld", spot, 0, 0, 3), "minecraft:zombie");
        assertEquals(DarkAttacksReminder.ID, board.snapshot().getFirst().id());
        dark.observe(new DarkAttacksReminder.Observation(4, "minecraft:overworld", spot, 14, 0, 14), null);
        assertTrue(board.snapshot().isEmpty());
    }
}
