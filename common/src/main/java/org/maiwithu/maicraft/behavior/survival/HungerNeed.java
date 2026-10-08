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
 * <p>三档阈值（原版事实）：饱食度满 20；低于 6 不能疾跑也不回血；0 时身体开始扣血。
 * 临时任务组合进食的流程——本需求只负责"什么时候吃、弄不到怎么办"，不重写进食。
 */
public final class HungerNeed implements SurvivalNeed {

    /** 低于这条线不能疾跑、也不回血。 */
    public static final int SPRINT_FLOOR = 6;

    /** 饥饿处境：饱食度、是否已在掉血、身上有没有能直接吃的。 */
    public record Facts(int food, boolean losingHealth, boolean carryingEdible) {}

    /** 饥饿的三档判断，纯函数；不到档位返回 null。 */
    public static Urgency assess(Facts facts) {
        if (facts.food() <= 0 && facts.losingHealth()) {
            return Urgency.NOW;
        }
        if (facts.food() <= SPRINT_FLOOR) {
            return Urgency.SOON;
        }
        if (facts.food() < 20 && facts.carryingEdible()) {
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
        return new EatSoonTask(moves, reader, events);
    }
}
