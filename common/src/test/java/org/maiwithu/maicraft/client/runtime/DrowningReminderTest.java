// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 回放水下憋气与出水恢复，保护氧气充足与短暂出水不被解释成持续溺水风险。 */
public final class DrowningReminderTest {
    public static void main(String[] args) {
        var events = new ArrayList<JsonObject>();
        var board = new ReminderBoard(events::add);
        var rule = new DrowningReminder(board);
        rule.observe(new DrowningReminder.Observation(0, true, 250, 300));
        check(board.snapshot().isEmpty(), "刚下水氧气充足不提醒");
        rule.observe(new DrowningReminder.Observation(1, true, 100, 300));
        check(board.snapshot().size() == 1 && events.size() == 1, "氧气跌破一半立即提醒");
        var evidence = board.snapshot().get(0).getAsJsonObject().getAsJsonObject("evidence");
        check(evidence.get("air_supply").getAsInt() == 100 && evidence.get("half_air_threshold").getAsInt() == 150,
                "证据报告氧气余量与阈值");
        check(!evidence.get("drowning_damage_started").getAsBoolean()
                && !evidence.get("remaining_seconds_estimated").getAsBoolean(),
                "不冒认溺水伤害已经开始，也不折算剩余秒数");
        rule.observe(new DrowningReminder.Observation(2, true, 99, 300));
        check(board.snapshot().size() == 1 && events.size() == 1, "逐刻复核刷新证据但不重复发布事件");
        // 潮涌核心或水肺效果会让水下氧气回到充足线以上，风险同样解除。
        rule.observe(new DrowningReminder.Observation(3, true, 250, 300));
        check(board.snapshot().isEmpty(), "水下氧气回到充足线以上也撤下");
        rule.observe(new DrowningReminder.Observation(4, true, 100, 300));
        check(board.snapshot().size() == 1, "氧气再次跌破重新提醒");
        rule.observe(new DrowningReminder.Observation(5, false, 100, 300));
        check(board.snapshot().isEmpty(), "出水即解除憋气风险，氧气未回满也不保留旧警报");
        rule.observe(new DrowningReminder.Observation(6, true, 100, 300));
        check(board.snapshot().size() == 1, "再次下水氧气过半重新提醒");
        rule.observe(new DrowningReminder.Observation(200, true, 100, 300));
        check(board.snapshot().size() == 1, "观察中断后重新取证，现状仍急迫则立即提醒");
        rule.clear();
        check(board.snapshot().size() == 1, "clear 只重置规则基准，不主动改写提醒板");
        System.out.println("DrowningReminderTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
