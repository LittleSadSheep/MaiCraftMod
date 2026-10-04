// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 着火或身处岩浆持续一秒才提醒，火焰掠过不催促；火灭即撤，不冒认伤害来源与每刻伤害量。 */
public final class BurningExposureReminder {
    public static final String ID = "exposed_to_fire_or_lava";
    private static final long EXPOSURE_TICKS = 20, MAX_OBSERVATION_GAP = 5 * 20;
    public record Observation(long tick, boolean onFire, boolean inLava) {}
    private final ReminderBoard board;
    private long previousTick = -1, exposureSince = -1;

    public BurningExposureReminder(ReminderBoard board) { this.board = board; }

    /** 每次暴露重新累计持续时长；火焰保护等效果下是否实际掉血不由本规则断言。 */
    public void observe(Observation now) {
        if (previousTick >= 0 && (now.tick() < previousTick
                || now.tick() - previousTick > MAX_OBSERVATION_GAP)) {
            clear();
            board.remove(ID, "burning_observation_interrupted", now.tick());
        }
        previousTick = now.tick();
        boolean exposed = now.onFire() || now.inLava();
        if (!exposed) {
            exposureSince = -1;
            board.remove(ID, "no_longer_burning", now.tick());
            return;
        }
        if (exposureSince < 0) exposureSince = now.tick();
        if (now.tick() - exposureSince < EXPOSURE_TICKS) return;
        JsonObject evidence = new JsonObject();
        evidence.addProperty("source", "on_fire_and_lava_contact_observations");
        evidence.addProperty("on_fire", now.onFire());
        evidence.addProperty("in_lava", now.inLava());
        evidence.addProperty("exposure_observed_ticks", now.tick() - exposureSince);
        evidence.addProperty("exposure_window_ticks", EXPOSURE_TICKS);
        // 着火可能来自自身点火、环境火格或岩浆；实际每刻伤害还受防火效果影响，提醒只陈述身体状态。
        evidence.addProperty("fire_source_known", false);
        evidence.addProperty("damage_per_tick_known", false);
        JsonArray suggestions = new JsonArray();
        suggestions.add("建议立即撤离火源或跳入水中灭火；下界作业前可提前准备防火药水与防火装备。");
        suggestions.add("提醒不会自动灭火或移动角色；脱离火源后再评估身体状态与继续作业的安全性。");
        board.update(ID, "正处于着火或岩浆环境中，可能持续受到伤害。建议立即撤离火源或跳入水中灭火。",
                evidence, suggestions, now.tick());
    }

    /** 换身体或退出观察时重新累计暴露时长，不把上一具身体的火焰计时带进新身体。 */
    public void clear() { previousTick = exposureSince = -1; }
}
