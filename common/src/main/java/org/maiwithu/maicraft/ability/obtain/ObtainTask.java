// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.CarriedItems;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.StartsAcquisition;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 拿东西的任务：先清点身上够不够（够了直接完成，一个动作都不用做），
 * 不够就把请求交给拿到物品的引擎——问遍来源、按代价挑路、做一个来源重新清点一次。
 * 引擎做完算拿到；引擎认输（所有路都试完）时，这次已经拿到几件照实结算——
 * 部分拿到是 partial 加还差几件，一件没拿到才是失败，两条路不混淆。
 */
final class ObtainTask extends PhasedTask<ObtainTask.Phase> {

    /** 任务的阶段：先清点，再交给引擎。 */
    enum Phase { COUNT, ACQUIRE }

    /** 连续一分钟没有真实进展算卡住；整件事最多做半小时（引擎内部的耐心另算）。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60;
    private static final long MAX_TICKS = 20L * 60 * 30;

    private final StartsAcquisition acquisition;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final ObtainItems input;
    /** 任务开始时身上有几件；结算"这次拿到几件"的基准。 */
    private int carriedAtStart;
    /** 实际拿到东西的途径，按拿到先后排，一条不重复。 */
    private final Set<String> obtainedRoutes = new java.util.LinkedHashSet<>();

    ObtainTask(ObtainItems input, StartsAcquisition acquisition, BackpackView backpack,
            OffhandContents offhand, ReadsItemTags tags) {
        super("拿东西", Phase.COUNT, new ProgressTracker(STUCK_AFTER_TICKS, MAX_TICKS));
        this.input = input;
        this.acquisition = acquisition;
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
    }

    @Override protected Action enter(Phase phase) {
        // 清点是只读判断，不需要动作；引擎的动作在进入 ACQUIRE 时才发起，许可随输入一起带过来。
        return phase == Phase.ACQUIRE
                ? acquisition.need(new ItemRequest(input.wanted(), input.count(), input.purpose()),
                        input.permissions(), input.scope(), obtainedRoutes::add)
                : null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case COUNT -> countPhase();
            case ACQUIRE -> acquirePhase(context);
        };
    }

    // 开始时已够：一个动作都不用做，直接完成并写明"开始时已够"。
    private Next<Phase> countPhase() {
        int carried = CarriedItems.matching(backpack, offhand, input.wanted(), tags);
        if (carried >= input.count()) {
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                    "开始时身上已够：要再拿 " + input.count() + " 个" + input.wanted().describe()
                            + "，身上已有 " + carried + " 个").details(details()).build());
        }
        carriedAtStart = carried;
        return Next.go(Phase.ACQUIRE, "身上只有 " + carried + " 个，还差 "
                + (input.count() - carried) + " 个，去各来源弄");
    }

    // 引擎逐刻推进：做完重新清点结算；认输时按这次拿到几件分别落到 partial 或失败。
    private Next<Phase> acquirePhase(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> settle(null);
            case ActionStatus.Failed failed -> settle(failed.problem());
        };
    }

    private Next<Phase> settle(Problem failure) {
        int carried = CarriedItems.matching(backpack, offhand, input.wanted(), tags);
        int gained = carried - carriedAtStart;
        if (gained > 0) {
            // 已确认的入包不因结果不合预期就记成"不知道"：这次拿到几件如实记进 changes。
            recordChange(Change.of(Change.Kind.ITEM_GAINED, input.wanted().specifier(), gained));
        }
        String item = input.wanted().describe();
        if (failure == null) {
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                    "拿到了：这次经 " + routesText() + " 拿到 " + gained + " 个" + item
                            + "，身上现在有 " + carried + " 个")
                    .details(details()).build());
        }
        if (gained > 0) {
            int stillNeeded = input.count() - carried;
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                    "部分拿到：经 " + routesText() + " 拿到 " + gained + " 个" + item
                            + "，还差 " + stillNeeded + " 个")
                    .problem(failure)
                    .remaining(List.of("还差 " + stillNeeded + " 个" + item))
                    .details(details()).build());
        }
        return Next.fail(failure);
    }

    @Override protected ResultDetails details() {
        return new ObtainedVia(List.copyOf(obtainedRoutes));
    }

    // 一条都没拿到时结果里不编途径：summary 说清是失败，细节给空列表。
    private String routesText() {
        return obtainedRoutes.isEmpty() ? "身上已有的" : String.join("、", obtainedRoutes);
    }
}
