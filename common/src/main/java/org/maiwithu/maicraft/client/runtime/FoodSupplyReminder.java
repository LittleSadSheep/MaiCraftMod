// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 从持续缺少随身口粮提醒建立农业供给，不把背包空误报成仓库和周围世界都没有食物。 */
public final class FoodSupplyReminder {
    public static final String ID = "persistent_food_shortage";
    private static final long SHORTAGE_TICKS = 60 * 20, HUNGER_MEMORY_TICKS = 60 * 20;
    private static final long MAX_OBSERVATION_GAP = 5 * 20, RESERVE_NUTRITION = 20;
    public record Observation(long tick, int foodLevel, long ordinaryFoodCount,
                              long ordinaryNutrition, long rottenFleshCount) {}
    private final ReminderBoard board;
    private long previousTick = -1, shortageSince = -1, lastHungryAt = -1;

    public FoodSupplyReminder(ReminderBoard board) { this.board = board; }

    /** 连续观察到口粮不足才提醒；刚吃完一口或拿到少量食物，不等于已经建立稳定补给。 */
    public void observe(Observation now) {
        if (previousTick >= 0 && (now.tick() < previousTick || now.tick() - previousTick > MAX_OBSERVATION_GAP)) {
            clear();
            board.remove(ID, "food_observation_interrupted", now.tick());
        }
        previousTick = now.tick();
        if (now.foodLevel() < 18) lastHungryAt = now.tick();
        if (now.ordinaryNutrition() >= RESERVE_NUTRITION) {
            shortageSince = -1;
            board.remove(ID, "ordinary_food_replenished", now.tick());
            return;
        }
        boolean recentHunger = lastHungryAt >= 0 && now.tick() - lastHungryAt < HUNGER_MEMORY_TICKS;
        // 没有饥饿迹象也没有腐肉时，不催促刚出生或刚清包的角色种地；有饥饿记忆则允许短暂吃饱。
        if (!recentHunger && now.rottenFleshCount() == 0) {
            shortageSince = -1;
            board.remove(ID, "food_pressure_no_longer_observed", now.tick());
            return;
        }
        if (shortageSince < 0) shortageSince = now.tick();
        if (now.tick() - shortageSince < SHORTAGE_TICKS) return;
        JsonObject evidence = new JsonObject();
        evidence.addProperty("source", "carried_food_and_hunger_observations");
        evidence.addProperty("inventory_scope", "player_inventory_and_offhand");
        evidence.addProperty("food_level", now.foodLevel());
        evidence.addProperty("ordinary_food_count", now.ordinaryFoodCount());
        evidence.addProperty("ordinary_food_nutrition", now.ordinaryNutrition());
        evidence.addProperty("reserve_nutrition_threshold", RESERVE_NUTRITION);
        evidence.addProperty("ordinary_food_policy", "OrdinaryFood.ordinary");
        evidence.addProperty("rotten_flesh_carried", now.rottenFleshCount());
        evidence.addProperty("shortage_observed_ticks", now.tick() - shortageSince);
        evidence.addProperty("minimum_shortage_ticks", SHORTAGE_TICKS);
        evidence.addProperty("external_food_stocks_checked", false);
        // 库存里的腐肉不是已进食的证明，措辞保留“可能”，不伪造吃饭或寻找食物失败的记录。
        String flesh = now.rottenFleshCount() > 0 ? "，可能正依赖腐肉充饥" : "";
        JsonArray suggestions = new JsonArray();
        suggestions.add("可考虑开垦农田、种植并补种作物，建立稳定食物来源，避免长期依赖临时觅食或腐肉。");
        suggestions.add("结合已有农田、库存和当前任务选择供给方案；急需进食时先补充可用口粮，种地不能立即解决饥饿。");
        board.update(ID, "随身普通口粮持续不足" + flesh
                + "。可考虑开垦农田、种植并补种作物，建立稳定食物来源。", evidence, suggestions, now.tick());
    }

    /** 换身体或退出生存观察时，重新建立口粮基准，不能继承上一条命的饥饿计时。 */
    public void clear() { previousTick = shortageSince = lastHungryAt = -1; }
}
