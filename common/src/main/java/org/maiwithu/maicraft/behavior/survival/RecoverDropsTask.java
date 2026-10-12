// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 回死的地方捡东西的临时任务：跑回死的地方 → 把附近地上的东西一件件捡起来 → 回去干活。
 *
 * <p>东西快消失了就不再耗着：到点还没捡完如实收场，捡到几件算几件。走不到也如实说，
 * 不为捡尸体挖山填海（走路按这次的默认许可）。
 */
final class RecoverDropsTask extends PhasedTask<RecoverDropsTask.Phase> {

    /** 阶段：跑回去 → 捡。 */
    enum Phase { RUN_BACK, PICK_UP }

    /** 两分钟没进展按卡住收场。 */
    private static final long STUCK_AFTER_TICKS = 20L * 120;

    /** 怎么跑回去、怎么捡：生产接走到与共用的捡掉落物，测试换替身。 */
    interface Moves {
        Action runTo(double x, double y, double z);

        PicksUpDrops drops();
    }

    private final LostItemsNeed.Death death;
    private final long expiresAtTick;
    private final Moves moves;
    private final TaskEventSink events;

    RecoverDropsTask(LostItemsNeed.Death death, long expiresAtTick, Moves moves, TaskEventSink events) {
        super("回去捡东西", Phase.RUN_BACK, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.death = Objects.requireNonNull(death, "death");
        this.expiresAtTick = expiresAtTick;
        this.moves = Objects.requireNonNull(moves, "moves");
        this.events = events;
    }

    @Override
    protected Action enter(Phase phase) {
        return switch (phase) {
            case RUN_BACK -> moves.runTo(death.x(), death.y(), death.z());
            // 死的地方附近地上的东西都是自己撒的：不分先来后到，全捡。
            case PICK_UP -> moves.drops().pickUpNewSince(Set.of());
        };
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        if (context.gameTick() >= expiresAtTick) {
            return finish("东西快消失了，不再耗着", TaskResult.Status.PARTIAL);
        }
        return switch (phase) {
            case RUN_BACK -> switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                case ActionStatus.Done done -> Next.go(Phase.PICK_UP, "到了死的地方，捡地上的东西");
                case ActionStatus.Failed failed -> {
                    // 跑不到正中也照样捡：站在够得着的地方先收一批。
                    recordAttempt("跑回死的地方", failed.problem().message());
                    yield Next.go(Phase.PICK_UP, "跑不到正中，就近捡");
                }
            };
            case PICK_UP -> switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                case ActionStatus.Done done -> finish("捡回了死时撒在地上的东西", TaskResult.Status.DONE);
                case ActionStatus.Failed failed -> {
                    recordAttempt("捡地上的东西", failed.problem().message());
                    yield finish("捡回一部分：" + failed.problem().message(), TaskResult.Status.PARTIAL);
                }
            };
        };
    }

    private Next<Phase> finish(String summary, TaskResult.Status status) {
        if (events != null) {
            events.publish(TaskEvent.Kind.TEMPORARY_TASK_FINISHED, summary + "，接着干活");
        }
        return Next.done(TaskResult.builder(status, summary).build());
    }

    @Override
    protected ResultDetails details() {
        return ResultDetails.NONE;
    }
}
