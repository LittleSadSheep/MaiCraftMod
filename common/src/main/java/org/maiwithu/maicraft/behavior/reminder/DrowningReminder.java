// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 水下憋气，氧气过半就提醒上浮；出水或氧气回升过半才撤下，贴着水面上下浮动不反复打扰。 */
final class DrowningReminder {

    static final String ID = "drowning_imminent";
    private static final long MAX_OBSERVATION_GAP = 5 * 20;

    /** 一刻的观察：头在不在水下、剩多少氧气、满氧气多少。 */
    record Observation(long tick, boolean underwater, int airSupply, int maxAirSupply) {}

    private final ReminderBoard board;
    private long previousTick = -1;

    DrowningReminder(ReminderBoard board) {
        this.board = board;
    }

    /** 氧气只减不增，低于一半就值得提醒；状态消失按氧气回升撤下，不在中途来回翻。 */
    void observe(Observation now) {
        if (previousTick >= 0 && (now.tick() < previousTick || now.tick() - previousTick > MAX_OBSERVATION_GAP)) {
            clear();
            board.remove(ID, "氧气的观察中断了", now.tick());
        }
        previousTick = now.tick();
        int half = now.maxAirSupply() / 2;
        if (!(now.underwater() && now.airSupply() <= half)) {
            board.remove(ID, now.underwater() ? "氧气回到一半以上" : "头出水了", now.tick());
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("air_supply", now.airSupply());
        evidence.put("max_air_supply", now.maxAirSupply());
        evidence.put("half_air_threshold", half);
        // 原版氧气用尽后才扣血；这里只报氧气余量，不同状态下消耗速度不同，不折算成剩几秒。
        evidence.put("drowning_damage_started", false);
        board.update(ID, "正在水下憋气，氧气已不到一半，用完就开始掉血。建议马上上浮到水面，或挖出一个临时气穴。",
                evidence, List.of(
                        "上浮的路被方块挡住时先挖开通道；提醒不会自己移动角色，脱离后再决定要不要继续水下作业。",
                        "长时间水下作业可以提前准备水肺药水。"), now.tick());
    }

    /** 换身体或退出观察时重建基准，不把上一具身体的憋气计时带过来。 */
    void clear() {
        previousTick = -1;
    }
}
