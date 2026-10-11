// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 随身口粮持续不够，提醒建立稳定的食物来源（种地、养殖）；不把背包里没吃的说成仓库和周围都没有吃的。
 * 刚吃完一口、刚捡到一点吃的不等于有了稳定补给；刚出生、刚清过包又不饿的不催。
 */
final class FoodShortageReminder {

    static final String ID = "persistent_food_shortage";
    private static final long SHORTAGE_TICKS = 60 * 20;
    private static final long HUNGER_MEMORY_TICKS = 60 * 20;
    private static final long MAX_OBSERVATION_GAP = 5 * 20;
    /** 身上普通口粮的营养值合计到这么多才算够。 */
    private static final long RESERVE_NUTRITION = 20;

    /** 一次观察：饱食度、身上普通口粮几件、营养合计、腐肉几块。 */
    record Observation(long tick, int foodLevel, long ordinaryFoodCount, long ordinaryNutrition, long rottenFleshCount) {}

    private final ReminderBoard board;
    private long previousTick = -1;
    private long shortageSince = -1;
    private long lastHungryAt = -1;

    FoodShortageReminder(ReminderBoard board) {
        this.board = board;
    }

    /** 连续一分钟口粮不够才提醒。 */
    void observe(Observation now) {
        if (previousTick >= 0 && (now.tick() < previousTick || now.tick() - previousTick > MAX_OBSERVATION_GAP)) {
            clear();
            board.remove(ID, "口粮的观察中断了", now.tick());
        }
        previousTick = now.tick();
        if (now.foodLevel() < 18) lastHungryAt = now.tick();
        if (now.ordinaryNutrition() >= RESERVE_NUTRITION) {
            shortageSince = -1;
            board.remove(ID, "身上的口粮补够了", now.tick());
            return;
        }
        boolean recentHunger = lastHungryAt >= 0 && now.tick() - lastHungryAt < HUNGER_MEMORY_TICKS;
        // 没饿过也没带腐肉：不催刚出生、刚清包的角色去种地；饿过之后短暂吃饱也照样算。
        if (!recentHunger && now.rottenFleshCount() == 0) {
            shortageSince = -1;
            board.remove(ID, "没看到缺吃的迹象", now.tick());
            return;
        }
        if (shortageSince < 0) shortageSince = now.tick();
        if (now.tick() - shortageSince < SHORTAGE_TICKS) return;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("food_level", now.foodLevel());
        evidence.put("ordinary_food_count", now.ordinaryFoodCount());
        evidence.put("ordinary_food_nutrition", now.ordinaryNutrition());
        evidence.put("reserve_nutrition_threshold", RESERVE_NUTRITION);
        evidence.put("rotten_flesh_carried", now.rottenFleshCount());
        evidence.put("shortage_observed_ticks", now.tick() - shortageSince);
        // 只看了身上（主背包与副手），箱子里、地里有没有吃的没看。
        evidence.put("external_food_stocks_checked", false);
        // 带着腐肉不等于吃过腐肉：措辞保留"可能"。
        String flesh = now.rottenFleshCount() > 0 ? "，可能正靠腐肉充饥" : "";
        board.update(ID, "身上的普通口粮一直不够" + flesh + "。可以考虑开垦农田种作物、或养殖动物，建立稳定的食物来源。",
                evidence, List.of(
                        "结合已有的农田、箱子里的存货和手上的事选补给办法；急着吃的话先弄点现成的，种地解不了眼前的饿。"),
                now.tick());
    }

    /** 换身体时重新建立基准，不继承上一条命的饥饿计时。 */
    void clear() {
        previousTick = shortageSince = lastHungryAt = -1;
    }
}
