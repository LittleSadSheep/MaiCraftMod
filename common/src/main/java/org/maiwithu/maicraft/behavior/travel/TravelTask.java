// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 出行任务：去一个地方。解析目的地 → 交给走到逐刻推进 → 到达或停下时如实结算。
 *
 * <p>到达以走到运行的结论为准（站实落地才算到），出行不另写一套判断。
 * 进展以剩余距离为信号：离目的地更近了才算前进，在原地打转会按卡住收场。
 * 中途停下分两步：先向走到请求停，等落到安全边界（落地、走完正在跨的一步）才真停，
 * 停下后按取消结算，写明实测停在哪、离目的地还差多远；被生存需求打断时走到运行先把自己停稳，
 * 处理完从原地接着走。路上垫的临时方块逐格进结果，不自动收回；泅渡如实记录。
 */
public final class TravelTask extends PhasedTask<TravelTask.Phase> {

    /** 出行的阶段：先确认要去哪，再在路上。 */
    public enum Phase { RESOLVE, WALK }

    /** 连续半分钟离目的地没有更近，就算在原地打转。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;

    private final Target target;
    private final double radius;
    private final TerrainPermit permit;
    private final WalkTo walks;
    private final DestinationResolver resolver;
    private final ReadsPlacedBlocks placedBlocks;
    private final TravelProgressListener progressListener;

    private TravelDestination destination;
    private WalkRun walk;
    private Question pendingQuestion;
    private TravelSettlement settlement;
    private boolean stopRequested;
    private boolean crossedWater;
    private double bestRemaining = Double.MAX_VALUE;

    /**
     * @param target        去哪：坐标（y 可省略）、叫得出名字的地点、看得见的东西、往某方向走多远
     * @param radius        到达容差，单位格；0 表示要站进那一格
     * @param maxSeconds    最多走多久（秒）；不超过 0 表示不设时限
     * @param permit        这次被允许动多少地形（能不能挖路垫路）
     */
    public TravelTask(Target target, double radius, int maxSeconds, TerrainPermit permit,
            WalkTo walks, DestinationResolver resolver, ReadsPlacedBlocks placedBlocks,
            TravelProgressListener progressListener) {
        super("出行", Phase.RESOLVE, new ProgressTracker(STUCK_AFTER_TICKS,
                maxSeconds > 0 ? maxSeconds * 20L : Long.MAX_VALUE));
        this.target = target;
        this.radius = radius;
        this.permit = permit;
        this.walks = walks;
        this.resolver = resolver;
        this.placedBlocks = placedBlocks;
        this.progressListener = progressListener;
    }

    /** 目标说不清时挂起的提问；内核的提问钩子取走它去问玩家。 */
    public Optional<Question> pendingQuestion() {
        return Optional.ofNullable(pendingQuestion);
    }

    /** 请求中途停下：先向走到请求，落到安全边界才真停，随后按取消结算。 */
    public void requestStop() {
        stopRequested = true;
    }

    @Override
    protected Action enter(Phase phase) {
        // 只有在路上有动作；解析阶段只读现场、给结论。
        return phase == Phase.WALK ? startWalk() : null;
    }

    /** 把目的地编译成导航目标交给走到；出行不碰寻路内部。 */
    private WalkRun startWalk() {
        // 出行没有途中要保护的方块：目的地是去站的地方，不是要去动的东西。
        walk = walks.start(new GoalCompiler.Compiled(destination.navGoal(), LongSets.emptySet()), permit);
        return walk;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case RESOLVE -> tickResolve();
            case WALK -> tickWalk(context);
        };
    }

    private Next<Phase> tickResolve() {
        if (pendingQuestion != null) {
            // 在等玩家回答：内核的提问钩子会暂停本任务，这里不重复解析也不超时瞎猜。
            return Next.stay();
        }
        DestinationResolver.Resolution resolution = resolver.resolve(target, radius);
        if (resolution instanceof DestinationResolver.Resolution.Ready ready) {
            destination = ready.destination();
            recordProgress("目的地定了：" + destination.describe());
            return Next.go(Phase.WALK, "目的地解析完成，出发");
        }
        if (resolution instanceof DestinationResolver.Resolution.AlreadyThere spot) {
            settlement = new TravelSettlement(spot.spot(), null, List.of(), false);
            return Next.done(TaskResult.done("出行：开始时已经站在目的地"));
        }
        if (resolution instanceof DestinationResolver.Resolution.Unclear unclear) {
            pendingQuestion = unclear.question();
            recordProgress("目的地说不清，已向玩家提问");
            return Next.stay();
        }
        return Next.fail(((DestinationResolver.Resolution.DeadEnd) resolution).problem());
    }    private Next<Phase> tickWalk(TickContext context) {
        if (stopRequested) {
            return tickStopping(context);
        }
        ActionStatus status = runAction(context);
        WalkReport report = walk.report();
        observe(report);
        // 生存需求打断时请求过停；停稳了就从原地重新上路，接着走完剩下的路。
        if (report.state() == WalkReport.State.STOPPED) {
            recordProgress("从原地接着走");
            return Next.go(Phase.WALK, "上一段走到已停稳，从原地接着走");
        }
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> settleArrival(report);
            case ActionStatus.Failed failed -> failWithJourney(failed.problem());
        };
    }

    /** 中途停下：先请求停，继续逐刻推进走到让它落到安全边界，停稳后按取消结算。 */
    private Next<Phase> tickStopping(TickContext context) {
        boolean settled = walk.stop();
        ActionStatus status = runAction(context);
        WalkReport report = walk.report();
        observe(report);
        // 走不动了也算停下了：取消结算如实交代停在哪，不把停不下来说成失败。
        if (settled || report.state() == WalkReport.State.STOPPED
                || status instanceof ActionStatus.Failed) {
            RemainingDistance remaining = measureFrom(report);
            buildSettlement(report.feet(), false, remaining);
            return Next.done(TaskResult.cancelled("出行：中途停下，停在 " + shortSpot(report.feet())
                    + "，离目的地还" + (remaining == null ? "远近未知" : "剩 " + remaining.describe())));
        }
        return Next.stay();
    }

    private Next<Phase> settleArrival(WalkReport report) {
        if (report.state() != WalkReport.State.ARRIVED || report.feet() == null) {
            // 走到报了做完但身体没到位：到达以走到运行的结论为准，结论不对就不能冒充到达。
            return failWithJourney(Problem.of(Problem.Kind.INTERNAL_ERROR,
                    "走到报了做完，但到达情况不是到达：" + report.state()));
        }
        buildSettlement(report.feet(), true, null);
        String water = crossedWater ? "，途中泅渡过水" : "";
        return Next.done(TaskResult.done("出行：走到了目的地，实测位置 " + shortSpot(report.feet()) + water));
    }

    private Next<Phase> failWithJourney(Problem problem) {
        WalkReport report = walk.report();
        buildSettlement(report.feet(), false, measureFrom(report));
        return Next.fail(problem);
    }

    /** 记录这一刻的行程观察：泅渡、剩余距离、逐刻进展。 */
    private void observe(WalkReport report) {
        crossedWater = crossedWater || report.crossedWater();
        RemainingDistance remaining = measureFrom(report);
        if (remaining != null && remaining.horizontalBlocks() < bestRemaining) {
            // 剩余距离是进展信号：更近了才算前进，站着不动早晚被判卡住。
            bestRemaining = remaining.horizontalBlocks();
            recordProgress("离目的地还剩 " + remaining.describe());
        }
        progressListener.onTravelProgress(new TravelProgress(
                describeStage(report), report.feet(), remaining, crossedWater));
    }

    private String describeStage(WalkReport report) {
        return switch (report.state()) {
            case PLANNING -> "正在算路";
            case ON_THE_WAY -> stopRequested ? "正在安全停下" : "在路上";
            case ARRIVED -> "到达";
            case STOPPED -> "已停下";
            case FAILED -> "走不过去";
        };
    }

    /** 结算行程：垫过的方块逐格进变化与细节；走到没走到分开写，不混在一起。 */
    private void buildSettlement(BlockPos feet, boolean arrived, RemainingDistance remaining) {
        List<String> placed = new ArrayList<>();
        for (BlockPos cell : placedBlocks.placedDuringCurrentWalk()) {
            placed.add(cell.toShortString());
            // 垫路用的临时方块先不自动收回；要不要拆由玩家看了结果再决定。
            recordChange(new Change(Change.Kind.BLOCK_PLACED, cell.toShortString(), 1,
                    "垫路用的临时方块，没有自动收回"));
        }
        WorldPosition arrivedAt = arrived && feet != null
                ? new WorldPosition(feet.getX(), feet.getY(), feet.getZ(), destination.position().dimension())
                : null;
        settlement = new TravelSettlement(arrivedAt, arrived ? null : remaining, placed, crossedWater);
    }

    private RemainingDistance measureFrom(WalkReport report) {
        return destination == null || report.feet() == null
                ? null
                : RemainingDistance.measure(report.feet(), destination.center());
    }

    @Override
    protected ResultDetails details() {
        return settlement == null ? ResultDetails.NONE : settlement;
    }

    @Override
    protected List<String> remaining() {
        if (settlement != null && settlement.remaining() != null) {
            return List.of("还没走到目的地：差 " + settlement.remaining().describe());
        }
        return List.of();
    }

    @Override
    protected String describePhase(Phase value) {
        return value == Phase.RESOLVE ? "确认要去哪" : stopRequested ? "正在安全停下" : "在路上";
    }

    private String shortSpot(BlockPos feet) {
        return feet == null ? "未知" : "(" + feet.getX() + ", " + feet.getY() + ", " + feet.getZ() + ")";
    }
}
