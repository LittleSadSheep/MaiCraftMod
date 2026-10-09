// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 临时吃饭任务：身上有合适的吃的就吃一口；没有就做一个有预算的弄吃的，弄到再吃。
 *
 * <p>一次只吃一口：吃完还饿，饥饿需求下一次报急时自然再插进来，按急迫程度决定是立刻接着吃
 * 还是等手上的活到了空当。吃不上、弄不到就以没做成收场并发事件把事实告诉 LLM，
 * 控制循环据此缓一阵再插；主任务不受影响，饿着肚子回去继续干活。
 *
 * <p>确认只认真的：一口的动作做完后再读一次处境，饱食度过线才说"吃上了"——
 * "还不算饿"从来不是"吃上了"的证据。谎报一次进展，死亡态不再恢复的饿度会让
 * 同一条假消息每刻重播，主任务也被牵着反复空转。
 */
public final class EatSoonTask extends PhasedTask<EatSoonTask.Phase> {

    /** 吃饭任务的阶段：吃一口；身上没合适的吃的时先弄吃的。 */
    enum Phase { EAT, FETCH }

    /** 弄吃的的默认预算：一分钟。只为弄一口饭，不为它跑半个地图。 */
    static final long DEFAULT_FETCH_BUDGET_TICKS = 20L * 60;

    /** 同一口接连吃不成几次就收手：游戏一直不让吃时如实交代，不在这里无限重试。 */
    static final int MAX_FAILED_BITES = 3;

    /** 连续半分钟没吃上也没弄到才算卡住。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;

    /**
     * 吃与弄吃的怎么落地。生产实现按处境挑一件随身食物、换到主手原生吃一口（主手那堆
     * 吃完了回到换手一步换下一堆）；弄吃的还没接上时给 null，任务会如实报告。
     */
    public interface FoodMoves {
        /** 按此刻的饥饿处境挑一件随身食物吃一口；身上没有这会儿该吃的东西时为 null。 */
        Action eating(HungerNeed.Facts hunger);

        /** 弄一件能直接吃的：给预算（刻）；还没接入时为 null。 */
        Action fetching(long budgetTicks);
    }

    private final FoodMoves moves;
    private final HungerNeed.ReadsFacts reader;
    private final TaskEventSink events;
    private final long fetchBudgetTicks;

    /** 最近一刻读到的饥饿处境：进入"吃一口"时按它挑吃什么。 */
    private HungerNeed.Facts latest;
    private int failedBites;
    private boolean fetchedSomething;

    EatSoonTask(FoodMoves moves, HungerNeed.ReadsFacts reader, TaskEventSink events, HungerNeed.Facts initial) {
        this(moves, reader, events, initial, DEFAULT_FETCH_BUDGET_TICKS);
    }

    EatSoonTask(FoodMoves moves, HungerNeed.ReadsFacts reader, TaskEventSink events,
                HungerNeed.Facts initial, long fetchBudgetTicks) {
        super("吃饭", Phase.EAT, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.moves = Objects.requireNonNull(moves, "moves");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.events = Objects.requireNonNull(events, "events");
        this.latest = initial;
        this.fetchBudgetTicks = fetchBudgetTicks;
    }

    @Override
    protected Action enter(Phase phase) {
        if (phase == Phase.FETCH) {
            return moves.fetching(fetchBudgetTicks);
        }
        return latest == null ? null : moves.eating(latest);
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        // 每刻先看还饿不饿：已经不饿（或角色不在了）就收场，不为了"吃过"而吃。
        // 身上还有能吃的、只是饱食度还没掉到线以下时不在这里收工——那正是这次被插进来的
        // 原因（找空当垫一口），真咽下一口才算数。
        latest = reader.read(context);
        // 饱食度没掉到线以下但身上还有能吃的，assess 给 LATER，不会走到这里——
        // 那正是这次被插进来的原因（找空当垫一口），真咽下一口才算数。
        if (latest == null || HungerNeed.assess(latest) == null) {
            return Next.done(TaskResult.done("已经不饿了，回去干活"));
        }
        return phase == Phase.EAT ? eat(context) : fetch(context);
    }

    // 吃一口：身上没合适的吃的先去弄；进食流程没接上就如实交代；咽下一口后再读处境，
    // 饱食度过线才说"吃上了"，还饿也照实说吃了一口，由饥饿需求下一次报急再接着吃。
    private Next<Phase> eat(TickContext context) {
        if (action() == null) {
            if (fetchedSomething) {
                return giveUp(Problem.of(Problem.Kind.NEED_ITEM,
                        "弄来的东西不是这会儿能直接吃的", "给角色一些普通食物"));
            }
            if (latest != null && latest.carryingEdible()) {
                // 身上有能吃的却给不出吃的动作：进食流程还没接上。真饿着就发事件把事实
                // 告诉 LLM；只是想垫一口就不打扰。都以完成收场，主任务照常继续。
                if (hungry(latest)) {
                    events.publish(TaskEvent.Kind.NEED_UNHANDLED,
                            "饿了但进食还没接上流程，吃不了一口；主任务照常继续");
                }
                return Next.done(TaskResult.done("身上有吃的但没法自动吃，等下一个空当或人工指派进食"));
            }
            return Next.go(Phase.FETCH, "身上没有这会儿该吃的东西，去弄");
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                // 这一口吃完了再读一次处境：饱食度过线才说"吃上了"；还饿就如实交代，
                // 不把一次动作收尾当成不饿了——死亡态饿度不恢复，谎报会每刻重播。
                HungerNeed.Facts after = reader.read(context);
                latest = after != null ? after : latest;
                if (after != null && !hungry(after)) {
                    yield Next.done(TaskResult.done("吃上了，不再饿着"));
                }
                yield Next.done(TaskResult.done("吃了一口；还饿的话下一次报急再接着吃"));
            }
            case ActionStatus.Failed failed -> {
                failedBites++;
                if (failedBites >= MAX_FAILED_BITES) {
                    yield giveUp(failed.problem());
                }
                // 重新进入"吃一口"：收尾这一口的动作，按此刻的处境重新挑一件再吃。
                yield Next.go(Phase.EAT, "这一口没吃成，再试：" + failed.problem().message());
            }
        };
    }

    // 弄吃的：没接上或弄不到都如实收场；弄到了回去吃。
    private Next<Phase> fetch(TickContext context) {
        if (action() == null) {
            return giveUp(Problem.of(Problem.Kind.NEED_ITEM,
                    "饿了，身上没有这会儿该吃的东西，弄吃的还没接上", "给角色一些普通食物"));
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                fetchedSomething = true;
                yield Next.go(Phase.EAT, "弄到吃的了，回来吃");
            }
            case ActionStatus.Failed failed -> giveUp(failed.problem());
        };
    }

    // 吃不上：发事件把事实告诉 LLM，以没做成收场；主任务照常继续。
    private Next<Phase> giveUp(Problem problem) {
        events.publish(TaskEvent.Kind.NEED_UNHANDLED, "饿了但没吃上：" + problem.message() + "；先回去继续干活");
        return Next.fail(problem);
    }

    /** 真饿着：饱食度掉到疾跑线及以下，或身体已经在扣血。只是没吃饱不算。 */
    private static boolean hungry(HungerNeed.Facts facts) {
        return facts.food() <= HungerNeed.SPRINT_FLOOR || facts.losingHealth();
    }
}
