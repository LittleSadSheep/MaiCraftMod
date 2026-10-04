// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 回放工具与盔甲的耐久起落，保护更换、修理与观察中断不被解释成装备持续将坏。 */
public final class GearDurabilityReminderTest {
    public static void main(String[] args) {
        var events = new ArrayList<JsonObject>();
        var board = new ReminderBoard(events::add);
        var rule = new GearDurabilityReminder(board);
        rule.observe(obs(0, slot("mainhand", "minecraft:wooden_pickaxe", 4, 59)));
        check(board.snapshot().size() == 1 && events.size() == 1, "主手工具耐久跌破安全线立即提醒");
        var evidence = board.snapshot().get(0).getAsJsonObject().getAsJsonObject("evidence");
        var observed = evidence.getAsJsonArray("observed_slots");
        check(observed.size() == 1 && observed.get(0).getAsJsonObject().get("remaining_durability").getAsInt() == 4,
                "证据保留观察槽位与剩余耐久");
        check(!evidence.get("exact_break_tick_known").getAsBoolean()
                && !evidence.get("backup_tools_counted").getAsBoolean(),
                "不冒认断裂时刻，也不冒认背包备件已经盘点");
        rule.observe(obs(20, slot("mainhand", "minecraft:iron_pickaxe", 250, 250)));
        check(board.snapshot().isEmpty(), "换上满耐久工具后撤下");
        rule.observe(obs(40, slot("head", "minecraft:iron_helmet", 20, 165)));
        check(board.snapshot().size() == 1, "所穿盔甲耐久跌破百分比线同样提醒");
        rule.observe(obs(60, slot("head", "minecraft:iron_helmet", 30, 165),
                slot("mainhand", "minecraft:iron_pickaxe", 200, 250)));
        check(board.snapshot().isEmpty(), "全部观察槽位回到安全区后撤下");
        rule.observe(obs(80, slot("head", "minecraft:iron_helmet", 20, 165),
                slot("mainhand", "minecraft:iron_pickaxe", 4, 59)));
        check(board.snapshot().size() == 1, "任一槽位再次跌破即重新提醒");
        rule.observe(obs(300, slot("mainhand", "minecraft:iron_pickaxe", 200, 250)));
        check(board.snapshot().isEmpty(), "观察中断后现状安全即撤下，不保留旧警报");
        rule.observe(obs(301, slot("mainhand", "minecraft:wooden_pickaxe", 4, 59)));
        check(board.snapshot().size() == 1, "中断后立即取证，现状仍将坏则马上提醒");
        System.out.println("GearDurabilityReminderTest: passed");
    }

    private static GearDurabilityReminder.Observation obs(long tick, GearDurabilityReminder.SlotFact... slots) {
        return new GearDurabilityReminder.Observation(tick, List.of(slots));
    }
    private static GearDurabilityReminder.SlotFact slot(String name, String item, int remaining, int max) {
        return new GearDurabilityReminder.SlotFact(name, item, remaining, max);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
