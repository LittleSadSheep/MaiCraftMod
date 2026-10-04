// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 随身主背包持续接近装满时提醒腾出空间；只数空槽，不推断可堆叠余量，也不替模型挑选该丢的物品。 */
public final class InventorySpaceReminder {
    public static final String ID = "carried_inventory_nearly_full";
    private static final long FULL_TICKS = 3 * 20, MAX_OBSERVATION_GAP = 5 * 20;
    private static final int TRIGGER_FREE_SLOTS = 2, RELEASE_FREE_SLOTS = 6;
    public record Observation(long tick, int freeSlots, int totalSlots) {}
    private final ReminderBoard board;
    private long previousTick = -1, fullSince = -1;

    public InventorySpaceReminder(ReminderBoard board) { this.board = board; }

    /** 战斗或采矿的瞬时满仓不催促；腾出空间带滞回阈值，一两个空槽的抖动不反复打扰。 */
    public void observe(Observation now) {
        if (previousTick >= 0 && (now.tick() < previousTick
                || now.tick() - previousTick > MAX_OBSERVATION_GAP)) {
            clear();
            board.remove(ID, "inventory_observation_interrupted", now.tick());
        }
        previousTick = now.tick();
        if (now.freeSlots() >= RELEASE_FREE_SLOTS) {
            fullSince = -1;
            board.remove(ID, "inventory_space_recovered", now.tick());
            return;
        }
        if (now.freeSlots() > TRIGGER_FREE_SLOTS) return;
        if (fullSince < 0) fullSince = now.tick();
        if (now.tick() - fullSince < FULL_TICKS) return;
        JsonObject evidence = new JsonObject();
        evidence.addProperty("source", "main_inventory_slot_counts");
        evidence.addProperty("inventory_scope", "main_inventory_slots_excluding_armor_and_offhand");
        evidence.addProperty("free_slots", now.freeSlots());
        evidence.addProperty("total_slots", now.totalSlots());
        evidence.addProperty("full_observed_ticks", now.tick() - fullSince);
        evidence.addProperty("trigger_free_slots", TRIGGER_FREE_SLOTS);
        evidence.addProperty("release_free_slots", RELEASE_FREE_SLOTS);
        // 空槽是“装不下”的直接下限证据；同类物品还能堆多少没有折算，不能由此断言完全失去拾取能力。
        evidence.addProperty("stacking_headroom_checked", false);
        JsonArray suggestions = new JsonArray();
        suggestions.add("可考虑丢弃低价值物品（maicraft:drop_items）或存入容器（maicraft:manage_container），合成箱子也能扩大随身容量。");
        suggestions.add("丢弃前先确认物品用途；提醒不会自动丢弃或搬运任何物品，取舍由当前任务决定。");
        board.update(ID, "随身背包几乎装满，可能捡不到新的战利品或材料。可考虑清理低价值物品或把存货转入容器。",
                evidence, suggestions, now.tick());
    }

    /** 换身体或退出观察时重新计数，不把上一具身体的满仓判断交给新背包。 */
    public void clear() { previousTick = fullSince = -1; }
}
