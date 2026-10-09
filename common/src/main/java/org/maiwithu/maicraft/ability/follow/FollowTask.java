// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.Standing;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.Objects;

/**
 * 跟随任务：目标走就跟、停就停，保持设定的距离；常驻，不自己结束。
 *
 * <p>距离带迟滞防抖：走出设定值 +2 格才起步，回到设定值以内就停，不在临界距离一步一停。
 * 每刻用实体的真实身份核对目标：编号被重用给了别的东西就判目标丢失，不跟着新实体走。
 * 路线走不通时以 UNREACHABLE 结束，附上"允许改哪些方块就能过去"的说明。
 */
final class FollowTask extends PhasedTask<FollowTask.Phase> implements Standing {

    /** 跟随的阶段：先锁定目标，之后一直保持距离。 */
    enum Phase { LOCK, KEEP }

    /** 起步要超出设定距离多少格；迟滞防抖，避免在临界距离上一进一出地走停。 */
    static final double HYSTERESIS = 2.0;

    /** 目标移出多远才重算一次路线；小步挪动不必每步重寻路。 */
    private static final double RETARGET_SLACK = 3.0;

    /** 连续半分钟既没跟上也没报错才算卡住。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;

    private final FollowInput input;
    private final FollowView view;
    private final WalkTo walks;
    private final TerrainPermit permit;

    private FollowView.Locked target;
    private String lastDirection;
    private double lastDistance = -1;
    private TickContext currentContext;
    private WalkRun walk;
    /** 这次路线要去的锚点；目标挪远过它才重算路线。 */
    private BlockPos walkAnchor;

    FollowTask(FollowInput input, FollowView view, WalkTo walks) {
        super("跟随", Phase.LOCK, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.input = Objects.requireNonNull(input, "input");
        this.view = Objects.requireNonNull(view, "view");
        this.walks = Objects.requireNonNull(walks, "walks");
        // 跟随的走到许可直接沿用任务许可的档位：只垫不挖时跟人开路也只垫不拆；落地水随动土一起放开。
        this.permit = TerrainPermit.of(input.permissions());
    }

    /** 把走在路上的动作接进基类的暂停与收尾：基类持有的是这个外壳。 */
    private final class Driver implements Action {
        @Override
        public ActionStatus tick(TickContext context) {
            return walk == null ? ActionStatus.running() : walk.tick(context);
        }

        @Override
        public void pause() {
            if (walk != null) walk.stop();
        }

        @Override
        public void close() {
            if (walk != null) {
                walk.stop();
                walk = null;
            }
        }

        @Override
        public String describe() {
            return walk == null ? "保持距离" : walk.describe();
        }
    }

    private final Driver driver = new Driver();

    @Override protected Action enter(Phase phase) { return driver; }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case LOCK -> lock();
            case KEEP -> keep(context);
        };
    }

    private Next<Phase> lock() {
        FollowView.Locked locked = view.lock(currentContext, input.target());
        if (locked == null) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                    "找不到要跟的目标：它现在不在视野或观察登记里，先 observe 确认它还在",
                    "重新观察后用新的观察编号再跟"));
        }
        target = locked;
        lastDirection = locked.direction();
        return Next.go(Phase.KEEP, "锁定了目标 " + locked.type());
    }

    private Next<Phase> keep(TickContext context) {
        currentContext = context;
        FollowView.Observed observed = view.observe(context);
        if (observed == null) {
            String where = lastDirection == null ? "未知方位" : lastDirection;
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "跟丢了目标：最后看到它在" + where + "，现在看不见了（退出世界、走远或死亡）",
                    "再 observe 一次，找到它后用新的观察编号重新跟随"));
        }
        lastDirection = observed.direction();
        lastDistance = observed.distance();
        recordProgress("距离 " + Math.round(observed.distance() * 10) / 10.0 + " 格");

        if (walk != null) {
            Next<Phase> settled = stepWalk(context);
            if (settled != null) return settled;
        }
        if (walk == null && observed.distance() > input.distance() + HYSTERESIS) {
            startWalk(observed);
        }
        // 正常保持距离不算完成：跟随是常驻任务，只有丢失与走不通才结束。
        return Next.stay();
    }

    // 推进一次走到；还在走返回 null，这一刻走到已结算（到站）或失败（已带问题收场）时返回走向。
    private Next<Phase> stepWalk(TickContext context) {
        ActionStatus status = walk.tick(context);
        if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordProgress(walk.describe());
        }
        WalkReport report = walk.report();
        if (status instanceof ActionStatus.Failed failed) {
            walk = null;
            walkAnchor = null;
            return Next.fail(unreachable(failed.problem()));
        }
        if (status instanceof ActionStatus.Done || report.state() == WalkReport.State.ARRIVED
                || report.state() == WalkReport.State.STOPPED) {
            walk = null;
            walkAnchor = null;
            return Next.stay();
        }
        return null;
    }

    private void startWalk(FollowView.Observed observed) {
        // 目标悬空时锚取它脚下的位置；停止判断仍按实体本体的三维距离。
        WorldPosition position = observed.position();
        BlockPos anchor = new BlockPos(position.x(), position.y(), position.z());
        if (walkAnchor != null && walkAnchor.distSqr(anchor) < RETARGET_SLACK * RETARGET_SLACK) {
            return;
        }
        walkAnchor = anchor;
        // 目标走远了要换目标点：先收尾上一趟走到交出身体，新的一趟才上得了路。
        if (walk != null) {
            walk.close();
        }
        // 走到离目标半格距离的范围内就算跟上，剩下的距离差交给迟滞判断。
        walk = walks.start(GoalCompiler.near(anchor, Math.max(1.0, input.distance() * 0.5)), permit);
    }

    private Problem unreachable(Problem cause) {
        recordProgress("路线走不通：" + cause.message());
        boolean mayChange = permit.mayChangeTerrain();
        // 跟随默认不改世界；开路的出路写全，让 LLM 决定要不要放开地形许可。
        return Problem.of(Problem.Kind.UNREACHABLE,
                "跟不上目标：路线走不通——" + cause.message(),
                mayChange
                        ? "允许的地形改动已经用上还是过不去；等它走回来，或取消跟随"
                        : "这次任务不允许改地形（change_blocks=none）；把 change_blocks 提到 temporary 或更高，也许就能跟过去");
    }

    @Override
    protected ResultDetails details() {
        return target == null ? ResultDetails.NONE
                : new FollowDetails(target.type() + "（实体 " + target.entityId() + "）",
                        lastDistance < 0 ? null : Math.round(lastDistance * 10) / 10.0, lastDirection);
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 在走动中停下来是安全的：跟随随时可以被人叫住。
        return walk != null ? Interruptibility.WORKING : Interruptibility.BETWEEN_ACTIONS;
    }

    /** 跟随任务的结果细节：跟的是谁、结束时的距离与最后一次看到目标在哪个方位。 */
    record FollowDetails(String targetId, Double keptDistance, String lastDirection) implements ResultDetails {}
}
