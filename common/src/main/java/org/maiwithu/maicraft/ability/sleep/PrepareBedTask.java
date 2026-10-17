// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 备床的任务：白天被叫去睡而手上没床时，用拿到物品引擎去弄一张床到身上（身上、随身背包、
 * 已知容器、合成；羊毛不够时引擎自己再想办法）。只把床弄到手，不去床边、不等天黑。
 */
final class PrepareBedTask extends PhasedTask<PrepareBedTask.Phase> {

    /** 任务的进度：只有弄床一个阶段。 */
    enum Phase { OBTAIN }

    /** 两分钟没进展算卡住：合成与取物每步都有确认，两分钟没动静就是绕不出来了。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60 * 2;
    /** 最多备十分钟：拿床这件事有预算，超了带着引擎给的原因结束。 */
    private static final long MAX_TICKS = 20L * 60 * 10;

    private final ItemNeeds obtain;
    private final Permissions permissions;
    private final Supplier<Optional<String>> carriedBed;

    PrepareBedTask(ItemNeeds obtain, Permissions permissions, Supplier<Optional<String>> carriedBed) {
        super("备床", Phase.OBTAIN, new ProgressTracker(STUCK_AFTER_TICKS, MAX_TICKS));
        this.obtain = Objects.requireNonNull(obtain, "obtain");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.carriedBed = Objects.requireNonNull(carriedBed, "carriedBed");
    }

    @Override
    protected Action enter(Phase phase) {
        // 任何一种床都行：合成配方是三块同色羊毛加三块木板，引擎按途径自己挑。
        return obtain.actionFor(new ItemRequest(WantedItem.ofTag("minecraft:beds"), 1, "备今晚的床"), permissions,
                records());
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        // 开始时身上就已经有床了：什么都不用做。
        if (carriedBed.get().isPresent()) {
            return Next.done(TaskResult.done("身上已经有床了，今晚的床备好了"));
        }
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) {
            return Next.stay();
        }
        if (status instanceof ActionStatus.Failed failed) {
            // 引擎弄不到床：带着它给的原因结束，让派活的一方知道缺什么。
            return Next.fail(failed.problem());
        }
        // 引擎说做完了：以背包实际有没有床为准，没有就不冒充备好了。
        if (carriedBed.get().isPresent()) {
            return Next.done(TaskResult.done("今晚的床备好了"));
        }
        return Next.fail(Problem.of(Problem.Kind.NEED_ITEM, "弄床的路走完了，身上还是没有床", null));
    }
}
