// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.drop;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.inventory.DropsItems;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 丢东西的任务：怎么丢交给玩家行为层的丢东西动作（看向远处、换到主手、逐份抛、登记落点、走开），
 * 这里只定丢几件、把结果写清楚。
 *
 * <p>数量以执行时身上实际持有的为准：请求超量就按持有的丢，partial 结束写清差额。
 * 抛掷没等到确认的那一下按没能确认的交互如实记账——没等到确认不等于确定没丢，也不盲目重试；
 * 还没丢的照实写进剩余，不混进没能确认里。走不出去时在结果里写明"可能被自己捡回"。
 */
final class DropTask extends PhasedTask<DropTask.Phase> {

    /** 只有一步：丢，丢完走开都在丢东西动作里。 */
    enum Phase { DROP }

    private final DropInput input;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final DropsItems.Parts parts;
    private DropsItems dropping;

    DropTask(DropInput input, BackpackView backpack, OffhandContents offhand, DropsItems.Parts parts) {
        super("丢东西", Phase.DROP, new ProgressTracker(100, 20L * 60 * 2));
        this.input = input;
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = Objects.requireNonNull(offhand, "offhand");
        this.parts = Objects.requireNonNull(parts, "parts");
    }

    @Override
    protected Action enter(Phase phase) {
        // 丢出的每一堆、没等到确认的那一下，都由丢东西动作直接记进本任务的结果。
        dropping = new DropsItems(input.itemId(), input.count(), this::carried, parts, records(), null);
        return dropping;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> finish();
            case ActionStatus.Failed failed -> Next.fail(failed.problem());
        };
    }

    // 丢完了：中途出过岔子按部分完成写明原因；丢够了是完成，身上不够是部分完成。
    private Next<Phase> finish() {
        int dropped = dropping.dropped();
        String closing = "（" + dropping.leaving() + "）";
        if (dropping.endedEarly().isPresent()) {
            Problem why = dropping.endedEarly().get();
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, "丢出了 " + dropped + " 件 "
                            + input.itemId() + "，没有全部丢完：" + why.message() + closing)
                    .problem(why).build());
        }
        return Next.done(TaskResult.builder(
                dropped >= input.count() ? TaskResult.Status.DONE : TaskResult.Status.PARTIAL,
                "丢出了 " + dropped + " 件 " + input.itemId() + closing).build());
    }

    @Override protected List<String> remaining() {
        int left = input.count() - (dropping == null ? 0 : dropping.dropped());
        return left <= 0 ? List.of()
                : List.of("还差 " + left + " 件" + input.itemId() + "没丢（身上只有这些，或丢的时候出了岔子）");
    }

    // 身上（主背包加副手）现在实际有几件要丢的。
    private int carried() {
        int total = 0;
        for (var stack : backpack.stacks()) {
            if (stack.itemId().equals(input.itemId())) total += stack.count();
        }
        if (offhand != null) {
            total += offhand.heldInOffhand()
                    .filter(stack -> stack.itemId().equals(input.itemId()))
                    .map(stack -> stack.count())
                    .orElse(0);
        }
        return total;
    }
}
