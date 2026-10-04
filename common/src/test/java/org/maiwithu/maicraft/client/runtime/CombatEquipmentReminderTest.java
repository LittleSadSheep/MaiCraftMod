// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 回放伤害包与红心同步错开、混合环境伤害和换装，避免靠逃跑次数或预测护甲强弱代替证据。 */
public final class CombatEquipmentReminderTest {
    public static void main(String[] args) {
        var events = new ArrayList<JsonObject>();
        var board = new ReminderBoard(events::add);
        var rule = new CombatEquipmentReminder(board);
        rule.observe(at(0, 20, 0, false), 0, false);
        rule.observe(at(1, 20, 0, false), 3, false);
        check(board.snapshot().isEmpty(), "只有攻击包但没有生命压力，不凭怪物出现就要求升级装备");
        rule.observe(at(2, 13, 0, false), 0, false);
        JsonObject facts = evidence(board);
        check(facts.get("hostile_hit_count").getAsInt() == 3
                && facts.get("observed_health_loss_near_attacks").getAsFloat() == 7,
                "迟到的红心变化能触发提醒，但同刻三个包只计一次七点掉血");
        rule.observe(at(3, 13, 0, false), 0, false);
        check(evidence(board).get("observed_health_loss_near_attacks").getAsFloat() == 7 && events.size() == 1,
                "反复观察不累计旧掉血，也不重复唤醒");
        rule.observe(at(400, 20, 0, false), 0, false);
        check(board.snapshot().size() == 1, "跑远或回血后仍保留为下次战斗准备装备的建议");
        rule.observe(at(401, 20, 6, false), 0, false);
        check(board.snapshot().isEmpty(), "护甲值增加后等待新战斗再评估，不沿用旧装备的受伤记录");
        rule.observe(at(402, 12, 6, false), 1, false);
        check(board.snapshot().size() == 1, "单次明显重伤也可提醒准备装备");
        rule.observe(at(403, 12, 6, true), 0, false);
        check(board.snapshot().isEmpty(), "新获得可用远程装备与弹药后重新观察");
        // 同时出现摔落等来源时，不能将这批血量变化冒充怪物造成的精确伤害。
        rule.clear(); board.clear(); rule.observe(at(500, 20, 0, false), 0, false);
        rule.observe(at(501, 8, 0, false), 3, true);
        rule.observe(at(502, 8, 0, false), 0, false);
        check(board.snapshot().isEmpty(), "混合伤害不错误归因到怪物");
        rule.clear(); rule.observe(at(600, 20, 0, false), 0, false);
        rule.observe(at(601, 20, 0, false), 3, false);
        rule.observe(at(620, 5, 0, false), 0, false);
        check(board.snapshot().isEmpty(), "关联时间窗外的掉血不能算成此前的怪物重击");
        // 小伤反复累积也会吃力；每次恢复生命再挨打，不能只检查最终一刻是否半血。
        rule.clear(); rule.observe(at(700, 20, 0, false), 0, false);
        for (int i = 0; i < 3; i++) {
            rule.observe(at(701 + i * 20, 17, 0, false), 1, false);
            rule.observe(at(702 + i * 20, 20, 0, false), 0, false);
        }
        check(evidence(board).get("observed_health_loss_near_attacks").getAsFloat() == 9,
                "三次小伤累计明显，恢复过程不会抹掉装备准备需求");
        rule.observe(at(1942, 20, 0, false), 0, false);
        check(board.snapshot().isEmpty(), "历史攻击过期后撤下建议");
        rule.observe(at(1943, 10, 0, false), 1, false);
        rule.observe(at(0, 10, 0, false), 0, false);
        check(board.snapshot().isEmpty(), "时间回退不复活旧战斗");
        System.out.println("CombatEquipmentReminderTest: passed");
    }

    private static CombatEquipmentReminder.Observation at(long tick, float health, int armor, boolean ranged) {
        JsonObject gear = new JsonObject(); gear.addProperty("ranged_weapon_carried", ranged);
        return new CombatEquipmentReminder.Observation(tick, health, 20, armor, ranged, gear);
    }
    private static JsonObject evidence(ReminderBoard board) {
        check(board.snapshot().size() == 1, "应提供当前战斗提醒");
        return board.snapshot().get(0).getAsJsonObject().getAsJsonObject("evidence");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
