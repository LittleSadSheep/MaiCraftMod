// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 饥饿这项生存需求：饿了趁干活的空当掏点吃的；饿到不能疾跑要尽快吃；饿到掉血必须立刻吃。
 *
 * <p>三档阈值（原版事实）：饱食度满 20；6 及以下不能疾跑；0 时身体开始扣血。
 * 临时任务组合进食的流程——本需求只负责"什么时候吃、弄不到怎么办"，不重写进食。
 *
 * <p>弄不到怎么办：弄吃的问遍各处都没有，这一回饿先歇一分钟不插（身上有了吃的、处境更急了照插），
 * 没吃上的事这一回只跟 LLM 说一次；不饿了这一回就算过去（见 {@link HungerBout}）。
 */
public final class HungerNeed implements SurvivalNeed {

    /** 饱食度到这条线及以下就不能疾跑。 */
    public static final int SPRINT_FLOOR = 6;

    /** 原版自然回血要的饱食度：到这一线以上才慢慢回血。 */
    public static final int NATURAL_REGEN_FOOD = 18;

    /**
     * 饥饿处境：饱食度、是否已在掉血、身上最小的一件普通食物（见 {@link FoodPicker#plain}）补多少饱食度。
     *
     * @param smallestPlainNutrition 身上普通食物里补得最少的那件的营养值；身上没有普通食物时为 0
     */
    public record Facts(int food, boolean losingHealth, int smallestPlainNutrition, boolean injured,
                        boolean badlyHurt, int bestJunkNutrition) {

        public Facts(int food, boolean losingHealth, int smallestPlainNutrition) {
            this(food, losingHealth, smallestPlainNutrition, false);
        }

        public Facts(int food, boolean losingHealth, int smallestPlainNutrition, boolean injured) {
            this(food, losingHealth, smallestPlainNutrition, injured, false, 0);
        }

        /**
         * 为回血吃一口：伤得重（血不到一半）、饱食度不到回血线、身上没有普通食物。腐肉这类垃圾食物吃一口
         * 就过回血线就吃它，不然去弄点吃的——人残血时不会揣着腐肉干等。
         */
        public boolean eatsToHeal() {
            return badlyHurt && food < NATURAL_REGEN_FOOD && !carryingEdible();
        }

        /** 回血时吃垃圾食物值不值：吃一口就能过回血线。 */
        public boolean junkReachesRegen() {
            return bestJunkNutrition > 0 && food + bestJunkNutrition >= NATURAL_REGEN_FOOD;
        }

        /** 身上有没有饿了就能顺手吃的普通食物。 */
        public boolean carryingEdible() {
            return smallestPlainNutrition > 0;
        }
    }

    /** 饥饿的三档判断，纯函数；不到档位返回 null。 */
    public static Urgency assess(Facts facts) {
        if (facts.food() <= 0 && facts.losingHealth()) {
            return Urgency.NOW;
        }
        if (facts.food() <= SPRINT_FLOOR) {
            return Urgency.SOON;
        }
        // 趁空当吃：身上最小的一件普通食物能整份补进去（不溢出）才吃，不在饱食度刚掉一格时就停下手上的活去吃。
        // 受了伤、饱食度又不到原版自然回血线（18）：溢出一点也吃，血回得来比省一口吃的要紧
        // （实机 13 点血、饱食度 17，揣着面包一直不回血）。
        if (facts.carryingEdible() && (facts.food() + facts.smallestPlainNutrition() <= 20
                || facts.injured() && facts.food() < NATURAL_REGEN_FOOD)) {
            return Urgency.LATER;
        }
        // 伤得重、饱食度不到回血线、身上没普通食物：一直不回血。趁空当吃一口腐肉过回血线，或去弄点吃的。
        // 实机 10-14 两点多血、饱食度 14、身上六块腐肉，残血站了几分钟不回血。
        if (facts.eatsToHeal()) {
            return Urgency.LATER;
        }
        return null;
    }

    /** 读饥饿处境的接缝：生产实现逐刻读角色，测试给固定值。 */
    @FunctionalInterface
    public interface ReadsFacts {
        Facts read(TickContext context);
    }

    private final ReadsFacts reader;
    private final EatSoonTask.FoodMoves moves;
    private final TaskEventSink events;
    /** 眼下这一回饿：说没说过、歇不歇着；不饿了就清掉。 */
    private final HungerBout bout = new HungerBout();

    /** @param moves 吃与弄吃的怎么落地；生产组合进食与采集的行为模型，测试换替身。 */
    public HungerNeed(ReadsFacts reader, EatSoonTask.FoodMoves moves,
                      TaskEventSink events) {
        this.reader = reader;
        this.moves = moves;
        this.events = events;
    }

    @Override public String name() { return "饥饿"; }

    @Override
    public Urgency urgency(TickContext context) {
        Facts facts = reader.read(context);
        if (facts == null) return null;
        Urgency urgency = assess(facts);
        if (urgency == null) {
            // 不饿了：这一回过去了，下回饿了重新跟 LLM 说、重新找吃的。
            bout.over();
            return null;
        }
        // 刚问遍各处都没弄到吃的：歇着的这一分钟里不插，手上的活照常干；身上有了这会儿能吃的
        // （捡到、别人给的）或处境更急了，马上照常插进来。
        if (bout.resting(context.gameTick(), urgency) && !moves.carriesBite(facts)) return null;
        return urgency;
    }

    @Override
    public Task createTask(TickContext context) {
        // 插进来的这一刻读一次处境，吃饭任务一开始就按它挑吃什么；这一回饿的记账跟着交过去。
        return new EatSoonTask(moves, reader, events, reader.read(context), bout);
    }
}
