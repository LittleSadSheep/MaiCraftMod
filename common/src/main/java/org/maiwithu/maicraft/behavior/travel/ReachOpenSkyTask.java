// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.acquire.DigsStairsUp;
import org.maiwithu.maicraft.behavior.lighting.TorchesAlongTheWay;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回露天：人在地下、屋里、崖檐下时回到头顶见天的地面上。
 *
 * <p>先看一眼：旁边几步就见天就直接走出去；不行、许可又能动土，就斜着往上挖楼梯；楼梯挖不下去或不许动土，
 * 再找远处的露天落脚处交给走到去找路，最多换三回。脚下露天（忽略树叶的最高遮挡物就在脚底）才算到。
 * 走路那几段垫的方块、挖开的方块、路上捡的东西和楼梯挖掉的方块都如实进结果；都不行就说清找了多大范围。
 */
public final class ReachOpenSkyTask extends PhasedTask<ReachOpenSkyTask.Phase> implements TorchesAlongTheWay {

    /** 回露天的阶段：看一眼该怎么出去 → 走出去，或挖楼梯上去。 */
    public enum Phase { LOOK, WALK, CLIMB }

    /** 连续半分钟没有真实进展（挖掉一格、上一级、离露天落脚处更近）算卡住。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;
    /** 找路走到远处露天落脚处最多换几回：走到了却没见天、走不到，都换一回。 */
    static final int FAR_WALKS = 3;

    private final TerrainPermit permit;
    private final Permissions permissions;
    private final TravelWorldView world;
    private final WalkTo walks;
    private final DigsStairsUp stairs;
    private final ReadsPlacedBlocks placedBlocks;

    private boolean triedNear;
    private boolean triedClimb;
    private int farWalks;
    private boolean climbed;
    private final Set<BlockPos> triedSpots = new HashSet<>();
    private List<BlockPos> spots = List.of();
    private boolean walkingFar;
    private int farSpotsSeen;
    private WalkRun walk;
    private Problem climbStopped;
    private BlockPos lastFeet;
    private double bestGap = Double.MAX_VALUE;
    private boolean crossedWater;
    private final List<String> placed = new ArrayList<>();
    private final List<String> landings = new ArrayList<>();
    private final List<String> trapEscapes = new ArrayList<>();
    private WalkRun recordedFor;
    private int recordedPlacements;
    private int recordedBreaks;
    private Map<String, Integer> bagLastTick;
    private TravelSettlement settlement;

    /**
     * @param maxSeconds  最多做多久（秒）；不超过 0 表示不设时限
     * @param permissions 这次的许可：能不能挖楼梯、走路能动多少地形都按它
     * @param stairs      往上挖楼梯；没接上为 null，只走路
     */
    public ReachOpenSkyTask(int maxSeconds, Permissions permissions, TravelWorldView world, WalkTo walks,
            DigsStairsUp stairs, ReadsPlacedBlocks placedBlocks) {
        super("回露天", Phase.LOOK, new ProgressTracker(STUCK_AFTER_TICKS,
                maxSeconds > 0 ? maxSeconds * 20L : Long.MAX_VALUE));
        this.permissions = permissions;
        this.permit = TerrainPermit.of(permissions);
        this.world = world;
        this.walks = walks;
        this.stairs = stairs;
        this.placedBlocks = placedBlocks;
    }

    @Override
    protected Action enter(Phase phase) {
        return switch (phase) {
            case LOOK -> null;
            case WALK -> startWalk();
            case CLIMB -> stairs.digUp(feet -> OpenSkySpots.underOpenSky(world, feet), permissions, records());
        };
    }

    // 交给走到：这几处露天落脚处哪一处走得到就去哪一处，走到按这次的许可动地形。
    private WalkRun startWalk() {
        List<NavGoal> goals = spots.stream().map(NavGoal::exact).toList();
        walk = walks.start(new GoalCompiler.Compiled(NavGoal.composite(goals), LongSets.emptySet()), permit);
        return walk;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        noteBackpack(context);
        return switch (phase) {
            case LOOK -> look();
            case WALK -> tickWalk(context);
            case CLIMB -> tickClimb(context);
        };
    }

    // 看一眼：露天了就到了；旁边几步见天先走出去；许可能动土就挖楼梯；再不行找路走到远处的露天落脚处；都试过如实收场。
    private Next<Phase> look() {
        BlockPos feet = feetNow();
        if (feet == null) return Next.stay();
        lastFeet = feet;
        if (OpenSkySpots.underOpenSky(world, feet)) return arrived();
        if (!triedNear) {
            triedNear = true;
            spots = OpenSkySpots.near(world, feet);
            if (!spots.isEmpty()) {
                walkingFar = false;
                return Next.go(Phase.WALK, "旁边几步就见天，先走出去");
            }
        }
        if (!triedClimb && stairs != null && mayDig()) {
            triedClimb = true;
            return Next.go(Phase.CLIMB, "许可动土，挖楼梯上去");
        }
        if (farWalks < FAR_WALKS) {
            spots = OpenSkySpots.far(world, feet, triedSpots);
            if (farWalks == 0) farSpotsSeen = spots.size();
            if (!spots.isEmpty()) {
                farWalks++;
                walkingFar = true;
                return Next.go(Phase.WALK, "找路走到远处的露天落脚处");
            }
        }
        return Next.fail(giveUp());
    }

    private Next<Phase> tickWalk(TickContext context) {
        ActionStatus status = runAction(context);
        WalkReport report = walk.report();
        observeWalk(report);
        // 生存需求打断时请求过停；停稳了就从原地重新上路。
        if (report.state() == WalkReport.State.STOPPED) return Next.go(Phase.WALK, "上一段走到已停稳，从原地接着走");
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> afterWalk(null);
            case ActionStatus.Failed failed -> afterWalk(failed.problem());
        };
    }

    // 一段走完：脚下露天就到了；没见天或没走到，记一笔，交给下一眼换办法。
    private Next<Phase> afterWalk(Problem failure) {
        settleWalkFacts();
        BlockPos feet = feetNow();
        if (feet != null) lastFeet = feet;
        if (failure == null && lastFeet != null && OpenSkySpots.underOpenSky(world, lastFeet)) return arrived();
        String tried = walkingFar ? "找路走到远处的露天落脚处" : "走到旁边见天的地方";
        if (failure != null) {
            recordAttempt(tried, failure.message());
        } else {
            recordAttempt(tried, "走到了 " + shortSpot(lastFeet) + "，头顶还没见天");
        }
        triedSpots.addAll(spots);
        return Next.go(Phase.LOOK, "这一段没出去，换个办法");
    }

    private Next<Phase> tickClimb(TickContext context) {
        ActionStatus status = runAction(context);
        BlockPos feet = feetNow();
        if (feet != null) noteFeet(feet);
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                climbed = true;
                yield feet != null && OpenSkySpots.underOpenSky(world, feet) ? arrived()
                        : Next.go(Phase.LOOK, "楼梯挖完了，再看一眼");
            }
            case ActionStatus.Failed failed -> {
                // 楼梯挖不下去（挨着水、头顶压着沙砾、几个方向都挖不了）：如实记下，换找路走出去。
                climbStopped = failed.problem();
                recordAttempt("往上挖楼梯", failed.problem().message());
                yield Next.go(Phase.LOOK, "楼梯挖不下去，找路走出去");
            }
        };
    }

    private Next<Phase> arrived() {
        BlockPos feet = lastFeet;
        WorldPosition spot = world.currentSpot();
        WorldPosition at = new WorldPosition(feet.getX(), feet.getY(), feet.getZ(), spot == null ? null : spot.dimension());
        settlement = new TravelSettlement(at, null, placed, landings, trapEscapes, crossedWater);
        return Next.done(TaskResult.done("回露天：站在露天的地上了，实测位置 " + shortSpot(feet)
                + (climbed ? "，挖楼梯上来的" : "")));
    }

    // 都试过了：说清找了多大范围、看到几处露天落脚处、走了几回、楼梯停没停；每一回为什么不行在 attempts 里，
    // 这里只说结论，不把下一层的说明再抄一遍。没有动土许可时说放开许可就能挖上去。
    private Problem giveUp() {
        StringBuilder message = new StringBuilder("回露天：在 ").append(shortSpot(lastFeet)).append(" 没能出去。");
        message.append("找了水平 ").append(OpenSkySpots.NEAR_RADIUS).append(" 格内能走几步到的、")
                .append(OpenSkySpots.FAR_RADIUS).append(" 格内已加载区的露天落脚处，远处看到 ")
                .append(farSpotsSeen).append(" 处");
        if (farWalks > 0) message.append("，找路走了 ").append(farWalks).append(" 回都没出去");
        if (climbStopped != null) message.append("；楼梯挖到一半停了");
        message.append("。没看到不等于那里没有，没加载的地方还不知道");
        String suggestion = mayDig()
                ? "换个地方再试，或先往开阔的方向走一段"
                : "许可动土（change_blocks=natural）就能挖楼梯上去";
        return Problem.of(Problem.Kind.UNREACHABLE, message.toString(), suggestion);
    }

    // 挖楼梯要能挖天然方块：只许垫临时方块（temporary）不算。
    private boolean mayDig() {
        return permissions.changeBlocks() == Permissions.BlockChanges.NATURAL
                || permissions.changeBlocks() == Permissions.BlockChanges.ANY;
    }

    // 走路这一刻的现场：脚在哪、泅渡过没有、垫和挖的方块逐刻进变化；离最近的露天落脚处更近才算前进。
    private void observeWalk(WalkReport report) {
        crossedWater |= report.crossedWater();
        if (report.feet() != null) noteFeet(report.feet());
        recordWalkChanges();
    }

    private void noteFeet(BlockPos feet) {
        lastFeet = feet;
        double gap = Double.MAX_VALUE;
        for (BlockPos spot : spots) gap = Math.min(gap, Math.sqrt(spot.distSqr(feet)));
        if (gap < bestGap) {
            bestGap = gap;
            recordProgress("离露天落脚处还剩 " + (int) Math.ceil(gap) + " 格");
        }
    }

    // 走到路上垫的方块、开路挖掉的方块逐刻记进变化：中途取消也已经记下。
    private void recordWalkChanges() {
        if (recordedFor != walk) {
            recordedFor = walk;
            recordedPlacements = 0;
            recordedBreaks = 0;
        }
        List<BlockPos> laid = placedBlocks.placedDuringCurrentWalk();
        for (int i = recordedPlacements; i < laid.size(); i++) {
            recordChange(new Change(Change.Kind.BLOCK_PLACED, laid.get(i).toShortString(), 1, "垫路用的临时方块，没有自动收回"));
        }
        recordedPlacements = laid.size();
        List<String> broken = placedBlocks.brokenDuringCurrentWalk();
        for (int i = recordedBreaks; i < broken.size(); i++) {
            recordChange(new Change(Change.Kind.BLOCK_BROKEN, broken.get(i), 1, "开路挖掉的"));
        }
        recordedBreaks = broken.size();
    }

    // 一段走路结束：垫过的格、落地救援与卡坑脱困的经过并进这一趟的细节。
    private void settleWalkFacts() {
        recordWalkChanges();
        placedBlocks.placedDuringCurrentWalk().forEach(cell -> placed.add(cell.toShortString()));
        landings.addAll(placedBlocks.landingsDuringCurrentWalk());
        trapEscapes.addAll(placedBlocks.trapEscapesDuringCurrentWalk());
    }

    // 背包每刻比一次：挖楼梯挖出来的石头、路上捡的进变化，用掉的（垫路、吃喝）也进。
    private void noteBackpack(TickContext context) {
        if (context.player() == null || context.player().backpack() == null) return;
        Map<String, Integer> now = new HashMap<>();
        for (BackpackStack stack : context.player().backpack().stacks()) now.merge(stack.itemId(), stack.count(), Integer::sum);
        if (bagLastTick != null) TravelTask.bagChanges(bagLastTick, now).forEach(this::recordChange);
        bagLastTick = now;
    }

    private BlockPos feetNow() {
        WorldPosition spot = world.currentSpot();
        return spot == null ? null : new BlockPos(spot.x(), spot.y(), spot.z());
    }

    @Override
    protected ResultDetails details() {
        if (settlement == null && lastFeet != null) {
            settlement = new TravelSettlement(null, null, placed, landings, trapEscapes, crossedWater);
        }
        return settlement == null ? ResultDetails.NONE : settlement;
    }

    @Override
    protected List<String> remaining() {
        return settlement != null && settlement.arrivedAt() != null || lastFeet == null
                ? List.of() : List.of("停在 " + shortSpot(lastFeet) + "，头顶还没见天");
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case LOOK -> "回露天：看怎么出去";
            case WALK -> walkingFar ? "回露天：找路走出去" : "回露天：走到旁边见天的地方";
            case CLIMB -> "回露天：往上挖楼梯";
        };
    }

    private static String shortSpot(BlockPos feet) {
        return feet == null ? "未知" : "(" + feet.getX() + ", " + feet.getY() + ", " + feet.getZ() + ")";
    }

    /** 本来就在挪地方：被临时任务带离原地后从新地点接着出去。 */
    @Override
    public boolean resumesAnywhere() {
        return true;
    }
}
