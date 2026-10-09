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
 */
public final class HungerNeed implements SurvivalNeed {

    /** 饱食度到这条线及以下就不能疾跑。 */
    public static final int SPRINT_FLOOR = 6;

    /**
     * 饥饿处境：饱食度、是否已在掉血、身上最小的一件普通食物（见 {@link FoodPicker#plain}）补多少饱食度。
     *
     * @param smallestPlainNutrition 身上普通食物里补得最少的那件的营养值；身上没有普通食物时为 0
     */
    public record Facts(int food, boolean losingHealth, int smallestPlainNutrition) {

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
        if (facts.carryingEdible() && facts.food() + facts.smallestPlainNutrition() <= 20) {
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
        return facts == null ? null : assess(facts);
    }

    @Override
    public Task createTask(TickContext context) {
        // 插进来的这一刻读一次处境，吃饭任务一开始就按它挑吃什么。
        return new EatSoonTask(moves, reader, events, reader.read(context));
    }
}
