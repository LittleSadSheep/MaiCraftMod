// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.function.Supplier;

/**
 * 临时吃饭任务：身上有吃的就直接吃（组合进食的流程，不重写进食）；没有且已经饿了，
 * 做一个有预算的弄吃的（翻随身背包与已知容器、合成、采集，只弄能直接吃的），弄到就吃。
 *
 * <p>弄不到就结束并发事件把事实告诉 LLM（缺什么、试过什么），主任务不受影响——
 * 饿着肚子回去继续干活，不站着发呆，也不把主任务判失败。
 */
public final class EatSoonTask extends PhasedTask<EatSoonTask.Phase> {

    /** 吃饭任务的阶段：直接吃 → 有预算地弄 → 结束。 */
    enum Phase { EAT, FETCH }

    /** 弄吃的的默认预算：一分钟。只为弄一口饭，不为它跑半个地图。 */
    static final long DEFAULT_FETCH_BUDGET_TICKS = 20L * 60;

    /** 连续半分钟没吃上也没弄到才算卡住。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;

    /**
     * 吃与弄吃的怎么落地。生产实现组合进食流程与采集行为模型；
     * 进食那一侧还没接上时给 null——任务会如实报告"能吃的东西还没法自动吃"。
     */
    public interface FoodMoves {
        /** 吃一件随身食物；还没接入时为 null。 */
        Action eating();

        /** 弄一件能直接吃的：给预算（刻）；还没接入时为 null。 */
        Action fetching(long budgetTicks);
    }

    private final FoodMoves moves;
    private final HungerNeed.ReadsFacts reader;
    private final TaskEventSink events;
    private final long fetchBudgetTicks;

    private Action current;
    private boolean fetchedSomething;

    EatSoonTask(FoodMoves moves, HungerNeed.ReadsFacts reader, TaskEventSink events) {
        this(moves, reader, events, DEFAULT_FETCH_BUDGET_TICKS);
    }

    EatSoonTask(FoodMoves moves, HungerNeed.ReadsFacts reader, TaskEventSink events, long fetchBudgetTicks) {
        super("吃饭", Phase.EAT, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.moves = Objects.requireNonNull(moves, "moves");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.events = Objects.requireNonNull(events, "events");
        this.fetchBudgetTicks = fetchBudgetTicks;
    }

    @Override
    protected Action enter(Phase phase) {
        current = phase == Phase.EAT ? moves.eating()
                : moves.fetching(fetchBudgetTicks);
        return current;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        FactsNow now = new FactsNow(reader.read(context));
        if (phase == Phase.EAT) {
            return eat(context, now);
        }
        return fetch(context, now);
    }

    private Next<Phase> eat(TickContext context, FactsNow now) {
        if (now.satisfied()) {
            return Next.done(TaskResult.builder(TaskResult.Status.DONE, "吃上了，不再饿着").build());
        }
        if (current == null) {
            // 没有进食流程可组合：身上有吃的才轮到这一步，连吃都不能自动吃就如实说。
            events.publish("need_unmet",
                    "饿了但进食还没接上流程，吃不了一口；主任务照常继续");
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                    "饿了；身上有吃的但没法自动吃，等下一个空当或人工指派进食").build());
        }
        return runCurrent(context, () -> {
            // 吃完了再看饱食度；还没到线也算了结——下一次报急自然再插进来。
            FactsNow after = new FactsNow(reader.read(context));
            if (after.satisfied()) {
                return Next.done(TaskResult.builder(TaskResult.Status.DONE, "吃上了，不再饿着").build());
            }
            if (moves.eating() == null) {
                return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        "吃了一口还饿；继续吃还没接上流程，先回去干活").build());
            }
            return Next.stay();
        });
    }

    private Next<Phase> fetch(TickContext context, FactsNow now) {
        if (now.satisfied()) {
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                    fetchedSomething ? "弄到吃的并吃上了" : "不知怎么就不饿了，回去干活").build());
        }
        if (current == null) {
            events.publish("need_unmet",
                    "饿了，身上没有吃的，弄吃的也没接上流程；先回去干活，缺食物这件事已上报");
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                    "饿了且身上没吃的；弄不到（流程未接入），主任务不受影响").build());
        }
        return runCurrent(context, () -> {
            // 一轮弄到就回去吃；弄不到按预算与卡住判定收场，事件交代事实。
            if (new FactsNow(reader.read(context)).carryingEdible()) {
                fetchedSomething = true;
                return Next.go(Phase.EAT, "弄到能吃的了，回来吃");
            }
            events.publish("need_unmet",
                    "饿了，身上没吃的，弄吃的也弄不到（箱子空的、做不了、采不到都可能）；先回去继续干活");
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                    "弄不到吃的：试过的路都没走通，主任务不受影响，继续干活").build());
        });
    }

    // 推进当前动作：还在做留在本阶段（做成了走给定的走向），失败转去弄吃的或如实收场。
    private Next<Phase> runCurrent(TickContext context, Supplier<Next<Phase>> whenDone) {
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running running) {
            if (running.progressed()) {
                recordProgress(current.describe());
            }
            return Next.stay();
        }
        if (status instanceof ActionStatus.Done) {
            return whenDone.get();
        }
        ActionStatus.Failed failed = (ActionStatus.Failed) status;
        if (phase() == Phase.EAT) {
            // 吃没成：身上还有吃的的话下一刻再试一次，别把一口失败当成吃不上。
            recordProgress("一口没吃成：" + failed.problem().message());
            current = moves.eating();
            return Next.stay();
        }
        events.publish("need_unmet",
                "弄吃的没成：" + failed.problem().message() + "；先回去继续干活");
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                "弄不到吃的：" + failed.problem().message() + "；主任务不受影响").build());
    }

    @Override
    protected ResultDetails details() {
        return ResultDetails.NONE;
    }

    /** 当刻的饥饿事实，读不到时按"不饿"处理（角色不在就没这件事）。 */
    private record FactsNow(int food, boolean losingHealth, boolean carryingEdible) {
        FactsNow(HungerNeed.Facts facts) {
            this(facts == null ? 20 : facts.food(),
                    facts != null && facts.losingHealth(),
                    facts != null && facts.carryingEdible());
        }

        boolean satisfied() {
            return food > HungerNeed.SPRINT_FLOOR && !losingHealth;
        }
    }
}
