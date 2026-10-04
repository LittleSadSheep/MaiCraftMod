// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 回放饥饿、腐肉应急和恢复口粮，保护短暂清包与观察中断不被解释为持续找不到食物。 */
public final class FoodSupplyReminderTest {
    public static void main(String[] args) {
        var events = new ArrayList<JsonObject>();
        var board = new ReminderBoard(events::add);
        var rule = new FoodSupplyReminder(board);
        observeUntil(rule, 0, 1180, 12, 0, 8);
        check(board.snapshot().isEmpty(), "不足一分钟的缺粮不急着催促模型另开农田目标");
        observeUntil(rule, 1200, 1200, 20, 0, 7);
        check(board.snapshot().size() == 1 && events.size() == 1, "短暂吃饱不能掩盖只有腐肉的持续口粮不足");
        JsonObject warning = board.snapshot().get(0).getAsJsonObject();
        check(warning.get("message").getAsString().contains("腐肉")
                && warning.get("message").getAsString().contains("种植")
                && !warning.getAsJsonObject("evidence").get("external_food_stocks_checked").getAsBoolean(),
                "提示种地且不冒称仓库或周围都没有食物");
        observeUntil(rule, 1220, 1500, 12, 5, 0);
        check(board.snapshot().size() == 1 && events.size() == 1, "只有一份临时口粮仍保留供给建议并节流事件");
        observeUntil(rule, 1520, 1520, 12, 20, 0);
        check(board.snapshot().isEmpty(), "普通食物储备足够后撤下提醒，即使角色尚未实际进食");
        // 刚清空背包但始终吃饱的角色，不应仅因没有食物就被推断成持续觅食失败。
        rule.clear(); board.clear(); observeUntil(rule, 2000, 4000, 20, 0, 0);
        check(board.snapshot().isEmpty(), "无饥饿或腐肉证据时保持安静");
        observeUntil(rule, 4020, 5220, 10, 0, 0);
        check(board.snapshot().size() == 1 && !board.snapshot().get(0).getAsJsonObject()
                .get("message").getAsString().contains("依赖腐肉"), "长期缺粮同样提醒，但不会编造腐肉库存");
        observeUntil(rule, 6000, 6000, 10, 0, 0);
        check(board.snapshot().isEmpty(), "观察中断不能冒充连续缺粮");
        observeUntil(rule, 0, 0, 10, 0, 0);
        check(board.snapshot().isEmpty(), "游戏时间回退重新建立观察基准");
        System.out.println("FoodSupplyReminderTest: passed");
    }

    private static void observeUntil(FoodSupplyReminder rule, long start, long end, int food, long nutrition, long flesh) {
        for (long tick = start; tick <= end; tick += 20)
            rule.observe(new FoodSupplyReminder.Observation(tick, food, nutrition > 0 ? 1 : 0, nutrition, flesh));
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
