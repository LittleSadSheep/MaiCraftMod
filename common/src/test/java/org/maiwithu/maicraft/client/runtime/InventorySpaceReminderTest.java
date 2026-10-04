// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 回放背包由满到空的完整过程，保护瞬时满仓与观察中断不被解释成持续装不下。 */
public final class InventorySpaceReminderTest {
    public static void main(String[] args) {
        var events = new ArrayList<JsonObject>();
        var board = new ReminderBoard(events::add);
        var rule = new InventorySpaceReminder(board);
        for (long tick = 0; tick <= 40; tick += 20) rule.observe(new InventorySpaceReminder.Observation(tick, 1, 36));
        check(board.snapshot().isEmpty(), "不满三秒的瞬时满仓不催促清理");
        rule.observe(new InventorySpaceReminder.Observation(60, 1, 36));
        check(board.snapshot().size() == 1 && events.size() == 1, "持续满仓三秒后提醒");
        var evidence = board.snapshot().get(0).getAsJsonObject().getAsJsonObject("evidence");
        check(evidence.get("free_slots").getAsInt() == 1
                && !evidence.get("stacking_headroom_checked").getAsBoolean(),
                "证据报告空槽计数，且不冒认可堆叠余量已经折算");
        rule.observe(new InventorySpaceReminder.Observation(80, 4, 36));
        check(board.snapshot().size() == 1, "空槽处于中间区时保持提醒");
        rule.observe(new InventorySpaceReminder.Observation(100, 6, 36));
        check(board.snapshot().isEmpty(), "腾出空间越过滞回线后撤下");
        rule.observe(new InventorySpaceReminder.Observation(120, 5, 36));
        check(board.snapshot().isEmpty(), "中间区不重新触发");
        for (long tick = 140; tick <= 200; tick += 20)
            rule.observe(new InventorySpaceReminder.Observation(tick, 2, 36));
        check(board.snapshot().size() == 1, "再次持续满仓重新提醒");
        rule.observe(new InventorySpaceReminder.Observation(400, 2, 36));
        check(board.snapshot().isEmpty(), "观察中断重新累计，不把断开的窗口算进持续满仓");
        for (long tick = 420; tick <= 480; tick += 20)
            rule.observe(new InventorySpaceReminder.Observation(tick, 2, 36));
        check(board.snapshot().size() == 1, "中断后现状仍满，重新计满三秒再提醒");
        System.out.println("InventorySpaceReminderTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
