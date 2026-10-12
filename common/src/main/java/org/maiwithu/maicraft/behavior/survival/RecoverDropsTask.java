// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
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

        /** 此刻站在哪（x, y, z）；读不到为 null。 */
        double[] selfPosition(TickContext context);

        /** 身上有几格东西：捡没捡回来按它前后的差说。 */
        int carriedStacks(TickContext context);
    }

    /** 离死的地方这么近（格）才算跑到了，地上的东西在拾取扫描的范围里。 */
    private static final double CLOSE_ENOUGH = 6;
    /** 跑回去的路一次没走通，再起步几次：刚重生那一两刻世界还没就绪，走到常常一出手就收场。 */
    private static final int RUN_TRIES = 3;

    private final LostItemsNeed.Death death;
    private final long expiresAtTick;
    private final Moves moves;
    private final TaskEventSink events;
    private int runs;
    private int carriedBefore = -1;

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
            case RUN_BACK -> runBack(context);
            case PICK_UP -> {
                yield switch (runAction(context)) {
                    case ActionStatus.Running running -> Next.stay();
                    case ActionStatus.Done done -> finish(pickedText(context), TaskResult.Status.DONE);
                    case ActionStatus.Failed failed -> {
                        recordAttempt("捡地上的东西", failed.problem().message());
                        yield finish(pickedText(context) + "；" + failed.problem().message(), TaskResult.Status.PARTIAL);
                    }
                };
            }
        };
    }

    // 跑回去：走到说做完了也要核对人真到了跟前；没到就再起步，几次都不行如实收场，不站在远处空捡。
    private Next<Phase> runBack(TickContext context) {
        // 身上几格东西从出发时数起：跑到地方时走过那堆就已经吸进包了，等开始捡再数就数不出来。
        if (carriedBefore < 0) carriedBefore = moves.carriedStacks(context);
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        if (close(context)) return Next.go(Phase.PICK_UP, "到了死的地方，捡地上的东西");
        if (status instanceof ActionStatus.Failed failed) recordAttempt("跑回死的地方", failed.problem().message());
        if (++runs < RUN_TRIES) return Next.go(Phase.RUN_BACK, "还没到死的地方，再起步");
        if (events != null) {
            events.publish(TaskEvent.Kind.TEMPORARY_TASK_FINISHED, "跑不回死的地方，东西没捡成，接着干活");
        }
        return Next.fail(Problem.of(Problem.Kind.UNREACHABLE, "跑不回死的地方，东西没捡成"));
    }

    private boolean close(TickContext context) {
        double[] here = moves.selfPosition(context);
        return here != null && Math.hypot(here[0] - death.x(), here[2] - death.z()) <= CLOSE_ENOUGH
                && Math.abs(here[1] - death.y()) <= CLOSE_ENOUGH;
    }

    // 捡回多少按身上前后几格东西的差说：地上已经没东西了也照实说，不冒充捡回来了。
    private String pickedText(TickContext context) {
        int gained = moves.carriedStacks(context) - Math.max(0, carriedBefore);
        return gained > 0 ? "捡回了死时撒在地上的东西（身上多了 " + gained + " 格）"
                : "到了死的地方，地上没捡到东西（可能已经消失或被别人捡走）";
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
