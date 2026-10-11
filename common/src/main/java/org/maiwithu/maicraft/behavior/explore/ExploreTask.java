// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.lighting.TorchesAlongTheWay;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.perception.DirectionWords;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

import it.unimi.dsi.fastutil.longs.LongSets;

/**
 * 探索任务：朝某个方向把外面的地形跑一遍。观察（逐列抽样）→ 选航点 → 走一段 → 再观察，
 * 直到核实目标、到达半径、走遍能走的外围，或如实受阻。
 *
 * <p>身体所在群系免视线闸直接记（到访）；远望样本必须过视线闸——已加载不等于看得见。
 * 途中发现写进世界记忆并发一条发现事件，角色不停不问。每段走行交给走到：跨水、上坡、
 * 垫料、换气、中途停下都是走到的行程保证，许可决定能不能挖垫，目标探测本身永不改方块。
 * 行进中达成目标立即取消在途段收尾，不走过头；越过硬边界截停失败，一次跳出去不冒充达成。
 */
public final class ExploreTask extends PhasedTask<ExploreTask.Phase> implements TorchesAlongTheWay {

    /** 探索的阶段：观察 → 选航点 → 走行段 → 到场复核。 */
    public enum Phase { OBSERVE, WAYPOINT, WALK, ARRIVE }

    // 数值初值按实机观感调整：观察圈与抽样网格、航点远近、打分射线、留意扫描的间隔。
    static final int WAYPOINT_MAX = 80;
    static final int WAYPOINT_MIN = 16;
    static final int RAY_COUNT = 17;
    /** 没给 max_seconds 时多久没有真实进展算卡住（刻）：观察与走行都有进展就续期。 */
    private static final long DEFAULT_STUCK_AFTER_TICKS = 20L * 90;

    private final ExploreObjective objective;
    private final ExploreObjective.Interests interests;
    private final TerrainPermit permit;
    private final ExplorationWorld world;
    private final WalkTo walks;
    private final WorldMemory memory;
    private final TaskEventSink events;
    private final RadiusVerdict radius;
    private final ExploreFindings findings = new ExploreFindings();
    private final LegHistory history = new LegHistory();
    private final ObservationSampling sampling = new ObservationSampling();
    private final BlockPos origin;

    private ExplorationSector sector;
    private final String startDimension;
    private LegState leg;

    /**
     * @param objective  探索目标与留意清单（能力做决定时已把参数校验、认不出的 ID 一次报全）
     * @param direction  扇形朝向（八向词，可空＝全向）；出发时按当时视角换算成罗盘方位并固定
     * @param maxSeconds 说好最多做多久（秒）；观察与走行有真实进展就续期，不超过 0 表示不设时限
     * @param permit     这次探索被允许动多少地形（走行能不能挖垫、被埋能不能上掘）
     */
    public ExploreTask(ExploreObjective objective, ExploreObjective.Interests interests,
            Target.Toward direction, int angleDegrees, int minDistance, int maxDistance,
            int maxSeconds, TerrainPermit permit, ExplorationWorld world, WalkTo walks,
            WorldMemory memory, TaskEventSink events) {
        super("探索", Phase.OBSERVE, new ProgressTracker(
                maxSeconds > 0 ? maxSeconds * 20L : DEFAULT_STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.objective = objective;
        this.interests = interests;
        this.permit = permit;
        this.world = world;
        this.walks = walks;
        this.memory = memory;
        this.events = events;
        this.radius = new RadiusVerdict(maxDistance);
        WorldPosition spot = world.currentSpot();
        this.origin = spot == null ? BlockPos.ZERO : new BlockPos(spot.x(), spot.y(), spot.z());
        this.startDimension = spot == null ? null : spot.dimension();
        // 方位在出发时按视角换算并固定，之后转头不改变搜索方向；没有朝向读数按正北折算。
        float facing = world.facingYaw() == null ? 0f : world.facingYaw();
        this.sector = direction == null
                ? ExplorationSector.allAround(minDistance, maxDistance)
                : ExplorationSector.fix(direction, facing, angleDegrees, minDistance, maxDistance);
        history.sectorNotes.add("扇形朝" + bearingWord(sector.centerBearing())
                + "，开口 " + (int) sector.angleDegrees() + "°");
    }

    @Override
    protected Action enter(Phase phase) {
        if (phase == Phase.OBSERVE) {
            sampling.reset();
            return null;
        }
        // 走行段进阶段时把目的地交给走到；观察与选航点只做判断，没有动作。
        if (phase == Phase.WALK) {
            leg.walk = startWalk();
            return leg.walk;
        }
        return null;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case OBSERVE -> tickObserve();
            case WAYPOINT -> tickWaypoint();
            case WALK -> tickWalk(context);
            case ARRIVE -> tickArrive();
        };
    }

    /** 观察推进：逐列抽样记群系与留意命中，发现目标候选就出发，看完了去选航点。 */
    private Next<Phase> tickObserve() {
        Next<Phase> ended = checkWorldThenSelf();
        if (ended != null) {
            return ended;
        }
        Next<Phase> dig = surfaceDigIfNeeded();
        if (dig != null) {
            return dig;
        }
        boolean observed = false;
        for (int[] column : sampling.nextBatch(selfColumn())) {
            ColumnSample sample = world.sampleColumn(column[0], column[1]);
            if (observeColumn(column[0], column[1], sample)) {
                observed = true;
            }
            Optional<Next<Phase>> candidate = candidateFrom(column[0], column[1], sample);
            if (candidate.isPresent()) {
                recordProgress("发现候选，出发去核实");
                return candidate.get();
            }
        }
        if (++sampling.sinceInterestScan >= ObservationSampling.INTEREST_SCAN_INTERVAL) {
            sampling.sinceInterestScan = 0;
            scanInterests();
        }
        if (observed) {
            recordProgress("观察到 " + sampling.uniqueColumns + " 列");
        }
        if (sampling.finished()) {
            return Next.go(Phase.WAYPOINT, "观察圈看完了，选航点接着往外走");
        }
        return Next.stay();
    }

    /** 记一列的观察：群系与留意命中按来源档落账；返回这一列有没有新看头。 */
    private boolean observeColumn(int x, int z, ColumnSample sample) {
        if (!sample.loaded()) {
            sampling.unloadedEdges++;
            return false;
        }
        boolean fresh = sampling.noteColumn(x, z, distanceFrom(x, z));
        WorldPosition spot = world.currentSpot();
        // 身体所在群系免视线闸直接记（到访）；远望样本必须过视线闸——已加载不等于看得见。
        if (spot.x() == x && spot.z() == z) {
            record(new ExploreFinding(ExploreFinding.Kind.BIOME, sample.biome(),
                    spot, MemoryOrigin.VISITED));
            return fresh;
        }
        if (!world.sightClear(x, sample.topY(), z)) {
            return fresh;
        }
        WorldPosition at = new WorldPosition(x, sample.topY(), z, startDimension);
        record(new ExploreFinding(ExploreFinding.Kind.BIOME, sample.biome(), at, MemoryOrigin.SEEN));
        if (sample.topFluid() && "minecraft:lava".equals(sample.topBlockId())) {
            record(new ExploreFinding(ExploreFinding.Kind.TERRAIN, "lava_pool", at, MemoryOrigin.SEEN));
        }
        for (String selector : interests.blocks()) {
            if (world.matches(sample.topBlockId(), List.of(selector))) {
                record(new ExploreFinding(ExploreFinding.Kind.BLOCK, selector, at, MemoryOrigin.SEEN));
            }
        }
        return fresh;
    }

    /** 一列样本是不是目标候选：在扇形内、过视线闸、过了起找距离才出发；扇形外只记不追。 */
    private Optional<Next<Phase>> candidateFrom(int x, int z, ColumnSample sample) {
        if (objective.target() != ExploreObjective.Kind.BIOME || !sample.loaded()
                || !world.matches(sample.biome(), objective.selectors())) {
            return Optional.empty();
        }
        double dx = x - origin.getX();
        double dz = z - origin.getZ();
        if (!sector.contains(dx, dz) || Math.sqrt(dx * dx + dz * dz) < sector.minDistance()) {
            return Optional.empty();
        }
        if (!world.sightClear(x, sample.topY(), z)) {
            return Optional.empty();
        }
        planLeg(new LegState(LegKind.TARGET, new BlockPos(x, 0, z)));
        return Optional.of(Next.go(Phase.WALK, "朝候选群系走，到了按脚下群系核实"));
    }

    /** 回露天被埋时的竖直上掘：许可动土且顶盖是实体方块才掘；顶盖是液体或非实体就跳过照走地面流程。 */
    private Next<Phase> surfaceDigIfNeeded() {
        if (objective.target() != ExploreObjective.Kind.SURFACE) {
            return null;
        }
        WorldPosition spot = world.currentSpot();
        BlockPos feet = new BlockPos(spot.x(), spot.y(), spot.z());
        if (!(OpenSky.judge(world.sampleColumn(feet.getX(), feet.getZ()), spot.y())
                instanceof OpenSky.Verdict.Buried buried)) {
            return null;
        }
        if (!permit.mayChangeTerrain()) {
            history.stopReason = ExploreDetails.StopReason.BLOCKED;
            return Next.fail(Problem.of(Problem.Kind.NEED_APPROVAL,
                    "被埋在（" + feet.getX() + ", " + feet.getY() + ", " + feet.getZ() + "），"
                            + "头顶这一列要到 " + buried.coverY() + " 才到顶，这次没有动土的许可",
                    "许可动土（change_blocks 提到 natural 或以上）就能沿本列竖直上掘破出"));
        }
        BlockPos top = new BlockPos(feet.getX(), buried.coverY() + 1, feet.getZ());
        if (!buried.coverSolid()) {
            // 顶盖是液体或非实体方块，上掘会灌进来或根本立不住：跳过上掘，记下原因照走地面流程。
            recordProgress("顶盖在 " + buried.coverY() + (buried.coverFluid() ? " 是液体" : " 不是实体方块")
                    + "，跳过上掘，照走地面流程");
            sampling.triedColumns.add(top.asLong());
            return null;
        }
        if (sampling.triedColumns.contains(top.asLong())) {
            return null;
        }
        sampling.triedColumns.add(top.asLong());
        recordAttempt("竖直上掘", "沿本列从脚下上掘到 " + buried.coverY());
        planLeg(new LegState(LegKind.DIG_UP, top));
        return Next.go(Phase.WALK, "被埋且许可动土，沿本列竖直上掘到顶盖再破出");
    }

    /** 选航点：扇形内沿射线打分或方形螺旋外扩，选出已加载的新落点；选不出就升级换向。 */
    private Next<Phase> tickWaypoint() {
        Next<Phase> ended = checkWorldThenSelf();
        if (ended != null) {
            return ended;
        }
        List<WaypointChooser.Candidate> candidates = sector.angleDegrees() >= 360
                ? spiralCandidates()
                : rayCandidates();
        Optional<WaypointChooser.Candidate> chosen =
                WaypointChooser.choose(candidates, sampling.triedColumns);
        if (chosen.isPresent()) {
            planLeg(new LegState(LegKind.WAYPOINT, chosen.get().column()));
            return Next.go(Phase.WALK, "朝选出的航点走一段");
        }
        // 这一圈选不出航点：换出发点或换方位，都不行就如实收场。
        return applyEscalation(LegEscalation.nextAfterFailedShift(
                sector.angleDegrees() < 360, history.originShifts, history.rotations));
    }

    /** 走行段：推进走到一刻，行进中达成目标或越界立即停段收尾，走完或失败按段结算。 */
    private Next<Phase> tickWalk(TickContext context) {
        Next<Phase> dimensionChanged = checkDimension();
        if (dimensionChanged != null) {
            return dimensionChanged;
        }
        if (leg.stopRequested) {
            return tickStopping(context);
        }
        SelfConclusion conclusion = assessSelf();
        if (conclusion != SelfConclusion.NONE) {
            // 行进中达成目标或越界：立即取消在途段，不走过头也不冒充无路。
            leg.pending = conclude(conclusion);
            leg.stopRequested = true;
            return Next.stay();
        }
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) {
            return Next.stay();
        }
        return settleLeg(status);
    }

    /** 收段中：等走到落到安全边界，再交出已经判定的结局。 */
    private Next<Phase> tickStopping(TickContext context) {
        boolean settled = leg.walk.stop();
        ActionStatus status = runAction(context);
        if (settled || leg.walk.report().state() == WalkReport.State.STOPPED
                || status instanceof ActionStatus.Failed) {
            return leg.pending;
        }
        return Next.stay();
    }

    /** 一段走完：目标候选与上掘到场复核，其余按成功回观察。 */
    private Next<Phase> settleLeg(ActionStatus status) {
        if (status instanceof ActionStatus.Failed failed) {
            return legFailed(failed.problem());
        }
        if (leg.kind == LegKind.TARGET || leg.kind == LegKind.DIG_UP) {
            return Next.go(Phase.ARRIVE, "走到候选跟前，按脚下事实复核");
        }
        history.record(true);
        markTried(leg.destination);
        return Next.go(Phase.OBSERVE, "航点到了，接着看这一片");
    }

    /** 到场复核：按脚下事实复查判据，不过就把候选记入拒绝集回观察，不反复尝试。 */
    private Next<Phase> tickArrive() {
        Next<Phase> dimensionChanged = checkDimension();
        if (dimensionChanged != null) {
            return dimensionChanged;
        }
        SelfConclusion conclusion = assessSelf();
        history.record(conclusion != SelfConclusion.NONE);
        if (conclusion == SelfConclusion.NONE) {
            markTried(leg.destination);
            recordProgress("候选复核不过，记入拒绝集");
            return Next.go(Phase.OBSERVE, "复核不过：脚下事实不是目标，回观察换别的候选");
        }
        return conclude(conclusion);
    }

    /** 段失败：记一段失败，换向升级或回观察。 */
    private Next<Phase> legFailed(Problem problem) {
        boolean shiftLeg = leg.kind == LegKind.SHIFT_ORIGIN;
        history.record(false);
        markTried(leg.destination);
        recordAttempt(describeLeg(), "没走到：" + problem.message());
        boolean rotatable = sector.angleDegrees() < 360;
        if (shiftLeg) {
            // 换出发点这段自身失败就直接走下一级，不在坏出发点周围再烧一整窗。
            return applyEscalation(LegEscalation.nextAfterFailedShift(
                    rotatable, history.originShifts, history.rotations));
        }
        return applyEscalation(LegEscalation.decide(
                history.windowSnapshot(), rotatable, history.originShifts, history.rotations));
    }

    /** 换向升级的去向落地：回观察、换出发点、轮换扇形，或如实受阻。 */
    private Next<Phase> applyEscalation(LegEscalation.Level level) {
        return switch (level) {
            case CARRY_ON -> Next.go(Phase.OBSERVE, "回观察接着找候选");
            case SHIFT_ORIGIN -> planShift();
            case ROTATE_SECTOR -> {
                sector = sector.rotatedClockwise();
                history.rotations++;
                history.sectorNotes.add("扇形轮换到" + bearingWord(sector.centerBearing()));
                recordAttempt("换方位", "扇形顺时针转 45°，朝" + bearingWord(sector.centerBearing()));
                yield Next.go(Phase.WAYPOINT, "换方位再选航点");
            }
            case BLOCKED -> settleBlocked();
        };
    }

    /** 换出发点：垂直于当前方位 ±90°、约 32 格，截已加载路段；这段自身失败直接走下一级。 */
    private Next<Phase> planShift() {
        boolean rightward = history.originShifts % 2 == 0;
        history.originShifts++;
        double bearing = sector.centerBearing() + (rightward ? 90 : -90);
        BlockPos from = selfColumn();
        for (int dist = LegEscalation.ORIGIN_SHIFT_DISTANCE; dist >= ObservationSampling.COLUMN_STEP; dist -= ObservationSampling.COLUMN_STEP) {
            int x = from.getX() + (int) Math.round(Math.sin(Math.toRadians(bearing)) * dist);
            int z = from.getZ() - (int) Math.round(Math.cos(Math.toRadians(bearing)) * dist);
            if (!world.sampleColumn(x, z).loaded()) {
                continue;
            }
            history.sectorNotes.add("换出发点，向" + bearingWord(bearing) + "横移 " + dist + " 格");
            recordAttempt("换出发点", "向" + bearingWord(bearing) + "横移 " + dist + " 格");
            planLeg(new LegState(LegKind.SHIFT_ORIGIN, new BlockPos(x, 0, z)));
            return Next.go(Phase.WALK, "先横移到新的出发点");
        }
        // 32 格内没有已加载的落点：横移无从截起，直接走下一级。
        return applyEscalation(LegEscalation.nextAfterFailedShift(
                sector.angleDegrees() < 360, history.originShifts, history.rotations));
    }

    /** 走不出去时的收场：自由跑图是走遍外围，定向探索看有没有见过候选。 */
    private Next<Phase> settleBlocked() {
        history.stopReason = ExploreDetails.StopReason.BLOCKED;
        if (objective.target() == ExploreObjective.Kind.SURVEY) {
            if (history.reachedAnyWaypoint) {
                history.stopReason = ExploreDetails.StopReason.FRONTIERS_EXHAUSTED;
                return Next.done(done("探索：能走到的外围都走过了，收工"));
            }
            return Next.fail(Problem.of(Problem.Kind.STUCK,
                    blockedSummary() + "；没到过任何航点，从出发点就走不出去",
                    "先许可动土或换一个出发点下达"));
        }
        if (findings.hasAny(ExploreFinding.Kind.BIOME)) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE, blockedSummary(),
                    "远距换区再下达，或许可动土放宽走行的挖垫"));
        }
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                blockedSummary() + "；只搜了真实加载的地形，查过没有不等于世界里没有",
                "换个方向或加大 max_distance 再下达"));
    }

    /** 换维度如实结束，旧范围作废；角色位置判定照做。 */
    private Next<Phase> checkWorldThenSelf() {
        Next<Phase> dimensionChanged = checkDimension();
        if (dimensionChanged != null) {
            return dimensionChanged;
        }
        return conclude(assessSelf());
    }

    private Next<Phase> checkDimension() {
        WorldPosition spot = world.currentSpot();
        if (spot == null) {
            return Next.stay();
        }
        if (!spot.dimension().equals(startDimension)) {
            return Next.done(TaskResult.cancelled("探索：中途换到别的维度（" + spot.dimension()
                    + "），这次探索作废，旧范围不跨维度"));
        }
        return null;
    }

    /** 按角色位置的判定给出收场：目标核实、到达半径、越界截停；没有结论给 null 继续走。 */
    private Next<Phase> conclude(SelfConclusion conclusion) {
        if (conclusion == SelfConclusion.NONE) {
            return null;
        }
        if (conclusion == SelfConclusion.TARGET_CONFIRMED) {
            history.reached = world.currentSpot();
            if (objective.target() == ExploreObjective.Kind.BIOME) {
                ColumnSample here = world.sampleColumn(history.reached.x(), history.reached.z());
                record(new ExploreFinding(ExploreFinding.Kind.BIOME, here.biome(),
                        history.reached, MemoryOrigin.VISITED));
            }
            return Next.done(TaskResult.done("探索：核实通过，站实了（" + history.reached.x()
                    + ", " + history.reached.y() + ", " + history.reached.z() + "）"));
        }
        if (conclusion == SelfConclusion.LEFT_BOUND) {
            history.stopReason = ExploreDetails.StopReason.LEFT_BOUND;
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "身体越出了说好的探索半径（" + (int) radius.maxDistance() + " 格），已在边界截停",
                    "max_distance 调大后重新下达"));
        }
        // 到达半径：自由跑图收工；定向探索等于说好的范围查完了，没核实就如实说。
        history.stopReason = ExploreDetails.StopReason.RADIUS_REACHED;
        if (objective.target() == ExploreObjective.Kind.SURVEY) {
            return Next.done(done("探索：到达探索半径的判定带，收工"));
        }
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                "到达了探索半径的边界（" + (int) radius.maxDistance() + " 格），没有核实到"
                        + describeTarget() + "；只搜了真实加载的地形，查过没有不等于世界里没有",
                "加大 max_distance，或先出行到更远处再下达探索"));
    }

    /** 每刻的角色位置判定：半径收尾与目标复核都读脚下事实。 */
    private SelfConclusion assessSelf() {
        WorldPosition spot = world.currentSpot();
        if (spot == null) {
            return SelfConclusion.NONE;
        }
        double distance = distanceFrom(spot.x(), spot.z());
        sampling.noteSelfDistance(distance);
        switch (radius.judge(distance)) {
            case LEFT_BOUND -> {
                return SelfConclusion.LEFT_BOUND;
            }
            case REACHED -> {
                return SelfConclusion.RADIUS_REACHED;
            }
            case WITHIN -> { }
        }
        return switch (objective.target()) {
            case SURVEY -> SelfConclusion.NONE;
            case BIOME -> biomeUnderFeet(spot, distance)
                    ? SelfConclusion.TARGET_CONFIRMED : SelfConclusion.NONE;
            case FLAT_GROUND -> LevelGroundCheck.holds(
                    world.supportedAt(spot.x(), spot.y(), spot.z()),
                    world.supportedAt(spot.x(), spot.y(), spot.z() - 1),
                    world.supportedAt(spot.x() + 1, spot.y(), spot.z()),
                    world.supportedAt(spot.x(), spot.y(), spot.z() + 1),
                    world.supportedAt(spot.x() - 1, spot.y(), spot.z()))
                    ? SelfConclusion.TARGET_CONFIRMED : SelfConclusion.NONE;
            case SURFACE -> OpenSky.judge(world.sampleColumn(spot.x(), spot.z()), spot.y())
                    instanceof OpenSky.Verdict.Open
                    ? SelfConclusion.TARGET_CONFIRMED : SelfConclusion.NONE;
        };
    }

    /** 脚下群系是不是目标：在扇形内、过了起找距离、群系对得上。 */
    private boolean biomeUnderFeet(WorldPosition spot, double distance) {
        ColumnSample here = world.sampleColumn(spot.x(), spot.z());
        if (!here.loaded() || !world.matches(here.biome(), objective.selectors())) {
            return false;
        }
        double dx = spot.x() - origin.getX();
        double dz = spot.z() - origin.getZ();
        return sector.contains(dx, dz) && distance >= sector.minDistance();
    }

    /** 扇形内沿射线找落点：每条射线从最远往回找第一个已加载且不越判定带的列。 */
    private List<WaypointChooser.Candidate> rayCandidates() {
        BlockPos from = selfColumn();
        double half = sector.angleDegrees() / 2;
        List<WaypointChooser.Candidate> candidates = new ArrayList<>();
        for (int ray = 0; ray < RAY_COUNT; ray++) {
            double bearing = sector.centerBearing() - half
                    + (sector.angleDegrees() * ray / (RAY_COUNT - 1));
            for (int dist = WAYPOINT_MAX; dist >= WAYPOINT_MIN; dist -= ObservationSampling.COLUMN_STEP) {
                int x = from.getX() + (int) Math.round(Math.sin(Math.toRadians(bearing)) * dist);
                int z = from.getZ() - (int) Math.round(Math.cos(Math.toRadians(bearing)) * dist);
                ColumnSample sample = world.sampleColumn(x, z);
                if (!sample.loaded() || distanceFrom(x, z) > radius.waypointBound()) {
                    continue;
                }
                candidates.add(toCandidate(new BlockPos(x, 0, z), sample, from, dist));
                break;
            }
        }
        return candidates;
    }

    /** 无方向时方形螺旋外扩：沿途截取最远 80 格的已加载路段，不越判定带。 */
    private List<WaypointChooser.Candidate> spiralCandidates() {
        BlockPos from = selfColumn();
        List<WaypointChooser.Candidate> candidates = new ArrayList<>();
        for (int ring = ObservationSampling.COLUMN_STEP; ring <= WAYPOINT_MAX && candidates.size() < RAY_COUNT;
                ring += ObservationSampling.COLUMN_STEP) {
            for (int[] offset : squareRing(ring)) {
                int x = from.getX() + offset[0];
                int z = from.getZ() + offset[1];
                ColumnSample sample = world.sampleColumn(x, z);
                if (!sample.loaded() || distanceFrom(x, z) > radius.waypointBound()) {
                    continue;
                }
                candidates.add(toCandidate(new BlockPos(x, 0, z), sample, from,
                        Math.sqrt((double) offset[0] * offset[0] + offset[1] * offset[1])));
            }
        }
        return candidates;
    }

    /** 航点候选：纯水面不做落点；要不要跨水沿直线探一眼，渡不渡由走到的跨水保证结算。 */
    private WaypointChooser.Candidate toCandidate(BlockPos column, ColumnSample sample,
            BlockPos from, double rayDistance) {
        double dx = column.getX() - origin.getX();
        double dz = column.getZ() - origin.getZ();
        return new WaypointChooser.Candidate(column, sample.topFluid(),
                crossesWater(from, column), Math.sqrt(dx * dx + dz * dz), rayDistance,
                sector.eccentricity(dx, dz));
    }

    /** 从出发点到落点的直线上有没有顶盖是液体的列：穿水候选整体让位，全穿水（身处岛屿）仍选。 */
    private boolean crossesWater(BlockPos from, BlockPos column) {
        int steps = (int) Math.max(1, Math.floor(Math.hypot(
                column.getX() - from.getX(), column.getZ() - from.getZ()) / (double) ObservationSampling.COLUMN_STEP));
        for (int step = 1; step < steps; step++) {
            int x = from.getX() + (column.getX() - from.getX()) * step / steps;
            int z = from.getZ() + (column.getZ() - from.getZ()) * step / steps;
            ColumnSample sample = world.sampleColumn(x, z);
            if (sample.loaded() && sample.topFluid()) {
                return true;
            }
        }
        return false;
    }

    /** 回字形一圈上的采样点，按抽样步长取。 */
    private static List<int[]> squareRing(int ring) {
        List<int[]> cells = new ArrayList<>();
        for (int edge = -ring; edge <= ring; edge += ObservationSampling.COLUMN_STEP) {
            cells.add(new int[]{edge, -ring});
            cells.add(new int[]{edge, ring});
            cells.add(new int[]{-ring, edge});
            cells.add(new int[]{ring, edge});
        }
        return cells;
    }

    /** 留意清单的结构线索与实体：按间隔扫一轮；走到结构跟前的线索升级成到访，不降级。 */
    private void scanInterests() {
        for (ExplorationWorld.Spotted spotted : world.structureClues(interests.structures())) {
            if (world.sightClear(spotted.position().x(), spotted.position().y(), spotted.position().z())) {
                record(new ExploreFinding(ExploreFinding.Kind.STRUCTURE_CLUE, spotted.id(),
                        spotted.position(), MemoryOrigin.SEEN));
            }
        }
        for (ExplorationWorld.Spotted spotted : world.entitySightings(interests.entities())) {
            record(new ExploreFinding(ExploreFinding.Kind.ENTITY, spotted.id(),
                    spotted.position(), MemoryOrigin.SEEN));
        }
        WorldPosition spot = world.currentSpot();
        for (ExploreFinding finding : findings.all()) {
            if (finding.kind() != ExploreFinding.Kind.STRUCTURE_CLUE
                    || finding.origin() != MemoryOrigin.SEEN) {
                continue;
            }
            StructureClues.resolve(finding.targetId()).ifPresent(resolved -> {
                int reach = resolved.profile().clusterRadius();
                if (Math.abs(spot.x() - finding.position().x()) <= reach
                        && Math.abs(spot.z() - finding.position().z()) <= reach) {
                    record(new ExploreFinding(ExploreFinding.Kind.STRUCTURE_CLUE, finding.targetId(),
                            finding.position(), MemoryOrigin.VISITED));
                }
            });
        }
    }

    /** 记一条发现：落进世界记忆，新发现发一条事件；角色不停不问，照常跑图。 */
    private void record(ExploreFinding finding) {
        if (finding.targetId() == null) {
            return;
        }
        ExploreFindings.Result result = findings.record(finding);
        if (result.entry() == ExploreFindings.Entry.KNOWN) {
            return;
        }
        List<String> contents = List.of(result.effective().targetId());
        if (result.effective().origin() == MemoryOrigin.VISITED) {
            memory.rememberSiteVisited(result.effective().position(), contents, Instant.now());
        } else {
            memory.rememberSite(result.effective().position(), contents, Instant.now());
        }
        if (result.entry() == ExploreFindings.Entry.NEW) {
            events.publish(TaskEvent.Kind.DISCOVERED, describe(result.effective()));
        }
    }

    /** 发现的一句话：是什么、在身体哪个方向多远。 */
    private String describe(ExploreFinding finding) {
        WorldPosition spot = world.currentSpot();
        String word = switch (finding.kind()) {
            case BIOME -> "群系";
            case TERRAIN -> "地形要素";
            case STRUCTURE_CLUE -> "结构线索";
            case BLOCK -> "留意方块";
            case ENTITY -> "留意实体";
        };
        if (spot == null) {
            return "发现" + word + " " + finding.targetId();
        }
        int dx = finding.position().x() - spot.x();
        int dz = finding.position().z() - spot.z();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + dz * dz));
        String place = distance <= 2 ? "脚下" : DirectionWords.compassOf(dx, dz) + " " + distance + " 格";
        return "发现" + word + " " + finding.targetId() + "（" + place + "）";
    }

    /** 把下一件事准备成一段走行；走行交给走到，许可随段带上。 */
    private void planLeg(LegState planned) {
        leg = planned;
    }

    /** 把目的柱交给走到：目标探测本身永不改方块，能不能挖垫由许可决定。 */
    private WalkRun startWalk() {
        return walks.start(new GoalCompiler.Compiled(
                NavGoal.column(leg.destination.getX(), leg.destination.getZ()), LongSets.emptySet()),
                permit);
    }

    private void markTried(BlockPos column) {
        sampling.triedColumns.add(column.asLong());
    }

    private BlockPos selfColumn() {
        WorldPosition spot = world.currentSpot();
        return spot == null ? origin : new BlockPos(spot.x(), spot.y(), spot.z());
    }

    private double distanceFrom(int x, int z) {
        double dx = x - origin.getX();
        double dz = z - origin.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private String bearingWord(double bearing) {
        return DirectionWords.compassOf(Math.sin(Math.toRadians(bearing)), -Math.cos(Math.toRadians(bearing)));
    }

    private String describeTarget() {
        return switch (objective.target()) {
            case BIOME -> "目标群系 " + String.join("、", objective.selectors());
            case FLAT_GROUND -> "一块能站的平台";
            case SURFACE -> "露天的地面";
            case SURVEY -> "外围";
        };
    }

    private String describeLeg() {
        return switch (leg.kind) {
            case WAYPOINT -> "走到航点（" + leg.destination.getX() + ", " + leg.destination.getZ() + "）";
            case TARGET -> "走到目标候选（" + leg.destination.getX() + ", " + leg.destination.getZ() + "）";
            case SHIFT_ORIGIN -> "横移换出发点";
            case DIG_UP -> "沿本列竖直上掘";
        };
    }

    private String blockedSummary() {
        List<Boolean> window = history.windowSnapshot();
        long recent = window.stream().filter(succeeded -> !succeeded).count();
        return "探索受阻：最近 " + window.size() + " 段失败 " + recent + " 段，共走了 "
                + history.total + " 段失败 " + history.failures + " 段；卡在"
                + bearingWord(sector.centerBearing()) + "方向；"
                + String.join("，", history.sectorNotes) + "。受阻不是目标不存在的证据";
    }

    private TaskResult done(String summary) {
        return TaskResult.done(summary);
    }

    @Override
    protected ResultDetails details() {
        List<ExploreDetails.Item> items = findings.all().stream()
                .map(finding -> new ExploreDetails.Item(finding.kind(), finding.targetId(),
                        finding.position(), finding.origin()))
                .toList();
        return new ExploreDetails(items, history.reached, history.stopReason,
                new ExploreDetails.Coverage(sampling.uniqueColumns, (int) sampling.maxSelfDistance,
                        (int) sampling.maxSampleDistance, sampling.unloadedEdges),
                new WorldPosition(origin.getX(), origin.getY(), origin.getZ(), startDimension),
                List.copyOf(history.sectorNotes),
                new ExploreDetails.Legs(history.total, history.failures));
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case OBSERVE -> "观察这一片";
            case WAYPOINT -> "选下一个航点";
            case WALK -> "在路上";
            case ARRIVE -> "到场复核";
        };
    }

    /** 身体每刻判定的结论。 */
    private enum SelfConclusion { NONE, TARGET_CONFIRMED, RADIUS_REACHED, LEFT_BOUND }

    /** 走行段的种类：航点、目标候选、换出发点、竖直上掘。 */
    private enum LegKind { WAYPOINT, TARGET, SHIFT_ORIGIN, DIG_UP }

    /** 一段在途的走行：去哪、走到运行、有没有在收段，以及收段后要交的结局。 */
    private static final class LegState {
        final LegKind kind;
        final BlockPos destination;
        WalkRun walk;
        boolean stopRequested;
        Next<Phase> pending;

        LegState(LegKind kind, BlockPos destination) {
            this.kind = kind;
            this.destination = destination.immutable();
        }
    }

}
