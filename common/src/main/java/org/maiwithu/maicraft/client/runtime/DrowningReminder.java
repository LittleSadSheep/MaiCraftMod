// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 水下氧气过半即提醒上浮；出水且氧气回升过半才撤下，贴水面上下浮动不反复打扰。 */
public final class DrowningReminder {
    public static final String ID = "drowning_imminent";
    private static final long MAX_OBSERVATION_GAP = 5 * 20;
    public record Observation(long tick, boolean underwater, int airSupply, int maxAirSupply) {}
    private final ReminderBoard board;
    private long previousTick = -1;

    public DrowningReminder(ReminderBoard board) { this.board = board; }

    /** 氧气单调递减，低于一半就值得提醒；状态消失按已回升的氧气幅度撤下，不在中途反复翻转。 */
    public void observe(Observation now) {
        if (previousTick >= 0 && (now.tick() < previousTick
                || now.tick() - previousTick > MAX_OBSERVATION_GAP)) {
            clear();
            board.remove(ID, "air_observation_interrupted", now.tick());
        }
        previousTick = now.tick();
        int half = now.maxAirSupply() / 2;
        boolean danger = now.underwater() && now.airSupply() <= half;
        if (!danger) {
            board.remove(ID, now.underwater() ? "air_recovered_above_half" : "head_above_water", now.tick());
            return;
        }
        JsonObject evidence = new JsonObject();
        evidence.addProperty("source", "native_air_supply_observation");
        evidence.addProperty("air_supply", now.airSupply());
        evidence.addProperty("max_air_supply", now.maxAirSupply());
        evidence.addProperty("underwater", now.underwater());
        evidence.addProperty("half_air_threshold", half);
        // 原版氧气用尽后才扣血；此处只报告氧气余量，不同状态的实际消耗速率不同，不折算剩余秒数。
        evidence.addProperty("drowning_damage_started", false);
        evidence.addProperty("remaining_seconds_estimated", false);
        JsonArray suggestions = new JsonArray();
        suggestions.add("建议立即上浮到水面或挖掘临时气穴；长时间水下作业可提前准备水肺药水。");
        suggestions.add("上浮路线被方块阻挡时可先挖通道脱离；提醒不会自动移动角色，脱离后由任务决定是否继续水下作业。");
        board.update(ID, "正在水下憋气，氧气已不足一半，氧气耗尽后将开始受到溺水伤害。建议立即上浮到水面或挖出临时气穴。",
                evidence, suggestions, now.tick());
    }

    /** 换身体或退出观察时重建氧气基准，不把上一具身体的憋气计时带进新身体。 */
    public void clear() { previousTick = -1; }
}
