// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 用可控游戏时间回放黑暗工位、白天地面和离场，不借助真实怪物持续伤害玩家。 */
public final class LowLightCombatReminderTest {
    public static void main(String[] args) {
        var events = new ArrayList<JsonObject>();
        var board = new ReminderBoard(events::add);
        var rule = new LowLightCombatReminder(board);
        rule.observe(at(0, 0, 0, "overworld"), null);
        hit(rule, 1); hit(rule, 201);
        check(board.snapshot().isEmpty(), "昏暗和两次攻击都不足以推断持续遇袭");
        hit(rule, 401);
        JsonObject evidence = board.snapshot().get(0).getAsJsonObject().getAsJsonObject("evidence");
        check(evidence.get("hit_count").getAsInt() == 3 && evidence.get("sky_light").getAsInt() == 15
                && !evidence.get("spawn_source_verified").getAsBoolean(), "夜间有效低光触发，但不能声称发现刷怪位置");
        // 玩家继续挨打时更新总数；窗口内同一怪物与不同怪物的攻击都累计，不能靠换物种重置风险。
        rule.observe(at(402, 0, 0, "overworld"), "minecraft:skeleton");
        check(events.size() == 1 && board.snapshot().get(0).getAsJsonObject().getAsJsonObject("evidence")
                .getAsJsonObject("attacker_type_hits").get("minecraft:skeleton").getAsInt() == 1, "连续命中保留种类且不会每刻唤醒");
        rule.observe(at(1201, 0, 0, "overworld"), null);
        check(board.snapshot().size() == 1, "六十秒窗口只移除到期的那次攻击");
        rule.observe(at(1401, 0, 0, "overworld"), null);
        check(board.snapshot().isEmpty(), "少于三次近期攻击即撤下，不永久粘住提醒");
        hit(rule, 1402); hit(rule, 1403); hit(rule, 1404);
        rule.observe(at(1405, 17, 0, "overworld"), null);
        check(board.snapshot().isEmpty(), "离开十六格附近范围后不再建议给新位置补光");
        hit(rule, 1500); hit(rule, 1501); hit(rule, 1502);
        rule.observe(at(1503, 0, 8, "overworld"), null);
        check(board.snapshot().isEmpty(), "当前光照恢复即撤下");
        rule.observe(at(1504, 0, 0, "overworld"), null);
        check(board.snapshot().isEmpty(), "亮度短暂波动不能反复复活旧命中造成刷屏");
        // 白天地面和未知照度不能解释为低光，跨维度或时间回退也必须重新积累证据。
        for (int tick = 1510; tick < 1515; tick++) rule.observe(at(tick, 0, 15, "overworld"), "minecraft:zombie");
        check(board.snapshot().isEmpty(), "方块光为零但太阳仍明亮时不提醒当前低光");
        hit(rule, 1600); hit(rule, 1601); hit(rule, 1602);
        rule.observe(at(1603, 0, -1, "overworld"), null);
        check(board.snapshot().isEmpty(), "无法取得照度不能用零代替真实现场");
        rule.observe(at(1604, 0, 0, "nether"), "minecraft:zombie");
        check(board.snapshot().isEmpty(), "换维度不继承旧攻击");
        hit(rule, 1700); hit(rule, 1701); hit(rule, 1702);
        rule.observe(at(10, 0, 0, "overworld"), null);
        check(board.snapshot().isEmpty(), "游戏时间回退使旧窗口失效");
        System.out.println("LowLightCombatReminderTest: passed");
    }

    private static void hit(LowLightCombatReminder rule, long tick) {
        rule.observe(at(tick, 0, 0, "overworld"), "minecraft:zombie");
    }

    private static LowLightCombatReminder.Observation at(long tick, int x, int localLight, String dimension) {
        return new LowLightCombatReminder.Observation(tick, dimension, new BlockPos(x, 64, 0), 0, 15, localLight);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
