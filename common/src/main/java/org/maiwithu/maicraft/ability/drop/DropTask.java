// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.drop;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.inventory.DropsItems;
import org.maiwithu.maicraft.behavior.inventory.TossesOnto;
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
    private final TossOnto tossOnto;
    private DropsItems dropping;
    /** 朝一格扔的那一下；没指定往哪扔为 null。 */
    private TossesOnto tossing;

    DropTask(DropInput input, BackpackView backpack, OffhandContents offhand, DropsItems.Parts parts) {
        this(input, backpack, offhand, parts, null);
    }

    DropTask(DropInput input, BackpackView backpack, OffhandContents offhand, DropsItems.Parts parts, TossOnto tossOnto) {
        super("丢东西", Phase.DROP, new ProgressTracker(100, 20L * 60 * 2));
        this.input = input;
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = Objects.requireNonNull(offhand, "offhand");
        this.parts = Objects.requireNonNull(parts, "parts");
        this.tossOnto = tossOnto;
    }

    @Override
    protected Action enter(Phase phase) {
        // 指定了往哪一格扔（传送带、工作盆上）：走到旁边、换到主手、按抛物线扔过去，看着它落下。
        if (input.onto() != null && tossOnto != null) {
            // 走到那一格旁边按这次目标的许可走：change_blocks=none 时不为扔一下去挖路。
            tossing = tossOnto.toss(input.itemId(), Math.min(input.count(), carried()), input.onto(), input.permissions());
            return tossing;
        }
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
        if (tossing != null) return tossed();
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

    // 朝一格扔完：照看见的说——落上去被收走的、停在上面的、没落到那一格的各几件。
    private Next<Phase> tossed() {
        String notes = tossing.notes().isEmpty() ? "" : "；" + String.join("；", tossing.notes());
        String summary = "朝 " + input.onto().toShortString() + " 扔了 " + input.itemId() + "：落上去 "
                + (tossing.delivered() + tossing.waiting()) + " 件" + notes;
        boolean all = tossing.notDelivered() == 0 && tossing.delivered() + tossing.waiting() >= input.count();
        return Next.done(TaskResult.builder(all ? TaskResult.Status.DONE : TaskResult.Status.PARTIAL, summary).build());
    }

    // 还差几件：朝一格扔的按落上去的（被收走的加停在上面的）扣，随手丢的按丢出的扣。
    @Override protected List<String> remaining() {
        int done = tossing != null ? tossing.delivered() + tossing.waiting()
                : dropping == null ? 0 : dropping.dropped();
        int left = input.count() - done;
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
