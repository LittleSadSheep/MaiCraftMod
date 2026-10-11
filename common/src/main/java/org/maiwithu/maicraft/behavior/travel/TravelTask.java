// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.lighting.TorchesAlongTheWay;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.BackpackStack;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出行任务：去一个地方。解析目的地 → 交给走到逐刻推进 → 到达或停下时如实结算。
 *
 * <p>到达以走到运行的结论为准（站实落地才算到），出行不另写一套判断。
 * 进展以剩余距离为信号：离目的地更近了才算前进，在原地打转会按卡住收场，
 * 收场说明带上现场——停在哪、离目的地还差多少、许可能不能动土；目标高度够不着时写明高度。
 * 中途停下分两步：先向走到请求停，等落到安全边界（落地、走完正在跨的一步）才真停，
 * 停下后按取消结算，写明实测停在哪、离目的地还差多远；被生存需求打断时走到运行先把自己停稳，
 * 处理完从原地接着走。路上垫的临时方块逐格进结果，不自动收回；路上捡进背包与从背包用掉的
 * 东西按刻记进变化；泅渡如实记录。
 */
public final class TravelTask extends PhasedTask<TravelTask.Phase> implements TorchesAlongTheWay {

    /** 出行的阶段：在路上。 */
    public enum Phase { WALK }

    /** 连续半分钟离目的地没有更近，就算在原地打转。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;

    private final TravelDestination destination;
    private final TerrainPermit permit;
    private final WalkTo walks;
    private final ReadsPlacedBlocks placedBlocks;
    private final TravelProgressListener progressListener;

    private WalkRun walk;
    private TravelSettlement settlement;
    private boolean stopRequested;
    private boolean crossedWater;
    private double bestRemaining = Double.MAX_VALUE;
    /** 最近一刻身体所在的方块格：空转收尾与取消结算都从这里取现场。 */
    private BlockPos lastFeet;
    /** 垫块记账的光标：跟着当前走到运行走，换段重新记。 */
    private WalkRun placementsRecordedFor;
    private int recordedPlacements;
    /** 上一刻的背包份数：与这一刻比，多出的是路上捡的，少掉的是路上用掉的。 */
    private Map<String, Integer> bagLastTick;

    /**
     * @param destination 已经解析好的目的地：能力做决定时用 {@link DestinationResolver} 解析，
     *                    说不清就向 LLM 提问、已经站在那里就直接结束，都不用开这个任务
     * @param maxSeconds  最多走多久（秒）；不超过 0 表示不设时限
     * @param permit      这次被允许动多少地形（能不能挖路垫路）
     */
    public TravelTask(TravelDestination destination, int maxSeconds, TerrainPermit permit,
            WalkTo walks, ReadsPlacedBlocks placedBlocks, TravelProgressListener progressListener) {
        super("出行", Phase.WALK, new ProgressTracker(STUCK_AFTER_TICKS,
                maxSeconds > 0 ? maxSeconds * 20L : Long.MAX_VALUE));
        this.destination = destination;
        this.permit = permit;
        this.walks = walks;
        this.placedBlocks = placedBlocks;
        this.progressListener = progressListener;
    }

    /** 请求中途停下：先向走到请求，落到安全边界才真停，随后按取消结算。 */
    public void requestStop() {
        stopRequested = true;
    }

    @Override
    protected Action enter(Phase phase) {
        return startWalk();
    }

    /** 把目的地编译成导航目标交给走到；出行不碰寻路内部。 */
    private WalkRun startWalk() {
        // 出行没有途中要保护的方块：目的地是去站的地方，不是要去动的东西。
        walk = walks.start(new GoalCompiler.Compiled(destination.navGoal(), LongSets.emptySet()), permit);
        return walk;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return tickWalk(context);
    }

    private Next<Phase> tickWalk(TickContext context) {
        if (stopRequested) {
            return tickStopping(context);
        }
        ActionStatus status = runAction(context);
        WalkReport report = walk.report();
        observe(context, report);
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
        observe(context, report);
        // 走不动了也算停下了：取消结算如实交代停在哪，不把停不下来说成失败。
        if (settled || report.state() == WalkReport.State.STOPPED
                || status instanceof ActionStatus.Failed) {
            buildSettlement(lastFeet, false, remainingFromHere());
            return Next.done(TaskResult.cancelled("出行：中途停下，停在 " + shortSpot(lastFeet)
                    + "，离目的地还" + (settlement.remaining() == null ? "远近未知" : "剩 " + settlement.remaining().describe())));
        }
        return Next.stay();
    }

    private Next<Phase> settleArrival(WalkReport report) {
        if (report.state() != WalkReport.State.ARRIVED || report.feet() == null) {
            // 走到报了做完但身体没到位：到达以走到运行的结论为准，结论不对就不能冒充到达。
            return failWithJourney(Problem.of(Problem.Kind.INTERNAL_ERROR,
                    "走到报了做完，但到达情况不是到达：" + report.state()));
        }
        buildSettlement(lastFeet, true, null);
        String water = crossedWater ? "，途中泅渡过水" : "";
        return Next.done(TaskResult.done("出行：走到了目的地，实测位置 " + shortSpot(lastFeet) + water));
    }

    private Next<Phase> failWithJourney(Problem problem) {
        buildSettlement(lastFeet, false, remainingFromHere());
        return Next.fail(problem);
    }

    /** 空转满时限的收尾：换成本领域的现场说明，只给不出更好解释时沿用进度跟踪的通用说法。 */
    @Override
    protected Problem noProgressProblem(ProgressTracker.Status status) {
        Problem generic = super.noProgressProblem(status);
        String lastProgress = status instanceof ProgressTracker.Status.Stuck stuck
                ? stuck.lastProgress() : "开始";
        long idleTicks = switch (status) {
            case ProgressTracker.Status.Stuck stuck -> stuck.idleTicks();
            case ProgressTracker.Status.TimedOut timedOut -> timedOut.activeTicks();
            default -> 0;
        };
        Problem judged = TravelStuckCheck.judge(destination, permit, lastFeet, lastProgress, idleTicks);
        return judged == null ? generic : judged;
    }

    /** 记录这一刻的行程观察：泅渡、剩余距离、逐刻进展，垫块与背包的变化逐刻记账。 */
    private void observe(TickContext context, WalkReport report) {
        crossedWater = crossedWater || report.crossedWater();
        if (report.feet() != null) {
            lastFeet = report.feet();
        }
        recordPlacementsSoFar();
        noteBackpack(context);
        RemainingDistance remaining = remainingFromHere();
        if (remaining != null && remaining.horizontalBlocks() < bestRemaining) {
            // 剩余距离是进展信号：更近了才算前进，站着不动早晚被判卡住。
            bestRemaining = remaining.horizontalBlocks();
            recordProgress("离目的地还剩 " + remaining.describe());
        }
        progressListener.onTravelProgress(new TravelProgress(
                describeStage(report), report.feet(), remaining, crossedWater));
    }

    /** 路上垫的临时方块逐刻记进变化：取消或空转收场时也已经记下，不用等到结算那一刻。 */
    private void recordPlacementsSoFar() {
        if (placementsRecordedFor != walk) {
            placementsRecordedFor = walk;
            recordedPlacements = 0;
        }
        List<BlockPos> placed = placedBlocks.placedDuringCurrentWalk();
        for (int i = recordedPlacements; i < placed.size(); i++) {
            BlockPos cell = placed.get(i);
            // 垫路用的临时方块先不自动收回；要不要拆由玩家看了结果再决定。
            recordChange(new Change(Change.Kind.BLOCK_PLACED, cell.toShortString(), 1,
                    "垫路用的临时方块，没有自动收回"));
        }
        recordedPlacements = placed.size();
    }

    /** 背包每刻记一份份数：与上一刻比，多出来的是路上捡的，少下去的是路上用掉的，都进变化。 */
    private void noteBackpack(TickContext context) {
        if (context.player() == null || context.player().backpack() == null) {
            return;
        }
        Map<String, Integer> now = new HashMap<>();
        for (BackpackStack stack : context.player().backpack().stacks()) {
            now.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        if (bagLastTick != null) {
            for (Change change : bagChanges(bagLastTick, now)) {
                recordChange(change);
            }
        }
        bagLastTick = now;
    }

    /** 纯函数：背包两份份数快照的差——多出的算捡到，少掉的算用掉，测试直接喂快照。 */
    static List<Change> bagChanges(Map<String, Integer> before, Map<String, Integer> after) {
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            int gained = entry.getValue() - before.getOrDefault(entry.getKey(), 0);
            if (gained > 0) {
                changes.add(new Change(Change.Kind.ITEM_GAINED, entry.getKey(), gained,
                        "路上顺带捡进背包"));
            }
        }
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            int consumed = entry.getValue() - after.getOrDefault(entry.getKey(), 0);
            if (consumed > 0) {
                changes.add(new Change(Change.Kind.ITEM_CONSUMED, entry.getKey(), consumed,
                        "出行期间从背包用掉（垫路、吃喝等）"));
            }
        }
        return changes;
    }

    private RemainingDistance remainingFromHere() {
        return destination == null || lastFeet == null
                ? null
                : RemainingDistance.measure(lastFeet, destination.center());
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

    /** 结算行程：垫过的方块、落地救援与卡坑脱困的经过进细节；走到没走到分开写，不混在一起。 */
    private void buildSettlement(BlockPos feet, boolean arrived, RemainingDistance remaining) {
        List<String> placed = new ArrayList<>();
        for (BlockPos cell : placedBlocks.placedDuringCurrentWalk()) {
            placed.add(cell.toShortString());
        }
        WorldPosition arrivedAt = arrived && feet != null
                ? new WorldPosition(feet.getX(), feet.getY(), feet.getZ(), destination.position().dimension())
                : null;
        // 落地救援的经过原样带上：放了什么、收没收回、掉没掉血，LLM 据此决定要不要回去收拾。
        // 卡坑自动脱困的经过也带上：没等许可动了哪些地形，LLM 知道这次为什么多了垫块、少了土。
        settlement = new TravelSettlement(arrivedAt, arrived ? null : remaining, placed,
                placedBlocks.landingsDuringCurrentWalk(), placedBlocks.trapEscapesDuringCurrentWalk(), crossedWater);
    }

    /** 这次行程的结算；取消、空转这类没走到结算步的收场到这里按"停下、没到"补结一次，
     *  结果才带停在哪、差多远、垫了什么。还没迈出步子（没有身体位置）时无账可结。 */
    private TravelSettlement settlement() {
        if (settlement == null && lastFeet != null) {
            buildSettlement(lastFeet, false, remainingFromHere());
        }
        return settlement;
    }

    @Override
    protected ResultDetails details() {
        return settlement() == null ? ResultDetails.NONE : settlement();
    }

    @Override
    protected List<String> remaining() {
        TravelSettlement done = settlement();
        if (done != null && done.remaining() != null) {
            return List.of("停在 " + shortSpot(lastFeet)
                    + "，还没走到目的地：差 " + done.remaining().describe());
        }
        return List.of();
    }

    @Override
    protected String describePhase(Phase value) {
        return stopRequested ? "正在安全停下" : "在路上：去" + destination.describe();
    }

    private String shortSpot(BlockPos feet) {
        return feet == null ? "未知" : "(" + feet.getX() + ", " + feet.getY() + ", " + feet.getZ() + ")";
    }
}
