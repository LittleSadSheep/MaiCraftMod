// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 手里的工具、身上的盔甲耐久快用完时提醒备替换；不断言哪一刻坏，也不清点背包里的备件。 */
final class WornGearReminder {

    static final String ID = "equipped_gear_near_breakage";
    /** 剩余耐久低于满值的这个比例，或低于绝对下限，就算快坏了。 */
    private static final double LOW_FRACTION = 0.15;
    private static final int ABSOLUTE_FLOOR = 10;
    private static final long MAX_OBSERVATION_GAP = 5 * 20;

    /** 一个在用槽位的耐久：槽位名（mainhand/head/chest/legs/feet）、物品、剩余、满值。 */
    record SlotFact(String slot, String itemId, int remaining, int max) {}

    record Observation(long tick, List<SlotFact> slots) {}

    private final ReminderBoard board;
    private long previousTick = -1;

    WornGearReminder(ReminderBoard board) {
        this.board = board;
    }

    /** 任一在用槽位降到安全线以下就提醒；全部回到安全线以上（修好、换掉）才撤下。 */
    void observe(Observation now) {
        if (previousTick >= 0 && (now.tick() < previousTick || now.tick() - previousTick > MAX_OBSERVATION_GAP)) {
            clear();
            board.remove(ID, "耐久的观察中断了", now.tick());
        }
        previousTick = now.tick();
        List<Map<String, Object>> worn = new ArrayList<>();
        for (SlotFact slot : now.slots()) {
            if (slot.remaining() > Math.max(ABSOLUTE_FLOOR, slot.max() * LOW_FRACTION)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slot", slot.slot());
            item.put("item_id", slot.itemId());
            item.put("remaining_durability", slot.remaining());
            item.put("max_durability", slot.max());
            worn.add(item);
        }
        if (worn.isEmpty()) {
            board.remove(ID, "在用的装备耐久回到了安全线以上，或换掉了", now.tick());
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("worn_slots", worn);
        evidence.put("low_durability_fraction", LOW_FRACTION);
        evidence.put("absolute_durability_floor", ABSOLUTE_FLOOR);
        // 耐久附魔让剩几下不好说；背包里的备件也没数。
        evidence.put("backup_tools_counted", false);
        board.update(ID, "正在用的装备耐久快用完了，干活或打架时可能突然坏掉。可以提前准备替换的，或者安排修理。",
                evidence, List.of(
                        "背包里有备件也不会自动换上，换不换由手上的事决定（equip 换装、obtain 做一把新的）。"),
                now.tick());
    }

    /** 换身体时重建基准。 */
    void clear() {
        previousTick = -1;
    }
}
