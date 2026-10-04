// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 用个人未休息时长提醒睡觉；白天准备床，夜晚提示长期不睡的风险，不把世界日期当作个人睡眠史。 */
public final class SleepReminder {
    public static final String ID = "prolonged_sleep_deprivation";
    public static final long THRESHOLD_TICKS = 3 * 24_000L;
    private final ReminderBoard board;
    public record Observation(long tick, int ticksSinceRest, long statsObservedAt,
                              long dayTime, boolean bedsWork, boolean naturalDimension) {}

    public SleepReminder(ReminderBoard board) { this.board = board; }

    /** 只有已确认的个人统计达到三天才提醒；日期跳变、别人睡过一晚或刚接入世界都不能替代这一依据。 */
    public void observe(Observation now) {
        if (now.ticksSinceRest() < THRESHOLD_TICKS) {
            unavailable(now.tick(), "rest_threshold_not_reached");
            return;
        }
        boolean night = WorldTimeSemantics.phase(now.dayTime()) == WorldTimeSemantics.Phase.NIGHT;
        boolean sleepHere = now.bedsWork() && now.naturalDimension();
        String message = night
                ? "你已经长时间没有睡觉，夜间可能遭遇幻翼和其他怪物攻击。"
                : "建议提前备床：你已经多个游戏日没有休息，入夜后可能遭遇幻翼和其他怪物攻击。";
        message += sleepHere
                ? night ? "可考虑使用睡觉功能（maicraft:sleep），在安全的床上休息。"
                        : "可提前准备安全的床，等适合睡眠时使用睡觉功能（maicraft:sleep）休息。"
                : "当前维度不支持正常用床睡眠，可先为返回适合睡眠的维度准备床。";
        JsonObject evidence = new JsonObject();
        evidence.addProperty("source", "native_time_since_rest_statistic");
        evidence.addProperty("time_since_rest_ticks", now.ticksSinceRest());
        evidence.addProperty("whole_days_since_rest", now.ticksSinceRest() / 24_000L);
        evidence.addProperty("threshold_ticks", THRESHOLD_TICKS);
        evidence.addProperty("stats_observed_at_tick", now.statsObservedAt());
        evidence.addProperty("stats_age_ticks", now.tick() - now.statsObservedAt());
        evidence.addProperty("time_phase", WorldTimeSemantics.phase(now.dayTime()).id());
        evidence.addProperty("night", night);
        evidence.addProperty("beds_work_in_dimension", now.bedsWork());
        evidence.addProperty("natural_dimension", now.naturalDimension());
        // 幻翼还受服务端规则、生成位置和随机判定影响；未休息时长不是怪物已经生成或即将攻击的证明。
        evidence.addProperty("phantom_spawn_guaranteed", false);
        evidence.addProperty("insomnia_game_rule_verified", false);
        JsonArray suggestions = new JsonArray();
        suggestions.add(night ? "可考虑先处理身边威胁，再到安全的床上休息；是否能入睡以原生回执为准。"
                : "建议白天提前准备床和安全休息地点，避免等到夜间被怪物追击时才临时找床。");
        if (!sleepHere) suggestions.add("先返回支持正常用床睡眠的维度；当前提醒不会自动使用床或移动角色。");
        board.update(ID, message, evidence, suggestions, now.tick());
    }

    /** 入睡、统计失效或休息计数下降时，只撤下睡眠提醒，不能挤掉缺粮、装备或补光建议。 */
    public void unavailable(long tick, String reason) { board.remove(ID, reason, tick); }
}
