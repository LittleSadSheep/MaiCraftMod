// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.maiwithu.maicraft.game.world.WorldTime;

/**
 * 角色自己好几天没睡：按原版个人统计"离上次休息多少刻"提醒——白天提醒备床，夜里提醒长期不睡可能招来幻翼。
 * 只认服务器发来的统计；世界日期跳了、别人睡过一晚、刚进世界都不能代替这个依据。
 */
final class SleeplessReminder {

    static final String ID = "prolonged_sleep_deprivation";
    static final long THRESHOLD_TICKS = 3 * 24_000L;

    /** 一次观察：离上次休息多少刻、统计在哪一刻收到、此刻的日内时间、这个维度能不能用床、是不是正常的主世界那种维度。 */
    record Observation(long tick, int ticksSinceRest, long statsObservedAt, long dayTime,
                       boolean bedsWork, boolean naturalDimension) {}

    private final ReminderBoard board;

    SleeplessReminder(ReminderBoard board) {
        this.board = board;
    }

    void observe(Observation now) {
        if (now.ticksSinceRest() < THRESHOLD_TICKS) {
            unavailable(now.tick(), "离上次睡觉还不到三天");
            return;
        }
        boolean night = WorldTime.phase(now.dayTime()) == WorldTime.Phase.NIGHT;
        boolean sleepHere = now.bedsWork() && now.naturalDimension();
        String message = night
                ? "你已经很久没睡觉了，夜里可能招来幻翼和别的怪。"
                : "建议提前备好床：你已经好几个游戏日没休息了，入夜后可能招来幻翼和别的怪。";
        message += sleepHere
                ? (night ? "可以找张安全的床睡一觉（sleep）。" : "可以先备好床，到能睡的时候用 sleep 休息。")
                : "这个维度不能正常用床睡，先为回到能睡的维度备好床。";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("time_since_rest_ticks", now.ticksSinceRest());
        evidence.put("whole_days_since_rest", now.ticksSinceRest() / 24_000L);
        evidence.put("threshold_ticks", THRESHOLD_TICKS);
        evidence.put("stats_age_ticks", now.tick() - now.statsObservedAt());
        evidence.put("time_phase", WorldTime.phase(now.dayTime()).id());
        evidence.put("beds_work_in_dimension", now.bedsWork());
        // 幻翼刷不刷还看服务器规则、位置与随机；没睡多久不等于幻翼已经刷了。
        evidence.put("phantom_spawn_guaranteed", false);
        List<String> suggestions = new ArrayList<>();
        suggestions.add(night ? "先处理身边的威胁，再到安全的床上睡；睡不睡得着以游戏的结果为准。"
                : "白天先备好床和安全的休息地方，别等到夜里被怪追着才临时找床。");
        if (!sleepHere) suggestions.add("先回到能正常用床的维度；提醒不会自己用床或移动角色。");
        board.update(ID, message, evidence, suggestions, now.tick());
    }

    /** 入睡、统计失效时只撤下这一条，不挤掉别的提醒。 */
    void unavailable(long tick, String reason) {
        board.remove(ID, reason, tick);
    }
}
