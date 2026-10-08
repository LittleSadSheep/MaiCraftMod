// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.wait;

import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.game.world.WorldTime;

import java.util.Objects;
import java.util.function.Function;

/**
 * 等待任务：站在原地盯着条件，等到了说一声；只观察，不替角色达成条件。
 *
 * <p>计时从任务第一次被推进那刻起，按世界时刻算：被生存需求打断的这段时间也算已经过去，
 * 不重置也不倒扣。条件不成立就继续等，没有超时——要停下由 LLM 取消任务。
 */
final class WaitTask extends PhasedTask<WaitTask.Phase> {

    /** 等待只有一个阶段：逐刻检查。 */
    enum Phase { WATCH }

    /** 等待本身就是"在等"，不算原地打转：每刻记一次进展。 */
    private static final long NEVER_STUCK = Long.MAX_VALUE;

    /** 每秒 20 个游戏刻，结果里的经过秒数按它折算。 */
    private static final long TICKS_PER_SECOND = 20;

    private final WaitInput input;
    private final Function<TickContext, WaitWorld> worlds;
    /** 第一次被推进时的世界时刻；用于承认暂停期间已经过去的时间。 */
    private Long startedAt;

    WaitTask(WaitInput input, Function<TickContext, WaitWorld> worlds) {
        super("等待", Phase.WATCH, new ProgressTracker(1, NEVER_STUCK));
        this.input = Objects.requireNonNull(input, "input");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
    }

    @Override protected Action enter(Phase phase) { return null; }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        if (startedAt == null) {
            // 从第一次被推进那刻起算；之后普通暂停承认已过去的时间，不重新起算。
            startedAt = context.gameTick();
        }
        long elapsedTicks = context.gameTick() - startedAt;
        recordProgress("已经等了 " + elapsedTicks / TICKS_PER_SECOND + " 秒");
        if (elapsedTicks < input.afterSeconds() * TICKS_PER_SECOND) {
            return Next.stay();
        }
        WaitWorld facts = worlds.apply(context);
        if (facts == null || !conditionMet(facts)) {
            return Next.stay();
        }
        long waitedSeconds = elapsedTicks / TICKS_PER_SECOND;
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        "等到了：" + waitedSeconds + " 秒后，" + input.describe())
                .details(new WaitDetails(waitedSeconds, conditionName())).build());
    }

    private boolean conditionMet(WaitWorld facts) {
        return switch (input.condition()) {
            case ELAPSED -> true;
            case DAY -> WorldTime.phase(facts.dayTime()) != WorldTime.Phase.NIGHT;
            case NIGHT -> WorldTime.phase(facts.dayTime()) == WorldTime.Phase.NIGHT;
            case HEALTH_FULL -> facts.health() >= facts.maxHealth() - 0.01;
            case NOT_HUNGRY -> facts.food() >= WaitFor.NATURAL_REGEN_FOOD;
        };
    }

    private String conditionName() {
        return switch (input.condition()) {
            case ELAPSED -> "elapsed";
            case DAY -> "day";
            case NIGHT -> "night";
            case HEALTH_FULL -> "health_full";
            case NOT_HUNGRY -> "not_hungry";
        };
    }

    /** 等待任务的结果细节：实际经过的秒数与成立的是哪个条件。 */
    record WaitDetails(long waitedSeconds, String conditionMet) implements ResultDetails {}
}
