// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 以个人统计回放昼夜切换和休息，同时保留其他提醒，避免睡眠建议覆盖角色的其他生存问题。 */
public final class SleepReminderTest {
    public static void main(String[] args) {
        var events = new ArrayList<JsonObject>();
        var board = new ReminderBoard(events::add);
        var rule = new SleepReminder(board);
        rule.observe(at(0, 0, 100 * 24_000L + 18_000, true));
        check(board.snapshot().isEmpty(), "世界已过百天也不代表该角色百天没睡");
        rule.observe(at(1, 71_999, 18_000, true));
        check(board.snapshot().isEmpty(), "个人未休息时间还未达到三天");
        rule.observe(at(2, 72_000, 6000, true));
        check(value(board).get("message").getAsString().startsWith("建议提前备床")
                && value(board).get("message").getAsString().contains("入夜后"), "白天提醒提前备床并为夜晚准备");
        rule.observe(at(3, 72_001, 18_000, true));
        check(value(board).get("message").getAsString().startsWith("你已经长时间没有睡觉")
                && value(board).get("message").getAsString().contains("幻翼")
                && value(board).getAsJsonObject("evidence").get("night").getAsBoolean(), "夜晚强调长期未睡及幻翼威胁");
        check(events.size() == 1, "文案随现场更新，注意事件仍遵守节流");
        rule.observe(at(4, 72_002, 18_000, false));
        check(value(board).get("message").getAsString().contains("当前维度不支持正常用床")
                && !value(board).getAsJsonObject("evidence").get("phantom_spawn_guaranteed").getAsBoolean(),
                "危险维度不建议直接上床，风险也不冒充已生成幻翼");
        // 同一字段允许多条规则；撤下睡眠只动自己的 ID，不能清空整个提醒板。
        for (String id : new String[]{LowLightCombatReminder.ID, FoodSupplyReminder.ID, CombatEquipmentReminder.ID})
            board.update(id, "另一条仍有效的提醒", new JsonObject(), new JsonArray(), 4);
        check(board.snapshot().size() == 4, "四条提醒同时存在");
        rule.observe(at(5, 0, 6000, true));
        check(board.snapshot().size() == 3 && board.snapshot().toString().contains(FoodSupplyReminder.ID)
                && board.snapshot().toString().contains(CombatEquipmentReminder.ID)
                && board.snapshot().toString().contains(LowLightCombatReminder.ID), "休息后另外三条提醒仍完整保留");
        rule.observe(at(6, 96_000, 6000, true));
        rule.unavailable(7, "rest_stat_unavailable");
        check(board.snapshot().size() == 3, "未知统计只撤下自己，不冒称已睡觉");
        System.out.println("SleepReminderTest: passed");
    }

    private static SleepReminder.Observation at(long tick, int rest, long dayTime, boolean bedsWork) {
        return new SleepReminder.Observation(tick, rest, tick, dayTime, bedsWork, bedsWork);
    }
    private static JsonObject value(ReminderBoard board) { return board.snapshot().get(0).getAsJsonObject(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
