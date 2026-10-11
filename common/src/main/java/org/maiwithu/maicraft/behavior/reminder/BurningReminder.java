// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 着火或泡在岩浆里持续一秒才提醒，火焰擦过不催；火一灭就撤，不冒认火从哪来、每刻掉多少血。 */
final class BurningReminder {

    static final String ID = "exposed_to_fire_or_lava";
    private static final long EXPOSURE_TICKS = 20;
    private static final long MAX_OBSERVATION_GAP = 5 * 20;

    /** 一刻的观察：身上着没着火、是不是泡在岩浆里。 */
    record Observation(long tick, boolean onFire, boolean inLava) {}

    private final ReminderBoard board;
    private long previousTick = -1;
    private long exposureSince = -1;

    BurningReminder(ReminderBoard board) {
        this.board = board;
    }

    /** 每次暴露重新累计时长；有防火效果时实际掉不掉血不由这里判断。 */
    void observe(Observation now) {
        if (previousTick >= 0 && (now.tick() < previousTick || now.tick() - previousTick > MAX_OBSERVATION_GAP)) {
            clear();
            board.remove(ID, "着火的观察中断了", now.tick());
        }
        previousTick = now.tick();
        if (!now.onFire() && !now.inLava()) {
            exposureSince = -1;
            board.remove(ID, "火灭了", now.tick());
            return;
        }
        if (exposureSince < 0) exposureSince = now.tick();
        if (now.tick() - exposureSince < EXPOSURE_TICKS) return;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("on_fire", now.onFire());
        evidence.put("in_lava", now.inLava());
        evidence.put("exposure_observed_ticks", now.tick() - exposureSince);
        // 火可能是自己点的、地上的火或岩浆；防火效果下实际伤害也不一样，这里只说身体此刻的状态。
        evidence.put("fire_source_known", false);
        board.update(ID, "正在着火或泡在岩浆里，可能一直在掉血。建议马上离开火源，或跳进水里灭火。",
                evidence, List.of(
                        "提醒不会自己灭火或移动角色；离开火源后再看身体状态，决定还要不要继续手上的事。",
                        "去下界作业前可以准备防火药水。"), now.tick());
    }

    /** 换身体或退出观察时重新累计，不把上一具身体的火焰计时带过来。 */
    void clear() {
        previousTick = exposureSince = -1;
    }
}
