// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.explore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.scan.SpiralWalker;
import org.maiwithu.maicraft.core.scan.ObservationVisibility;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.task.CompassUtil;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.structure.StructureEvidenceProfiles;
import org.maiwithu.maicraft.core.task.structure.StructureSightingScanner;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 先观察已加载地形，再前往内部边界、加载周围地形并重复，直到角色亲自核实语义目标。全程不读取种子或生成器，也不调用区块加载接口。
 */
public final class SemanticExploreCompanionTask
        extends AbstractCompanionTask<SemanticExploreTaskRecord> {

    private enum Stage { OBSERVE, TRAVEL_TARGET, VERIFY_TARGET, TRAVEL_WAYPOINT, TRAVEL_RELOCATE }
    // 包内可见：地表列分类的判定需要静态直测，LAVA 只进地形要素记忆，移动消费仍只有 LAND/WATER。
    enum SurfaceKind { LAND, WATER, LAVA, UNKNOWN }

    private record ColumnOffset(int dx, int dz, int distanceSquared) {}
    // 包内可见：顶块流体分类与列内深度数法由回归直接断言。
    record FluidColumn(SurfaceKind kind, int depth) {}
    private record SurfaceInfo(
            SurfaceKind kind, BlockPos approach, BlockPos evidence, int waterDepth) {}
    private record TargetCandidate(
            BlockPos approach, BlockPos evidence, String description) {}
    private static final int OBSERVATION_RADIUS = 112;
    private static final int BIOME_OBSERVATION_STEP = 4;
    private static final int BIOME_Y_STEP = 32;
    private static final int BIOME_SAMPLES_PER_TICK = 192;
    private static final int WAYPOINT_GRID = 64;
    private static final int MAX_LEG_DISTANCE = 80;
    /** 换锚的小半径：沿垂直方位横移这段距离再继续航点行程，不远离原探索扇区。 */
    private static final double RELOCATION_HOP_BLOCKS = 32.0;
    private static final int SCOPE_TOLERANCE = 8;
    private static final int MAX_SPIRAL_PROBES = 10_000;
    /**
     * 观察驻留为在途扫描轮保留的额外刻数上限：真实世界的冷索引要给观测半径内全部已加载区块段建条目
     * （半径 112 时约五千段，共享每刻 2ms 墙钟），加上可见性与逐画像分组相，一轮可超出两百刻；
     * 驻留只在扫描轮确实在途时消耗，结算即止，超限按未覆盖处理不无限等待。
     */
    private static final int MAX_SCAN_DWELL_TICKS = 400;

    private static final List<ColumnOffset> BIOME_OBSERVATION_OFFSETS =
            buildOffsets(BIOME_OBSERVATION_STEP);

    private Predicate<Holder<Biome>> biomeMatch;
    private String canonicalTarget;
    private String inputFailure;
    private BlockPos origin;
    private ExplorationSector.Area sector;
    private boolean survey;
    private String surveyStopReason;
    private ClientExplorationMemory memory;
    private WaterCrossingProbe waterProbe;
    private ClientLevel startingLevel;
    private Stage stage;

    private int observationColumn;
    private int biomeYIndex;
    private int scanDwellTicks;
    private int observationCycles;
    private BlockPos observationCenter;
    private long loadedSampleCount;
    private long unloadedSampleCount;
    private long unapproachableMatchCount;
    private int observedMinX = Integer.MAX_VALUE;
    private int observedMaxX = Integer.MIN_VALUE;
    private int observedMinZ = Integer.MAX_VALUE;
    private int observedMaxZ = Integer.MIN_VALUE;
    private double farthestObservedDistance;
    private final Set<Long> observedLoadedColumns = new HashSet<>();
    private final Set<Long> exploredCenterColumns = new HashSet<>();
    private final List<BlockPos> exploredCenters = new ArrayList<>();
    private final List<BlockPos> unloadedFrontiers = new ArrayList<>();
    private final Map<Long, SurfaceInfo> surfaceCache = new HashMap<>();
    /** 只统计去重后真正新记录的发现；同一空间格重复扫到不会重复计数。 */
    private final Map<String, Integer> notableFindings = new LinkedHashMap<>();
    /** 沿途结构 sighting 扫描器；当前维度无可用画像时保持 null，不产生任何扫描开销。 */
    private StructureSightingScanner sightingScanner;
    private final Map<String, Integer> sightingRadii = new HashMap<>();
    /** 已记 sighting 的锚点，按 canonicalId 分桶；新锚点与任一已记锚点同簇即视为同一座。 */
    private final Map<String, List<BlockPos>> sightedAnchors = new HashMap<>();

    private MoveToCompanionTask moveChild;
    private MoveToTaskRecord moveRecord;
    private int legSerial;
    private TargetCandidate candidate;
    private BlockPos activeWaypoint;
    private BlockPos activeRelocationTarget;
    private int waypointAttempts;
    private int waypointReached;
    private int waypointFailed;
    private int targetAttempts;
    private final Set<Long> rejectedTargets = new HashSet<>();
    private final Set<Long> attemptedWaypoints = new HashSet<>();
    private final List<Map<String, Object>> legFailures = new ArrayList<>();
    private final FrontierLegBreaker waypointBreaker = new FrontierLegBreaker();
    /** 行进涉水守卫的记数：当前行程的连续滞水刻、最近一次逼近目标的时刻与已放弃行程次数。 */
    private int legWaterTicks;
    private long lastWaterApproachTick;
    private double legWaterBestDistance = Double.MAX_VALUE;
    private int waterAbandonments;

    private SpiralWalker spiral;

    private BlockPos verifiedPosition;
    private String verifiedDescription;
    private double farthestBodyDistance;
    /** 模型在兴趣决策中选了 stop；收尾回执保留发现与标签，不声称主目标已核实。 */
    private boolean stoppedByInterestDecision;
    /** 身体越过请求半径被安全截停时置位：失败码与建议都按越界实情给，不冒用寻路失败。 */
    private boolean radiusBoundExit;

    public SemanticExploreCompanionTask(
            LocalPlayer player, SemanticExploreTaskRecord record) {
        super(player, record);
    }

    @Override protected void onStart() {
        origin = player.blockPosition().immutable();
        sector = r.sector.at(origin.getX(), origin.getZ(), player.getYRot());
        spiral = new SpiralWalker(origin, WAYPOINT_GRID);
        ClientLevel level = ClientRuntime.requireContext(player).level();
        startingLevel = level;
        if (!resolveTarget(level, r.target)) {
            fail(inputFailure, FailureType.UNSUPPORTED);
            return;
        }
        memory = new ClientExplorationMemory(player);
        waterProbe = new WaterCrossingProbe(level);
        sightingScanner = createStructureScanner(level);
        beginObservation();
    }

    @Override protected TaskState onTick() {
        // 传送到其他维度后结束旧范围，不能把新世界观察挂到出发时的跑图任务上。
        if (player.clientLevel != startingLevel) {
            fail("dimension changed during exploration", FailureType.INTERRUPTED);
            return TaskState.FAILED;
        }
        memory.tick();
        double bodyDistance = horizontalDistance(origin, player.blockPosition());
        farthestBodyDistance = Math.max(farthestBodyDistance, bodyDistance);
        // 「到达半径即达成」优先于「走完航点」：跑图身体一进到达容差带就按 radius_reached 成功收尾，
        // 不等当前航点行程走完，也不会再向边界外推进；真越过硬边界才按越界截停如实失败。
        RadiusVerdict verdict = radiusVerdict(survey, bodyDistance, r.maxDistance);
        if (verdict == RadiusVerdict.RADIUS_REACHED) {
            // 航点行程未走完也被达成覆盖：子任务按取消记录，父任务按 radius_reached 成功收尾。
            stopActiveChild(TaskState.CANCELLED);
            surveyStopReason = "radius_reached";
            succeed();
            return TaskState.SUCCESS;
        }
        if (verdict == RadiusVerdict.RADIUS_BOUND_EXIT) {
            radiusBoundExit = true;
            surveyStopReason = "radius_bound_exit";
            stopActiveChild(TaskState.FAILED);
            fail("exploration movement left the bounded radius of " + r.maxDistance
                    + " blocks and was stopped", FailureType.RADIUS_BOUND_EXIT);
            return TaskState.FAILED;
        }
        // 兴趣答复由伴随任务消费一次；stop 是模型选择的收尾，发现与标签保留，不声称主目标已核实。
        String interestAnswer = r.consumeInterestAnswer();
        if ("stop".equals(interestAnswer)) {
            stoppedByInterestDecision = true;
            succeed();
            return TaskState.SUCCESS;
        }
        if (r.hasPendingInterestFinding()) {
            // 发现待询问期间冻结推进：不发起新的航点或目标路段，等语义层把发现转成正式决策。
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return TaskState.RUNNING;
        }
        return switch (stage) {
            case OBSERVE -> tickObservation();
            case TRAVEL_TARGET -> tickTravel(true);
            case VERIFY_TARGET -> tickVerification();
            case TRAVEL_WAYPOINT -> tickTravel(false);
            case TRAVEL_RELOCATE -> tickRelocation();
        };
    }

    private boolean resolveTarget(ClientLevel level, String raw) {
        String target = raw.trim();
        if ("survey".equals(target)) {
            // 自由跑图没有预设群系；观察完一轮就继续推进，不能把脚下任意群系当作抵达目标。
            survey = true;
            canonicalTarget = "survey";
            biomeMatch = holder -> true;
            return true;
        }
        var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        if (target.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(target.substring(1));
            if (id == null) {
                inputFailure = "invalid biome tag: " + target;
                return false;
            }
            TagKey<Biome> tag = TagKey.create(Registries.BIOME, id);
            if (registry.get(tag).isEmpty()) {
                inputFailure = "unknown biome tag in the client registry: " + target
                        + "; query perceive(view=exploration, focus=biome_tags) for installed tags";
                return false;
            }
            canonicalTarget = "#" + id;
            biomeMatch = holder -> holder.is(tag);
            return true;
        }
        ResourceLocation id = ResourceLocation.tryParse(target);
        ResourceKey<Biome> key = id == null ? null : ResourceKey.create(Registries.BIOME, id);
        if (key == null || registry.get(key).isEmpty()) {
            inputFailure = "unknown biome in the client registry: " + target
                    + "; query perceive(view=exploration, focus=biomes) for installed biomes";
            return false;
        }
        canonicalTarget = id.toString();
        biomeMatch = holder -> holder.is(key);
        return true;
    }

    private void beginObservation() {
        observationCenter = player.blockPosition().immutable();
        observationColumn = 0;
        biomeYIndex = 0;
        scanDwellTicks = 0;
        observationCycles++;
        surfaceCache.clear();
        long centerKey = BlockPos.asLong(
                observationCenter.getX(), 0, observationCenter.getZ());
        if (exploredCenters.size() < r.maxWaypoints + 1
                && exploredCenterColumns.add(centerKey)) {
            exploredCenters.add(observationCenter);
        }
        stage = Stage.OBSERVE;
    }

    private TaskState tickObservation() {
        ClientLevel level = ClientRuntime.requireContext(player).level();
        noteStructureSightings();
        List<ColumnOffset> offsets = BIOME_OBSERVATION_OFFSETS;
        int budget = BIOME_SAMPLES_PER_TICK;
        while (budget-- > 0 && observationColumn < offsets.size()) {
            ColumnOffset offset = offsets.get(observationColumn);
            int x = observationCenter.getX() + offset.dx();
            int z = observationCenter.getZ() + offset.dz();
            if (!insideScope(x, z)) {
                advanceObservationColumn();
                continue;
            }
            if (!columnLoaded(level, x, z)) {
                noteUnloaded(x, z);
                advanceObservationColumn();
                continue;
            }
            if (biomeYIndex == 0) {
                // 沿途可见的群系都属于跑图成果，即使它不是本次指定目标也保留到长期记忆。
                memory.observeBiome(surfaceTravelCell(level, x, z), false);
                noteTerrainFeature(level, x, z);
                loadedSampleCount++;
                observedLoadedColumns.add(BlockPos.asLong(x, 0, z));
                observedMinX = Math.min(observedMinX, x);
                observedMaxX = Math.max(observedMaxX, x);
                observedMinZ = Math.min(observedMinZ, z);
                observedMaxZ = Math.max(observedMaxZ, z);
                farthestObservedDistance = Math.max(farthestObservedDistance,
                        horizontalDistance(origin, new BlockPos(x, origin.getY(), z)));
            }

            TargetCandidate found = observeBiomeSample(level, x, z, biomeYIndex++);
            if (biomeYIndex >= biomeSampleCount(level)) {
                advanceObservationColumn();
            }
            if (!survey && found != null && !rejectedTargets.contains(found.approach().asLong())) {
                return startTargetTravel(found);
            }
        }
        if (observationColumn < offsets.size()) {
            // 观察会按每刻预算分批抽样有限的本地网格。保留这一 CPU 上限，同时不要因排队等待抽样而消耗语义任务的存活期限，尤其是在游戏刻加速时。
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return TaskState.RUNNING;
        }
        // 列抽样完成后给在途扫描轮留驻留：聚类判定结算（或到达上限）前不进入路点移动，
        // 否则路点间的位移会在下一轮观察把进度重开，村庄就在眼前也攒不出一次完整判定。
        if (sightingScanner != null && sightingScanner.roundInProgress()
                && scanDwellTicks < MAX_SCAN_DWELL_TICKS) {
            scanDwellTicks++;
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return TaskState.RUNNING;
        }
        return startNextWaypoint(level);
    }

    /**
     * 每刻消费扫描器本轮完成的 sighting：同簇去重后记一条线索，画像符合只是线索口径，不当成到访或生成器确认。
     * 模型声明的兴趣含此 canonicalId 时复用地形要素同一条询问链路。
     */
    private void noteStructureSightings() {
        if (sightingScanner == null) return;
        for (var sighting : sightingScanner.tick(player)) {
            String id = sighting.canonicalId();
            int clusterRadius = sightingRadii.getOrDefault(id, 0);
            if (isDuplicateSighting(sightedAnchors.get(id), sighting.anchor(), clusterRadius)) continue;
            sightedAnchors.computeIfAbsent(id, ignored -> new ArrayList<>()).add(sighting.anchor());
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("group_counts", sighting.groupCounts());
            evidence.put("block_counts", sighting.blockCounts());
            evidence.put("total_blocks", sighting.totalBlocks());
            ExplorationFinding finding =
                    memory.observeStructureSighting(id, sighting.anchor(), evidence);
            if (finding == null) continue;
            notableFindings.merge(id, 1, Integer::sum);
            noteInterestFinding(finding, sighting.anchor());
        }
    }

    /** 当前维度可解析的画像去重后交给扫描器；别名共享同一份画像，canonicalId 相同只留一份。 */
    private StructureSightingScanner createStructureScanner(ClientLevel level) {
        String dimension = level.dimension().location().toString();
        Map<String, StructureEvidenceProfiles.ResolvedProfile> unique = new LinkedHashMap<>();
        for (String id : StructureEvidenceProfiles.registeredIds()) {
            var resolved = StructureEvidenceProfiles.resolve(id);
            if (resolved == null) continue;
            Set<String> dims = resolved.profile().dimensions();
            if (!dims.isEmpty() && !dims.contains(dimension)) continue;
            unique.putIfAbsent(resolved.profile().canonicalId(), resolved);
        }
        if (unique.isEmpty()) return null;
        for (var resolved : unique.values()) {
            sightingRadii.put(resolved.profile().canonicalId(), resolved.profile().clusterRadius());
        }
        return new StructureSightingScanner(List.copyOf(unique.values()), OBSERVATION_RADIUS);
    }

    /** 新锚点与任一已记锚点落进同簇半径即视为同一座；纯几何判定供回归直测。 */
    static boolean isDuplicateSighting(List<BlockPos> recordedAnchors, BlockPos candidate, int clusterRadius) {
        if (recordedAnchors == null || clusterRadius < 1) return false;
        double radiusSquared = (double) clusterRadius * clusterRadius;
        for (BlockPos existing : recordedAnchors) {
            if (existing.distSqr(candidate) <= radiusSquared) return true;
        }
        return false;
    }

    /** 熔岩湖是远望地形要素：记入探索记忆但不给移动决策，只有真正新记录的发现才计入进度。 */
    private void noteTerrainFeature(ClientLevel level, int x, int z) {
        SurfaceInfo surface = surfaceInfo(level, x, z);
        if (surface.kind() != SurfaceKind.LAVA) return;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("surface_y", surface.evidence().getY());
        evidence.put("depth", surface.waterDepth());
        ExplorationFinding finding =
                memory.observeTerrainFeature("lava_pool", surface.evidence(), evidence);
        if (finding == null) return;
        notableFindings.merge("lava_pool", 1, Integer::sum);
        noteInterestFinding(finding, surface.evidence());
    }

    /** 声明了兴趣时把新发现挂上任务单等语义层询问；未声明兴趣时完全不置位，行为与从前一致。 */
    private void noteInterestFinding(ExplorationFinding finding, BlockPos evidence) {
        if (!r.declaredInterest(finding.targetId())) return;
        BlockPos from = player.blockPosition();
        r.noteInterestFinding(new SemanticExploreTaskRecord.InterestFinding(
                finding.id(), finding.targetId(),
                evidence.getX(), evidence.getY(), evidence.getZ(),
                CompassUtil.compass(evidence.getX() - from.getX(), evidence.getZ() - from.getZ()),
                (int) horizontalDistance(from, evidence)));
    }

    private TargetCandidate observeBiomeSample(
            ClientLevel level, int x, int z, int sampleIndex) {
        if (sampleIndex == 0) {
            int currentY = Math.clamp(player.blockPosition().getY(),
                    level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
            BlockPos approach = travelCellNear(level, x, currentY, z, 10, biomeMatch);
            if (approach != null) {
                return new TargetCandidate(approach, approach,
                        "loaded stand/swim cell inside " + canonicalTarget);
            }
            return null;
        }
        if (sampleIndex == 1) {
            BlockPos surface = surfaceTravelCell(level, x, z);
            if (surface != null && biomeMatch.test(level.getBiome(surface))) {
                return new TargetCandidate(surface, surface,
                        "surface cell inside " + canonicalTarget);
            }
            return null;
        }
        int sampleY = level.getMinBuildHeight() + 8 + (sampleIndex - 2) * BIOME_Y_STEP;
        sampleY = Math.clamp(sampleY,
                level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        BlockPos sample = new BlockPos(x, sampleY, z);
        if (!biomeMatch.test(level.getBiome(sample))) {
            return null;
        }
        BlockPos approach = travelCellNear(level, x, sampleY, z, 10, biomeMatch);
        if (approach == null) {
            unapproachableMatchCount++;
            return null;
        }
        return new TargetCandidate(approach, sample,
                "loaded stand/swim cell inside " + canonicalTarget);
    }

    private int biomeSampleCount(ClientLevel level) {
        int height = level.getMaxBuildHeight() - level.getMinBuildHeight();
        return 2 + (height + BIOME_Y_STEP - 1) / BIOME_Y_STEP;
    }

    private void advanceObservationColumn() {
        observationColumn++;
        biomeYIndex = 0;
    }

    private TaskState startTargetTravel(TargetCandidate found) {
        // 较近的侧后方群系不能替代指定方向上的目标；候选落脚点与最终站位都按同一扇区核实。
        if (!sector.accepts(found.approach().getX(), found.approach().getZ())) return TaskState.RUNNING;
        // 前往语义目的地前先看到其证据位置，不能凭已加载的地下生物群系或墙后水域导航进去。
        if (!ObservationVisibility.block(player, found.evidence()))
            return TaskState.RUNNING;
        memory.observeBiome(found.approach(), false);
        candidate = found;
        targetAttempts++;
        startMove(found.approach(), true);
        stage = Stage.TRAVEL_TARGET;
        return TaskState.RUNNING;
    }

    private TaskState startNextWaypoint(ClientLevel level) {
        // 抵达请求半径的收尾判定统一在 onTick 的 radius_reached 检查里做；这里只负责选下一条航点行程。
        if (waypointAttempts >= r.maxWaypoints) {
            return exhausted();
        }
        BlockPos waypoint = nextWaypoint(level);
        if (waypoint == null) {
            return exhausted();
        }
        activeWaypoint = waypoint;
        waypointAttempts++;
        startMove(waypoint, false);
        stage = Stage.TRAVEL_WAYPOINT;
        return TaskState.RUNNING;
    }

    private void startMove(BlockPos target, boolean exact) {
        long now = player.level().getGameTime();
        String parentCall = r.getToolCallId() == null ? "explore" : r.getToolCallId();
        String childCall = parentCall + "-internal-leg-" + (++legSerial);
        // 群系目的地允许站立或游泳；到达后依据脚下真实群系复核。
        moveRecord = new MoveToTaskRecord(
                        childCall, now + SemanticExploreTaskRecord.LEG_LEASE_TICKS,
                        (double) target.getX(), exact ? (double) target.getY() : null,
                        (double) target.getZ(), null,
                        r.mayAlterTerrain, false, r.transportMode);
        moveChild = new MoveToCompanionTask(player, moveRecord);
        legWaterTicks = 0;
        legWaterBestDistance = Double.MAX_VALUE;
        lastWaterApproachTick = now;
    }

    private TaskState tickTravel(boolean targetTravel) {
        TaskState terminal = null;
        TaskResult result = null;
        boolean abandonedInWater = false;
        if (player.level().getGameTime() >= moveRecord.getDeadlineGameTime()) {
            moveChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(moveChild);
            if (terminal == null) {
                // 只要实际执行的子任务持续续期已核实进度期限，耗时较长但健康的旅程就继续存活。语义半径和航点边界仍限制搜索范围，墙上时间不会改变目标含义。
                r.extendDeadlineTo(moveRecord.getDeadlineGameTime());
                // 行进涉水守卫：身体被行程带进水里且不再逼近目标（或连续涉水越过硬上限）
                // 时立即放弃当前行程换向，不等租约烧完，更不靠漂浮续命。
                if (tickWaterLegGuard()) {
                    abandonedInWater = true;
                } else {
                    return TaskState.RUNNING;
                }
            }
        }
        if (abandonedInWater) {
            waterAbandonments++;
            result = TaskResult.fail(waterAbandonMessage());
            stopActiveChild(TaskState.FAILED);
        } else {
            result = moveChild.result(terminal);
            moveChild = null;
            moveRecord = null;
        }

        if (targetTravel) {
            if (terminal == TaskState.SUCCESS) {
                stage = Stage.VERIFY_TARGET;
                return TaskState.RUNNING;
            }
            rejectedTargets.add(candidate.approach().asLong());
            recordLegFailure(abandonedInWater
                            ? "target_approach_abandoned_in_water" : "target_approach",
                    candidate.approach(), result);
            candidate = null;
            beginObservation();
            return TaskState.RUNNING;
        }

        if (terminal == TaskState.SUCCESS) {
            waypointReached++;
            waypointBreaker.onLegSuccess();
        } else {
            waypointFailed++;
            recordLegFailure(abandonedInWater
                            ? "exploration_waypoint_abandoned_in_water" : "exploration_waypoint",
                    activeWaypoint, result);
            // 航点高频走不到时按 换锚→轮换→受阻 升级：先挪发射位置，再换候选扇区，
            // 都失败就宣布受阻并给出远距换区建议，不在不可行地形带无限重付寻路成本。
            FrontierLegBreaker.Decision decision = waypointBreaker.onLegFailure(sector);
            while (decision == FrontierLegBreaker.Decision.RELOCATED) {
                BlockPos hop = relocationTarget(waypointBreaker.relocationBearing(sector));
                if (hop != null) {
                    activeWaypoint = null;
                    activeRelocationTarget = hop;
                    startMove(hop, false);
                    stage = Stage.TRAVEL_RELOCATE;
                    return TaskState.RUNNING;
                }
                decision = waypointBreaker.onRelocationLegFailed(sector);
            }
            if (decision == FrontierLegBreaker.Decision.ROTATED) {
                sector = waypointBreaker.rotated(sector);
            } else if (decision == FrontierLegBreaker.Decision.EXHAUSTED) {
                fail(FrontierLegBreaker.blockedMessage(
                        "waypoint", waypointFailed, waypointBreaker, sector),
                        FailureType.NO_PATH);
                return TaskState.FAILED;
            }
        }
        activeWaypoint = null;
        beginObservation();
        return TaskState.RUNNING;
    }

    /** 换锚行程结束：到达即在新发射点重新观察选航点；失败按换锚失败继续升级，不在坏锚点原地再烧一窗尝试。 */
    private TaskState tickRelocation() {
        TaskState terminal = null;
        TaskResult result = null;
        boolean abandonedInWater = false;
        if (player.level().getGameTime() >= moveRecord.getDeadlineGameTime()) {
            moveChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(moveChild);
            if (terminal == null) {
                r.extendDeadlineTo(moveRecord.getDeadlineGameTime());
                if (tickWaterLegGuard()) {
                    abandonedInWater = true;
                } else {
                    return TaskState.RUNNING;
                }
            }
        }
        if (abandonedInWater) {
            waterAbandonments++;
            result = TaskResult.fail(waterAbandonMessage());
            stopActiveChild(TaskState.FAILED);
        } else {
            result = moveChild.result(terminal);
            moveChild = null;
            moveRecord = null;
        }
        BlockPos relocationTarget = activeRelocationTarget;
        activeRelocationTarget = null;
        if (terminal == TaskState.SUCCESS && result != null && result.success()) {
            beginObservation();
            return TaskState.RUNNING;
        }
        recordLegFailure(abandonedInWater
                        ? "anchor_relocation_abandoned_in_water" : "anchor_relocation",
                relocationTarget, result);
        FrontierLegBreaker.Decision decision = waypointBreaker.onRelocationLegFailed(sector);
        while (decision == FrontierLegBreaker.Decision.RELOCATED) {
            BlockPos hop = relocationTarget(waypointBreaker.relocationBearing(sector));
            if (hop != null) {
                activeRelocationTarget = hop;
                startMove(hop, false);
                return TaskState.RUNNING;
            }
            decision = waypointBreaker.onRelocationLegFailed(sector);
        }
        if (decision == FrontierLegBreaker.Decision.ROTATED) {
            sector = waypointBreaker.rotated(sector);
        } else if (decision == FrontierLegBreaker.Decision.EXHAUSTED) {
            fail(FrontierLegBreaker.blockedMessage(
                    "waypoint", waypointFailed, waypointBreaker, sector),
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        beginObservation();
        return TaskState.RUNNING;
    }

    /** 行进涉水守卫的一刻：更新滞水计数与最近逼近时刻，返回是否应放弃当前行程；离水即清零。 */
    private boolean tickWaterLegGuard() {
        long now = player.level().getGameTime();
        if (!player.isInWater()) {
            legWaterTicks = 0;
            lastWaterApproachTick = now;
            return false;
        }
        legWaterTicks++;
        BlockPos target = activeWaypoint != null ? activeWaypoint : activeRelocationTarget;
        if (target != null) {
            double d = horizontalDistance(player.blockPosition(), target);
            if (d < legWaterBestDistance - 0.5) {
                legWaterBestDistance = d;
                lastWaterApproachTick = now;
            }
        }
        return WaterCrossingProbe.waterLegExpired(
                legWaterTicks, now - lastWaterApproachTick);
    }

    /** 滞水放弃行程的如实话术：回执与 travel_failures 都能看出放弃原因是涉水楔死。 */
    private String waterAbandonMessage() {
        return "leg abandoned: the body stayed in water for " + legWaterTicks
                + " continuous ticks without approaching the leg target"
                + "; the exploration redirects instead of floating in place";
    }

    /** 换锚落点：沿建议方位截取已加载的小半径路段；截不出（未加载或过近）返回 null 继续升级。 */
    private BlockPos relocationTarget(double bearingDegrees) {
        ClientLevel level = ClientRuntime.requireContext(player).level();
        double radians = Math.toRadians(bearingDegrees);
        BlockPos desired = new BlockPos(
                (int) Math.round(player.getX() + Math.sin(radians) * RELOCATION_HOP_BLOCKS),
                player.blockPosition().getY(),
                (int) Math.round(player.getZ() - Math.cos(radians) * RELOCATION_HOP_BLOCKS));
        return loadedFrontierToward(level, desired, this::routeBlockedByWater);
    }

    private TaskState tickVerification() {
        ClientLevel level = ClientRuntime.requireContext(player).level();
        TargetCandidate verified = null;
        BlockPos feet = player.blockPosition();
        if (level.isLoaded(feet) && biomeMatch.test(level.getBiome(feet))
                && sector.accepts(feet.getX(), feet.getZ())) {
            verified = new TargetCandidate(feet, feet,
                    "body position is inside " + biomeId(level, feet));
        }
        if (verified != null) {
            verifiedPosition = player.blockPosition().immutable();
            verifiedDescription = verified.description();
            r.retainVerifiedPosition(new InternalPositionReceipt.Position(
                    verifiedPosition.getX(), verifiedPosition.getY(), verifiedPosition.getZ(),
                    player.level().dimension().location().toString()));
            return TaskState.SUCCESS;
        }

        rejectedTargets.add(candidate.approach().asLong());
        recordLegFailure("target_verification", candidate.approach(),
                TaskResult.fail("arrival did not re-confirm the semantic target in loaded facts"));
        candidate = null;
        beginObservation();
        return TaskState.RUNNING;
    }

    private SurfaceInfo surfaceInfo(ClientLevel level, int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        SurfaceInfo cached = surfaceCache.get(key);
        if (cached != null) return cached;
        SurfaceInfo result;
        if (!columnLoaded(level, x, z)) {
            result = new SurfaceInfo(SurfaceKind.UNKNOWN, null, null, 0);
        } else {
            int height = Math.clamp(
                    ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z),
                    level.getMinBuildHeight() + 1,
                    level.getMaxBuildHeight() - 1);
            BlockPos top = new BlockPos(x, height - 1, z);
            BlockState topState = level.getBlockState(top);
            FluidColumn fluid = fluidColumn(topState.getFluidState(),
                    level, x, z, top, level.getMinBuildHeight());
            if (fluid != null) {
                result = new SurfaceInfo(fluid.kind(), null, top, fluid.depth());
            } else {
                BlockPos approach = dryTravelCellNear(level, x, height, z, 3);
                result = approach == null
                        ? new SurfaceInfo(SurfaceKind.UNKNOWN, null, top, 0)
                        : new SurfaceInfo(SurfaceKind.LAND, approach, top, 0);
            }
        }
        surfaceCache.put(key, result);
        return result;
    }

    /**
     * 顶块带水或熔岩时按列向下数同种流体的深度（封顶 8）；MOTION_BLOCKING 高度图含流体，熔岩面与水面取到的顶面同理。
     * 非流体顶面返回 null，由调用方走干立足点判定。
     */
    static FluidColumn fluidColumn(
            FluidState topFluid, BlockGetter level, int x, int z, BlockPos top, int minY) {
        SurfaceKind kind = topFluid.is(FluidTags.WATER) ? SurfaceKind.WATER
                : topFluid.is(FluidTags.LAVA) ? SurfaceKind.LAVA : SurfaceKind.UNKNOWN;
        if (kind == SurfaceKind.UNKNOWN) return null;
        TagKey<Fluid> tag = kind == SurfaceKind.WATER ? FluidTags.WATER : FluidTags.LAVA;
        int depth = 0;
        for (int y = top.getY(); y >= minY && depth < 8; y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).getFluidState().is(tag)) break;
            depth++;
        }
        return new FluidColumn(kind, depth);
    }

    private BlockPos surfaceTravelCell(ClientLevel level, int x, int z) {
        SurfaceInfo surface = surfaceInfo(level, x, z);
        if (surface.kind() == SurfaceKind.LAND) return surface.approach();
        if (surface.kind() == SurfaceKind.WATER && isTravelCell(level, surface.evidence())) {
            return surface.evidence();
        }
        return null;
    }

    private BlockPos travelCellNear(
            ClientLevel level, int x, int y, int z, int verticalRadius,
            Predicate<Holder<Biome>> requiredBiome) {
        for (int distance = 0; distance <= verticalRadius; distance++) {
            int[] ys = distance == 0 ? new int[]{y} : new int[]{y + distance, y - distance};
            for (int candidateY : ys) {
                if (candidateY <= level.getMinBuildHeight()
                        || candidateY >= level.getMaxBuildHeight() - 1) continue;
                BlockPos candidate = new BlockPos(x, candidateY, z);
                if (isTravelCell(level, candidate)
                        && (requiredBiome == null
                                || requiredBiome.test(level.getBiome(candidate)))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private BlockPos dryTravelCellNear(
            ClientLevel level, int x, int y, int z, int verticalRadius) {
        for (int distance = 0; distance <= verticalRadius; distance++) {
            int[] ys = distance == 0 ? new int[]{y} : new int[]{y + distance, y - distance};
            for (int candidateY : ys) {
                if (candidateY <= level.getMinBuildHeight()
                        || candidateY >= level.getMaxBuildHeight() - 1) continue;
                BlockPos candidate = new BlockPos(x, candidateY, z);
                if (level.isLoaded(candidate.below()) && level.isLoaded(candidate.above())
                        && BlockHelper.isDryStandable(level, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean isTravelCell(ClientLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.above())) return false;
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        if (feetState.getFluidState().is(FluidTags.LAVA)
                || headState.getFluidState().is(FluidTags.LAVA)) return false;
        boolean headClear = headState.getCollisionShape(level, feet.above()).isEmpty();
        if (feetState.getFluidState().is(FluidTags.WATER)) return headClear;
        if (!feetState.getCollisionShape(level, feet).isEmpty() || !headClear) return false;
        BlockPos below = feet.below();
        return level.isLoaded(below)
                && !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                && !level.getBlockState(below).getFluidState().is(FluidTags.LAVA);
    }

    /** 航点候选的涉水口径：落点列是纯水面（海面、湖心）或路线判穿水都算被水挡住；短促渡水豁免不变（130）。 */
    private boolean routeBlockedByWater(BlockPos candidate) {
        return waterProbe.landsOnWater(candidate.getX(), candidate.getZ())
                || waterProbe.crossesWater(player.blockPosition(), candidate);
    }

    private BlockPos nextWaypoint(ClientLevel level) {
        if (r.sector.direction() != null) {
            return ExplorationFrontiers.next(player.blockPosition(), sector, r.maxDistance,
                    attemptedWaypoints, pos -> columnLoaded(level, pos.getX(), pos.getZ()),
                    this::routeBlockedByWater);
        }
        return nextTerrainNeutralWaypoint(level);
    }

    private BlockPos nextTerrainNeutralWaypoint(ClientLevel level) {
        for (int probe = 0; probe < MAX_SPIRAL_PROBES; probe++) {
            BlockPos desired = spiral.next(player.blockPosition().getY());
            if (!insideScope(desired.getX(), desired.getZ())) continue;
            BlockPos frontier = loadedFrontierToward(level, desired,
                    this::routeBlockedByWater);
            if (frontier != null && attemptedWaypoints.add(
                    BlockPos.asLong(frontier.getX(), 0, frontier.getZ()))) return frontier;
        }
        return null;
    }

    // 优先返回不穿水的最远已加载路段；全线皆穿水时回退最近已加载路段，但落点列本身是
    // 纯水面的候选一律不选——短促渡水到对岸干地仍允许（130 豁免），航点落在水面上只会
    // 把身体领进海里（175），截不出干地落点就返回 null 由上层如实失败。
    private BlockPos loadedFrontierToward(
            ClientLevel level, BlockPos desired, Predicate<BlockPos> avoidWater) {
        BlockPos current = player.blockPosition();
        double dx = desired.getX() - current.getX();
        double dz = desired.getZ() - current.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0) return null;
        double farthest = Math.min(MAX_LEG_DISTANCE, distance);
        BlockPos fallback = null;
        for (double leg = farthest; leg >= Math.min(16.0, farthest); leg -= 16.0) {
            int x = (int) Math.round(current.getX() + dx / distance * leg);
            int z = (int) Math.round(current.getZ() + dz / distance * leg);
            if (!insideScope(x, z) || !columnLoaded(level, x, z)) continue;
            BlockPos candidate = new BlockPos(x, current.getY(), z);
            if (avoidWater == null) return candidate;
            fallback = candidate;
            if (!avoidWater.test(candidate)) return candidate;
        }
        if (avoidWater == null || fallback == null) return null;
        return waterProbe.landsOnWater(fallback.getX(), fallback.getZ()) ? null : fallback;
    }

    private TaskState exhausted() {
        if (survey && waypointReached > 0) {
            surveyStopReason = "reachable_frontiers_exhausted";
            return TaskState.SUCCESS;
        }
        if (survey) {
            fail("map survey could not reach a new frontier; any actual observations remain in exploration memory", FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        fail("bounded exploration finished without verifying " + canonicalTarget
                        + "; searched only the initial client view and terrain loaded by real "
                        + "travel within "
                        + r.maxDistance + " blocks",
                FailureType.TARGET_LOST);
        return TaskState.FAILED;
    }

    private void noteUnloaded(int x, int z) {
        unloadedSampleCount++;
        BlockPos sample = new BlockPos(x, 0, z);
        for (BlockPos existing : unloadedFrontiers) {
            if (existing.distManhattan(sample) < 32) return;
        }
        unloadedFrontiers.add(sample);
    }

    private void recordLegFailure(String kind, BlockPos target, TaskResult result) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("kind", kind);
        failure.put("x", target.getX());
        failure.put("y", target.getY());
        failure.put("z", target.getZ());
        failure.put("message", result == null ? "no child result" : result.message());
        legFailures.add(failure);
    }

    private void stopActiveChild(TaskState terminal) {
        if (moveChild == null) return;
        moveChild.stop(player, Task.StopReason.REPLACED);
        moveChild.result(terminal);
        moveChild = null;
        moveRecord = null;
    }

    private boolean insideScope(int x, int z) {
        double dx = x - origin.getX();
        double dz = z - origin.getZ();
        return dx * dx + dz * dz <= (double) r.maxDistance * r.maxDistance;
    }

    /** 跑图达成半径的距离阈值：到这条线即算 radius_reached，与航点落点上限共用同一容差，留出收尾判定的提前量。 */
    static double surveyRadiusReachThreshold(int maxDistance) {
        return maxDistance - ExplorationFrontiers.arrivalMargin(maxDistance);
    }

    // 包内可见：跑图半径收尾判定由回归直测。越界判定优先——身体一次跳到硬边界外
    // （传送、失控等不可控位移）不冒充达成；达成带内的正常推进不等航点行程走完即收尾。
    enum RadiusVerdict { WITHIN_RADIUS, RADIUS_REACHED, RADIUS_BOUND_EXIT }

    static RadiusVerdict radiusVerdict(boolean survey, double bodyDistance, int maxDistance) {
        if (bodyDistance > maxDistance + SCOPE_TOLERANCE) return RadiusVerdict.RADIUS_BOUND_EXIT;
        if (survey && bodyDistance >= surveyRadiusReachThreshold(maxDistance)) {
            return RadiusVerdict.RADIUS_REACHED;
        }
        return RadiusVerdict.WITHIN_RADIUS;
    }

    private boolean columnLoaded(ClientLevel level, int x, int z) {
        int y = Math.clamp(player.blockPosition().getY(),
                level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        return level.isLoaded(new BlockPos(x, y, z));
    }

    private static double horizontalDistance(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String biomeId(ClientLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unregistered biome");
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static List<ColumnOffset> buildOffsets(int step) {
        List<ColumnOffset> offsets = new ArrayList<>();
        for (int dx = -OBSERVATION_RADIUS; dx <= OBSERVATION_RADIUS; dx += step) {
            for (int dz = -OBSERVATION_RADIUS; dz <= OBSERVATION_RADIUS; dz += step) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared <= OBSERVATION_RADIUS * OBSERVATION_RADIUS) {
                    offsets.add(new ColumnOffset(dx, dz, distanceSquared));
                }
            }
        }
        offsets.sort(Comparator.comparingInt(ColumnOffset::distanceSquared));
        return List.copyOf(offsets);
    }

    /** 停滞与进度对调用方可见：still working 事件要能看出连续失败的累积与扇区轮换。 */
    @Override public Map<String, Object> progress() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stage", String.valueOf(stage));
        data.put("waypoints_attempted", waypointAttempts);
        data.put("waypoints_reached", waypointReached);
        data.put("waypoints_failed", waypointFailed);
        data.put("waypoint_consecutive_failures", waypointBreaker.consecutiveFailures());
        data.put("waypoint_recent_failures", waypointBreaker.recentWindowFailures());
        data.put("waypoint_sector_rotations", waypointBreaker.rotations());
        data.put("waypoint_relocations", waypointBreaker.relocations());
        // 滞水守卫对调用方可见：行进中身体落水要能从进度流里看出，放弃行程不等楔死才暴露。
        if (legWaterTicks > 0) {
            data.put("leg_water_ticks", legWaterTicks);
            data.put("leg_water_stall_limit", WaterCrossingProbe.WATER_LEG_STALL_TICKS);
        }
        data.put("notable_findings", Map.copyOf(notableFindings));
        if (r.hasPendingInterestFinding()) data.put("paused_for_interest_decision", true);
        return data;
    }

    /** 面板行动行的一句话汇报；方向来自模型下单的探索扇区，未指定方向即四周漫游。 */
    @Override public String describeCurrentAction() {
        if (sector == null || sector.request().direction() == null) return "正在四周探索附近区域";
        String direction = switch (sector.request().direction()) {
            case "north" -> "北";
            case "south" -> "南";
            case "east" -> "东";
            case "west" -> "西";
            default -> "";
        };
        return direction.isEmpty() ? "正在四周探索附近区域" : "正在向" + direction + "方向探索附近区域";
    }

    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("target", canonicalTarget == null ? r.target : canonicalTarget);
        if (memory != null) data.put("exploration_memory", memory.receipt());
        if (stoppedByInterestDecision) {
            data.put("stopped_by_interest_decision", true);
            var finding = r.pendingInterestFinding();
            if (finding != null) {
                data.put("interest_stop_finding", Map.of(
                        "finding_id", finding.findingId(),
                        "target_id", finding.targetId(),
                        "x", finding.x(), "y", finding.y(), "z", finding.z(),
                        "direction", finding.direction(),
                        "distance_blocks", finding.distanceBlocks()));
            }
        }
        if (!survey) data.put("verified", verifiedPosition != null);
        if (survey) {
            data.put("survey_stop_reason", surveyStopReason == null ? "interrupted_or_blocked" : surveyStopReason);
            data.put("coverage", "sampled_observed_terrain_not_exhaustive");
        }
        data.put("scope", "initial_client_view_and_first_person_loaded_terrain");
        data.put("max_distance", r.maxDistance);
        if (sector != null) data.put("search_sector", sector.describe());
        data.put("origin", Map.of("x", origin.getX(), "y", origin.getY(), "z", origin.getZ()));
        data.put("final_position", Map.of(
                "x", player.getX(), "y", player.getY(), "z", player.getZ()));
        data.put("farthest_body_distance", farthestBodyDistance);
        data.put("observation_cycles", observationCycles);
        data.put("loaded_columns_observed", observedLoadedColumns.size());
        data.put("loaded_column_samples", loadedSampleCount);
        data.put("farthest_loaded_observation", farthestObservedDistance);
        if (!observedLoadedColumns.isEmpty()) {
            data.put("observed_loaded_bounds", Map.of(
                    "min_x", observedMinX, "max_x", observedMaxX,
                    "min_z", observedMinZ, "max_z", observedMaxZ));
        }
        data.put("unloaded_column_samples", unloadedSampleCount);
        data.put("unapproachable_loaded_matches", unapproachableMatchCount);
        data.put("waypoints_attempted", waypointAttempts);
        data.put("waypoints_reached", waypointReached);
        data.put("waypoints_failed", waypointFailed);
        data.put("waypoint_consecutive_failures", waypointBreaker.consecutiveFailures());
        data.put("waypoint_sector_rotations", waypointBreaker.rotations());
        data.put("waypoint_relocations", waypointBreaker.relocations());
        if (waypointBreaker.relocations() > 0) {
            data.put("waypoint_relocation_bearings", waypointBreaker.anchorRelocationBearings());
        }
        if (waypointBreaker.rotations() > 0) {
            data.put("waypoint_rotation_bearings", waypointBreaker.rotatedBearings());
        }
        data.put("target_approaches_attempted", targetAttempts);
        if (waterAbandonments > 0) {
            data.put("leg_water_abandonments", waterAbandonments);
        }
        // 结构 sighting 扫描逐相计数：零落账时回执直接指出断在哪一相（索引/可见性/分组/匹配）。
        if (sightingScanner != null) {
            data.put("structure_scan", sightingScanner.diagnostics());
            data.put("structure_sightings_recorded", sightedAnchors.values().stream().mapToInt(List::size).sum());
        }
        // 路段失败完整保留在任务证据中；默认回执可按既有归档机制分页，不能按固定条数丢弃卡点。
        data.put("travel_failures", List.copyOf(legFailures));

        List<Map<String, Object>> centers = exploredCenters.stream()
                .map(pos -> Map.<String, Object>of(
                        "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(),
                        "distance_from_origin", horizontalDistance(origin, pos)))
                .toList();
        data.put("explored_centers", centers);

        List<Map<String, Object>> frontiers = unloadedFrontiers.stream()
                .map(pos -> Map.<String, Object>of(
                        "x", pos.getX(), "z", pos.getZ(),
                        "direction", CompassUtil.compass(
                                pos.getX() - origin.getX(), pos.getZ() - origin.getZ())))
                .toList();
        data.put("observed_unloaded_frontier_samples", frontiers);
        if (verifiedPosition != null) {
            data.put("verified_position", Map.of(
                    "x", verifiedPosition.getX(),
                    "y", verifiedPosition.getY(),
                    "z", verifiedPosition.getZ()));
            data.put("verification", verifiedDescription);
        } else {
            List<String> suggestions = new ArrayList<>();
            if (radiusBoundExit) {
                // 越界截停的实情是身体已越过请求半径，不是寻路无路；通用扩距建议只会误导归因。
                suggestions.add("the body moved past the requested radius and was stopped for safety;"
                        + " observations gathered before the stop remain in exploration memory");
                suggestions.add("submit a new explore from the final position to continue outward");
            } else {
                suggestions.add("increase max_distance or choose another semantic landmark");
                suggestions.add("continue from the final position to search a different loaded frontier");
                suggestions.add("sparse generation is normal; no match over the observed area is not proof of absence beyond it");
            }
            if (waypointBreaker.relocations() > 0) {
                suggestions.add("anchor relocations toward "
                        + waypointBreaker.anchorRelocationBearings().stream()
                                .map(value -> Math.round(value) + " degrees")
                                .collect(java.util.stream.Collectors.joining(", "))
                        + " also failed route finding; the launch terrain itself looks infeasible,"
                        + " so travel to open terrain a few hundred blocks away and re-submit");
            }
            if (!r.mayAlterTerrain && waypointFailed > 0) {
                suggestions.add("review travel_failures; enable may_alter_terrain only if those route changes are acceptable");
            }
            data.put("suggestions", suggestions);
        }
        return data;
    }

    @Override protected String successMessage() {
        if (stoppedByInterestDecision) {
            var finding = r.pendingInterestFinding();
            String place = finding == null ? "" : " at "
                    + finding.x() + "," + finding.y() + "," + finding.z();
            return "exploration stopped by decision after a new "
                    + (finding == null ? "interest" : finding.targetId()) + " finding" + place
                    + "; all observations remain in exploration memory and the main target was not verified";
        }
        if (survey) return "map survey finished: " + surveyStopReason + "; only actually observed terrain is recorded";
        return "verified " + canonicalTarget + " at " + shortPos(verifiedPosition)
                + " after real first-person exploration";
    }

    @Override protected String timeoutMessage() {
        if (survey) return "map survey stopped making movement progress; actually observed places remain in exploration memory";
        return "semantic exploration stopped making verifiable progress before " + canonicalTarget
                + " was verified; the explored range and unloaded frontier are in data";
    }

    @Override protected String cancelledMessage() {
        if (survey) return "map survey was interrupted; actually observed places remain in exploration memory";
        return "semantic exploration was interrupted before verification";
    }

    @Override protected void cleanup() {
        if (memory != null) memory.close();
        if (sightingScanner != null) sightingScanner.release();
        stopActiveChild(TaskState.CANCELLED);
        super.cleanup();
    }
}
