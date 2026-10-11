// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 随身主背包一直快满时提醒腾地方；只数空格，不推算同类还能堆多少，也不替 LLM 挑该丢什么。
 * 打架、挖矿时一下子满了不催；腾出来要多空几格才撤，一两格来回抖不反复打扰。
 */
final class FullInventoryReminder {

    static final String ID = "carried_inventory_nearly_full";
    private static final long FULL_TICKS = 3 * 20;
    private static final long MAX_OBSERVATION_GAP = 5 * 20;
    /** 空格少到这么多开始算快满；回到这么多才算腾出来了。 */
    private static final int TRIGGER_FREE_SLOTS = 2;
    private static final int RELEASE_FREE_SLOTS = 6;

    record Observation(long tick, int freeSlots, int totalSlots) {}

    private final ReminderBoard board;
    private long previousTick = -1;
    private long fullSince = -1;

    FullInventoryReminder(ReminderBoard board) {
        this.board = board;
    }

    void observe(Observation now) {
        if (previousTick >= 0 && (now.tick() < previousTick || now.tick() - previousTick > MAX_OBSERVATION_GAP)) {
            clear();
            board.remove(ID, "背包的观察中断了", now.tick());
        }
        previousTick = now.tick();
        if (now.freeSlots() >= RELEASE_FREE_SLOTS) {
            fullSince = -1;
            board.remove(ID, "背包腾出地方了", now.tick());
            return;
        }
        if (now.freeSlots() > TRIGGER_FREE_SLOTS) return;
        if (fullSince < 0) fullSince = now.tick();
        if (now.tick() - fullSince < FULL_TICKS) return;
        Map<String, Object> evidence = new LinkedHashMap<>();
        // 只数主背包三十六格：盔甲与副手装不进普通战利品，混进来会掩盖真实的满。
        evidence.put("free_slots", now.freeSlots());
        evidence.put("total_slots", now.totalSlots());
        evidence.put("full_observed_ticks", now.tick() - fullSince);
        evidence.put("trigger_free_slots", TRIGGER_FREE_SLOTS);
        evidence.put("release_free_slots", RELEASE_FREE_SLOTS);
        evidence.put("stacking_headroom_checked", false);
        board.update(ID, "随身背包快装满了，可能捡不进新的战利品或材料。可以清掉不值钱的东西，或把存货存进箱子。",
                evidence, List.of(
                        "丢（drop）或存（deposit）之前先想清楚用途；提醒不会自己丢或搬任何东西，取舍由手上的事决定。"),
                now.tick());
    }

    void clear() {
        previousTick = fullSince = -1;
    }
}
