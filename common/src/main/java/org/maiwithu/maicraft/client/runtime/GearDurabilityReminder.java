// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 在用工具或所穿盔甲耐久将尽时提醒备料；不断言确切的断裂时刻，也不清点背包里的备件。 */
public final class GearDurabilityReminder {
    public static final String ID = "equipped_gear_near_breakage";
    private static final double LOW_FRACTION = 0.15;
    private static final int ABSOLUTE_FLOOR = 10;
    private static final long MAX_OBSERVATION_GAP = 5 * 20;
    /** slot 是观察槽位名（mainhand/head/chest/legs/feet），remaining/max 是当前剩余与满值耐久。 */
    public record SlotFact(String slot, String itemId, int remaining, int max) {}
    public record Observation(long tick, List<SlotFact> slots) {}
    private final ReminderBoard board;
    private long previousTick = -1;

    public GearDurabilityReminder(ReminderBoard board) { this.board = board; }

    /** 耐久只降不升，任一观察槽位降到安全线以下即可提醒；全部回到安全区后才撤下。 */
    public void observe(Observation now) {
        if (previousTick >= 0 && (now.tick() < previousTick
                || now.tick() - previousTick > MAX_OBSERVATION_GAP)) {
            clear();
            board.remove(ID, "durability_observation_interrupted", now.tick());
        }
        previousTick = now.tick();
        boolean anyWorn = now.slots().stream()
                .anyMatch(slot -> slot.remaining() <= Math.max(ABSOLUTE_FLOOR, slot.max() * LOW_FRACTION));
        if (!anyWorn) {
            board.remove(ID, "gear_durability_recovered_or_replaced", now.tick());
            return;
        }
        JsonArray slots = new JsonArray();
        for (SlotFact slot : now.slots()) {
            JsonObject item = new JsonObject();
            item.addProperty("slot", slot.slot());
            item.addProperty("item_id", slot.itemId());
            item.addProperty("remaining_durability", slot.remaining());
            item.addProperty("max_durability", slot.max());
            slots.add(item);
        }
        JsonObject evidence = new JsonObject();
        evidence.addProperty("source", "mainhand_and_armor_durability_observations");
        evidence.addProperty("low_durability_fraction", LOW_FRACTION);
        evidence.addProperty("absolute_durability_floor", ABSOLUTE_FLOOR);
        evidence.add("observed_slots", slots);
        // 耐久附魔让剩余刻数不可预测；背包备件也未盘点，提醒只陈述在用槽位的现状。
        evidence.addProperty("exact_break_tick_known", false);
        evidence.addProperty("backup_tools_counted", false);
        JsonArray suggestions = new JsonArray();
        suggestions.add("可考虑提前准备替换工具或用砧子修理；备件在背包里也不会被自动更换，是否换装由当前任务决定。");
        board.update(ID, "正在使用的装备耐久即将耗尽，作业或战斗中可能突然损坏。可考虑提前准备替换工具或安排修理。",
                evidence, suggestions, now.tick());
    }

    /** 换身体或退出观察时重建耐久基准，不把上一具身体的槽位状态带进下一具。 */
    public void clear() { previousTick = -1; }
}
