// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.lighting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DirtPathBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.InternalAreaProtectionReceipt;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import java.util.Locale;

/** 基于已加载事实规划照明，通过施工回执放置灯具并核实实际光照。 */
public final class SemanticLightAreaCompanionTask
        extends AbstractCompanionTask<SemanticLightAreaTaskRecord> {

    private enum Stage {
        OBSERVE, TRAVEL_SURVEY, PLAN, SUPPLY, BUILD, SETTLE, VERIFY
    }
    private enum SurveyMovePurpose { LOAD_ANCHOR, LOAD_COMPONENT_FRONTIER }
    private record Sample(BlockPos pos, int light) {}
    private record ComponentFrontier(BlockPos knownCell, BlockPos unknownCell) {}
    private record LightSource(
            Item item, Block block, String id, int emission, int available, int priority) {}
    private record Candidate(
            BlockPos pos, BlockState state, int preferenceScore, Set<Long> covers) {}

    private static final int COLUMNS_PER_TICK = 18;
    private static final int COMPONENT_CELLS_PER_TICK = 96;
    private static final int FRONTIERS_PER_TICK = 192;
    private static final int VERIFY_PER_TICK = 256;
    private static final int SETTLE_TICKS = 5;
    private static final int MAX_CANDIDATES = 4_096;
    private static final int MAX_PASS_BATCH = 48;
    private static final long LIGHTING_PROGRESS_LEASE_TICKS = 2L * 60L * 20L;
    private static final int PROTECTED_LABEL_RADIUS = 4;
    private static final List<String> DEFAULT_LIGHT_IDS = List.of(
            "minecraft:torch", "minecraft:lantern", "minecraft:glowstone",
            "minecraft:sea_lantern", "minecraft:shroomlight");

    private Stage stage;
    private int explicitScanDx;
    private int explicitScanDz;
    private boolean explicitScanComplete;
    private int unloadedColumns;
    private int loadedColumns;
    private final Map<Long, Sample> samples = new LinkedHashMap<>();
    private final Map<String, Integer> protectedFacts = new LinkedHashMap<>();
    private final List<BlockPos> protectedAnchors = new ArrayList<>();
    private final Set<BlockPos> protectedMutationCells = new LinkedHashSet<>();
    private final Set<BlockPos> protectedNavigationCells = new LinkedHashSet<>();
    /** 从地标所在柱列开始，访问当前所有可达的已加载柱列以发现初始候选。 */
    private final ArrayDeque<Long> seedColumns = new ArrayDeque<>();
    private final Set<Long> queuedSeedColumns = new HashSet<>();
    /** 只有实时观察到的匹配方块事实才会扩展语义区域。 */
    private final ArrayDeque<BlockPos> componentCells = new ArrayDeque<>();
    private final Set<Long> queuedComponentCells = new HashSet<>();
    private final Set<Long> observedComponentCells = new HashSet<>();
    private final Map<Long, ComponentFrontier> componentFrontiers = new LinkedHashMap<>();
    private final ArrayDeque<Long> componentFrontierOrder = new ArrayDeque<>();
    private final Set<Long> rejectedComponentFrontiers = new HashSet<>();
    private final Set<Long> placementFootprintColumns = new HashSet<>();
    private final Set<Long> attemptedSurveyMoves = new HashSet<>();
    private boolean seedSearchInitialized;
    private boolean componentSeeded;
    private boolean areaBoundaryVerified;
    private int seedColumnsObserved;
    private int componentFrontiersObserved;
    private int componentFrontiersLoaded;
    private int surveyLegs;
    private int surveyLegFailures;
    private MoveToCompanionTask surveyMoveChild;
    private MoveToTaskRecord surveyMoveRecord;
    private SurveyMovePurpose surveyMovePurpose;
    private long activeComponentFrontier = Long.MIN_VALUE;
    private int surveyMoveSerial;
    private List<Sample> targetCells = List.of();
    private List<Sample> darkCells = List.of();
    private double achievedCoverage;
    private int litCells;
    private int verifyIndex;
    private int verifyLit;
    private final List<Sample> verifyDark = new ArrayList<>();

    private LightSource source;
    private final Set<Long> attemptedPositions = new HashSet<>();
    private int passes;
    private int requestedPlacements;
    private int settledAt;
    private Task buildChild;
    private BuildTaskRecord buildRecord;
    private int observedBuildProgress;
    private int passBaselineLitCells;
    private String activeCandidateFingerprint;
    private String lastUnproductiveCandidateFingerprint;
    private TaskResult lastBuildResult;
    /** 跨轮累计的逐灯位拒绝明细（坐标+拒绝闸名），失败回执直接交付，实机零放置时可读因。 */
    private final List<Map<String, Object>> siteRejections = new ArrayList<>();
    private final List<Map<String, Object>> childReceipts = new ArrayList<>();
    private final SemanticMaterialSupplyCoordinator supply =
            new SemanticMaterialSupplyCoordinator();
    private final List<Map<String, Object>> supplyReceipts = new ArrayList<>();
    private int supplyRounds;
    private String pendingSupplySource;
    private String pinnedSuppliedSource;
    private Map<String, Object> supplyFailure = Map.of();
    private String failureCode;
    private boolean requiresDecision;
    private List<String> recoveryOptions = List.of();

    public SemanticLightAreaCompanionTask(
            LocalPlayer player, SemanticLightAreaTaskRecord record) {
        super(player, record);
    }

    @Override protected void onStart() {
        // 先确认区域灯具样式与需保留的地点，再调查暗格、取料、分轮放置和复测；这一能力会占用主任务身体。
        if (r.style == SemanticLightAreaTaskRecord.Style.WALL
                || r.style == SemanticLightAreaTaskRecord.Style.HANGING) {
            giveUp("unsupported_lighting_style",
                    "style=" + r.style.name().toLowerCase(Locale.ROOT)
                            + " has no receipt-verifiable placement planner; it was not silently treated as ground lighting",
                    FailureType.UNSUPPORTED,
                    List.of("retry with style=auto or style=ground",
                            "express a separate semantic fixture prerequisite, then retry",
                            "cancel without changing the area"));
            return;
        }
        for (String label : r.protectedLabels) {
            IntentRuntime.Landmark landmark = IntentRuntime.get().landmark(label);
            if (landmark == null) {
                giveUp("unresolved_protected_label",
                        "protected label '" + label + "' is not remembered; lighting paused before placement",
                        FailureType.TARGET_LOST,
                        List.of("remember or resolve the protected area first",
                                "retry with an unambiguous protected_labels set",
                                "cancel without changing the area"));
                return;
            }
            String dimension = landmark.position().dimension();
            if (dimension != null && !dimension.isBlank()
                    && !dimension.equals(player.level().dimension().location().toString())) {
                giveUp("protected_label_other_dimension",
                        "protected label '" + label + "' belongs to another dimension",
                        FailureType.TARGET_LOST,
                        List.of("travel to the protected area's dimension first",
                                "choose the correct same-dimension label"));
                return;
            }
            protectedAnchors.add(new BlockPos(
                    landmark.position().x(), landmark.position().y(), landmark.position().z()));
        }
        if (r.hasExplicitRadius()) {
            explicitScanDx = -r.radius;
            explicitScanDz = -r.radius;
        }
        stage = Stage.OBSERVE;
    }

    @Override protected TaskState onTick() {
        return switch (stage) {
            case OBSERVE -> tickObserve();
            case TRAVEL_SURVEY -> tickSurveyMove();
            case PLAN -> tickPlan();
            case SUPPLY -> tickSupply();
            case BUILD -> tickBuild();
            case SETTLE -> tickSettle();
            case VERIFY -> tickVerify();
        };
    }

    private TaskState tickObserve() {
        if (r.resolveLoadedComponent) return tickConnectedObservation();
        if (!r.hasExplicitRadius()) {
            giveUp("semantic_area_boundary_missing",
                    "no connected-component discovery or player-authored geometric boundary was supplied",
                    FailureType.TARGET_LOST,
                    List.of("retry against an area or landmark seed so the Mod can discover its boundary",
                            "have the player state an explicit geometric boundary",
                            "cancel without changing the world"));
            return TaskState.FAILED;
        }
        return tickExplicitBoundaryObservation();
    }

    private TaskState tickExplicitBoundaryObservation() {
        // 玩家给定半径后逐列调查圆内地面；未加载列保留为范围缺口，当前分支不会自动走过去加载再重扫。
        ClientLevel level = ClientRuntime.requireContext(player).level();
        int budget = COLUMNS_PER_TICK;
        while (budget-- > 0 && !explicitScanComplete) {
            int dx = explicitScanDx;
            int dz = explicitScanDz;
            advanceExplicitScan();
            long distance = (long) dx * dx + (long) dz * dz;
            if (distance > (long) r.radius * r.radius) continue;
            int x = r.center.getX() + dx;
            int z = r.center.getZ() + dz;
            if (!columnLoaded(level, x, z)) {
                unloadedColumns++;
                renewLightingProgress();
                continue;
            }
            loadedColumns++;
            observeColumn(level, x, z);
            renewLightingProgress();
        }
        if (!explicitScanComplete) return TaskState.RUNNING;

        if (unloadedColumns > 0) {
            giveUp("explicit_area_not_fully_loaded",
                    "the player-authored geometric boundary includes unloaded columns; they were "
                            + "not silently omitted from the coverage denominator",
                    FailureType.TARGET_LOST,
                    List.of("move so the full explicit boundary is loaded, then retry",
                            "let MaiCraft discover a connected semantic component instead",
                            "stop without claiming whole-area coverage"));
            return TaskState.FAILED;
        }

        targetCells = new ArrayList<>(samples.values());
        if (targetCells.isEmpty()) {
            giveUp(unloadedColumns > 0 ? "area_not_loaded" : "no_matching_area_cells",
                    unloadedColumns > 0
                            ? "the requested semantic area cannot be resolved from currently loaded cells"
                            : "loaded observations contain no cells matching the requested coverage semantics",
                    FailureType.TARGET_LOST,
                    List.of("travel to or load the target area, then retry",
                            "choose a different coverage policy or area anchor",
                            "cancel without changing the world"));
            return TaskState.FAILED;
        }
        evaluateCurrentLight(level);
        areaBoundaryVerified = true;
        retainAreaProtectionReceipt();
        if (meetsRequirement()) return TaskState.SUCCESS;
        stage = Stage.PLAN;
        return TaskState.RUNNING;
    }

    private void advanceExplicitScan() {
        if (explicitScanDz < r.radius) {
            explicitScanDz++;
            return;
        }
        explicitScanDz = -r.radius;
        if (explicitScanDx < r.radius) explicitScanDx++;
        else explicitScanComplete = true;
    }

    /**
     * 从地标附近发现匹配的连通区域；未指定半径时不猜边界，显式半径仍限制可扩展的水平范围。
     * 每个接纳的格子都必须在区块已加载时读取；未加载边界要么由第一人称移动实际加载，要么在冻结照明方案前报告不可达。
     */
    private TaskState tickConnectedObservation() {
        ClientLevel level = ClientRuntime.requireContext(player).level();
        if (!columnLoaded(level, r.center.getX(), r.center.getZ())) {
            return continueSurveyToward(level, r.center, SurveyMovePurpose.LOAD_ANCHOR,
                    Long.MIN_VALUE);
        }

        if (!componentSeeded) {
            TaskState seedState = tickSeedDiscovery(level);
            if (seedState != null) return seedState;
        }

        int cellBudget = COMPONENT_CELLS_PER_TICK;
        while (cellBudget-- > 0 && !componentCells.isEmpty()) {
            BlockPos requested = componentCells.removeFirst();
            long requestedKey = requested.asLong();
            if (!observedComponentCells.add(requestedKey)) continue;
            if (!insideRequestedBoundary(requested)) continue;
            if (!level.isLoaded(requested)) {
                // 方块入队时已加载，但随后移出了客户端视野；已观察到的相邻格仍是唯一有证据支持的接近路线。
                observedComponentCells.remove(requestedKey);
                continue;
            }
            Sample sample = componentSample(level, requested);
            if (sample == null) continue;
            samples.put(sample.pos().asLong(), sample);
            noteComponentSafety(level, sample.pos());
            addPlacementFootprint(sample.pos());
            renewLightingProgress();

            expandComponentNeighbours(level, sample.pos());
        }
        if (!componentCells.isEmpty()) return TaskState.RUNNING;

        recheckLoadedComponentFrontiers(level);
        if (!componentCells.isEmpty()) return TaskState.RUNNING;
        // FRONTIERS_PER_TICK 只限制每 tick 的 CPU 工作量，不是任务边界。选择实际移动以加载地形前，先处理完当前所有实时已加载事实。
        for (ComponentFrontier frontier : componentFrontiers.values()) {
            if (level.isLoaded(frontier.unknownCell())) return TaskState.RUNNING;
        }
        ComponentFrontier frontier = nextComponentFrontier(level);
        if (frontier != null) {
            long key = frontier.unknownCell().asLong();
            return continueSurveyToward(level, frontier.knownCell(),
                    SurveyMovePurpose.LOAD_COMPONENT_FRONTIER, key);
        }
        if (!componentFrontiers.isEmpty()) {
            giveUp("semantic_area_frontier_unreachable",
                    "the connected semantic component still has unloaded edges, but every "
                            + "evidence-backed first-person approach is currently unreachable",
                    FailureType.NO_PATH,
                    List.of("remove or authorize a semantic route obstacle, then resume",
                            "approach the unresolved side of the area and retry",
                            "stop without claiming whole-area coverage"));
            return TaskState.FAILED;
        }
        return finishConnectedObservation(level);
    }

    private void expandComponentNeighbours(ClientLevel level, BlockPos from) {
        // 直接使用八邻域形态学连接普通连续种植床和高差一格的梯田；绝不把空隙格加入覆盖率分母。
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            inspectComponentLanding(level, from, from.offset(dx, -1, dz));
            inspectComponentLanding(level, from, from.offset(dx, 0, dz));
            inspectComponentLanding(level, from, from.offset(dx, 1, dz));
        }
        // 一格宽的实际通道（例如水渠、土路或可行走过道）可能把同一语义区域切成两个方块连通分量。
        // 仅跨越这条已观察到的一格通道，并要求远侧确实存在匹配方块；这是局部形态连接，不是半径扩张。
        int[][] cardinals = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : cardinals) {
            BlockPos gap = from.offset(direction[0], 0, direction[1]);
            if (!level.isLoaded(gap) || !isObservedServiceGap(level, gap)) continue;
            for (int dy = -1; dy <= 1; dy++) {
                inspectComponentLanding(level, from,
                        from.offset(direction[0] * 2, dy, direction[1] * 2));
            }
        }
    }

    private void inspectComponentLanding(
            ClientLevel level, BlockPos known, BlockPos landing) {
        if (!insideRequestedBoundary(landing)) return;
        if (level.isLoaded(landing)) {
            Sample match = componentSample(level, landing);
            if (match != null && queuedComponentCells.add(match.pos().asLong())) {
                componentCells.addLast(match.pos());
            }
        } else {
            addComponentFrontier(known, landing);
        }
    }

    private boolean isObservedServiceGap(ClientLevel level, BlockPos cell) {
        if (componentSample(level, cell) != null) return false;
        BlockState state = level.getBlockState(cell);
        BlockState below = level.getBlockState(cell.below());
        return state.getFluidState().is(FluidTags.WATER)
                || below.getFluidState().is(FluidTags.WATER)
                || state.getBlock() instanceof DirtPathBlock
                || below.getBlock() instanceof DirtPathBlock
                || (state.getCollisionShape(level, cell).isEmpty()
                        && state.getFluidState().isEmpty()
                        && below.is(BlockTags.DIRT)
                        && below.isFaceSturdy(level, cell.below(), Direction.UP));
    }

    /** 已有真实匹配种子入队时返回 null。 */
    private TaskState tickSeedDiscovery(ClientLevel level) {
        if (!seedSearchInitialized) {
            seedSearchInitialized = true;
            enqueueSeedColumn(r.center.getX(), r.center.getZ(), level);
        }
        int budget = COLUMNS_PER_TICK;
        while (budget-- > 0 && !seedColumns.isEmpty()) {
            BlockPos column = BlockPos.of(seedColumns.removeFirst());
            int x = column.getX();
            int z = column.getZ();
            if (!columnLoaded(level, x, z)) continue;
            seedColumnsObserved++;
            loadedColumns++;
            renewLightingProgress();
            Sample nearest = nearestMatchingSampleInColumn(level, x, z);
            if (nearest != null) {
                componentSeeded = true;
                queuedComponentCells.add(nearest.pos().asLong());
                componentCells.addLast(nearest.pos());
                renewLightingProgress();
                return null;
            }
            enqueueSeedColumn(x + 1, z, level);
            enqueueSeedColumn(x - 1, z, level);
            enqueueSeedColumn(x, z + 1, level);
            enqueueSeedColumn(x, z - 1, level);
        }
        if (!seedColumns.isEmpty()) return TaskState.RUNNING;
        giveUp("no_matching_area_seed",
                "the semantic landmark's complete currently loaded column component contains no "
                        + "cell matching coverage="
                        + r.coverage.name().toLowerCase(Locale.ROOT),
                FailureType.TARGET_LOST,
                List.of("remember a point inside or immediately beside the intended semantic area",
                        "choose the coverage semantics that define the intended cells",
                        "cancel without changing the world"));
        return TaskState.FAILED;
    }

    private void enqueueSeedColumn(int x, int z, ClientLevel level) {
        if (!columnLoaded(level, x, z)) return;
        if (r.hasExplicitRadius() && !insideRequestedBoundary(new BlockPos(x, r.center.getY(), z))) {
            return;
        }
        long key = BlockPos.asLong(x, 0, z);
        if (queuedSeedColumns.add(key)) seedColumns.addLast(key);
    }

    private Sample nearestMatchingSampleInColumn(ClientLevel level, int x, int z) {
        Sample best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int y : observationYs(level, x, z)) {
            Sample candidate = componentSample(level, new BlockPos(x, y, z));
            if (candidate == null) continue;
            int distance = Math.abs(candidate.pos().getY() - r.center.getY());
            if (best == null || distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private Set<Integer> observationYs(ClientLevel level, int x, int z) {
        int surface = Math.clamp(ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z),
                level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 2);
        Set<Integer> ys = new LinkedHashSet<>();
        for (int d = 0; d <= 16; d++) {
            int above = r.center.getY() + d;
            int below = r.center.getY() - d;
            if (above > level.getMinBuildHeight() && above < level.getMaxBuildHeight() - 1) {
                ys.add(above);
            }
            if (below > level.getMinBuildHeight() && below < level.getMaxBuildHeight() - 1) {
                ys.add(below);
            }
        }
        for (int y = surface - 4; y <= surface + 3; y++) {
            if (y > level.getMinBuildHeight() && y < level.getMaxBuildHeight() - 1) ys.add(y);
        }
        return ys;
    }

    private Sample componentSample(ClientLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || !level.isLoaded(pos.below()) || !level.isLoaded(pos.above())) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        boolean matches = r.coverage == SemanticLightAreaTaskRecord.Coverage.CROP_GROWTH
                ? isGrowthCell(state) || level.getBlockState(pos.below()).getBlock() instanceof FarmBlock
                : isWalkable(level, pos);
        if (!matches) return null;
        return new Sample(pos.immutable(), level.getBrightness(LightLayer.BLOCK, pos));
    }

    private void noteComponentSafety(ClientLevel level, BlockPos sample) {
        BlockState state = level.getBlockState(sample);
        BlockState support = level.getBlockState(sample.below());
        String stateReason = sensitiveReason(level, sample, state);
        String supportReason = sensitiveReason(level, sample.below(), support);
        if (stateReason != null) {
            protectedFacts.merge(stateReason, 1, Integer::sum);
            protectedMutationCells.add(sample.immutable());
        }
        if (supportReason != null) {
            protectedFacts.merge(supportReason, 1, Integer::sum);
            protectedMutationCells.add(sample.below().immutable());
        }
        if (isGrowthCell(state)) {
            protectedMutationCells.add(sample.immutable());
            protectedNavigationCells.add(sample.immutable());
        }
        if (support.getBlock() instanceof FarmBlock) {
            protectedMutationCells.add(sample.below().immutable());
            protectedNavigationCells.add(sample.immutable());
        }
    }

    private void addPlacementFootprint(BlockPos sample) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            placementFootprintColumns.add(BlockPos.asLong(
                    sample.getX() + dx, 0, sample.getZ() + dz));
        }
    }

    private void addComponentFrontier(BlockPos known, BlockPos unknown) {
        long key = unknown.asLong();
        if (rejectedComponentFrontiers.contains(key)
                || componentFrontiers.containsKey(key)) return;
        componentFrontiers.put(key,
                new ComponentFrontier(known.immutable(), unknown.immutable()));
        componentFrontierOrder.addLast(key);
        componentFrontiersObserved++;
        unloadedColumns++;
    }

    private void recheckLoadedComponentFrontiers(ClientLevel level) {
        int budget = Math.min(FRONTIERS_PER_TICK, componentFrontierOrder.size());
        while (budget-- > 0 && !componentFrontierOrder.isEmpty()) {
            long key = componentFrontierOrder.removeFirst();
            ComponentFrontier frontier = componentFrontiers.get(key);
            if (frontier == null) continue;
            if (!level.isLoaded(frontier.unknownCell())) {
                componentFrontierOrder.addLast(key);
                continue;
            }
            componentFrontiers.remove(key);
            componentFrontiersLoaded++;
            Sample match = componentSample(level, frontier.unknownCell());
            if (match != null && queuedComponentCells.add(match.pos().asLong())) {
                componentCells.addLast(match.pos());
            } else if (level.isLoaded(frontier.knownCell())) {
                // 加载非成员通道后，紧邻另一侧可能立即出现真实成员。先用新事实重新扩展已知边缘并检查一格形态桥接，再判断区域边界是否闭合。
                expandComponentNeighbours(level, frontier.knownCell());
            }
            renewLightingProgress();
        }
    }

    private ComponentFrontier nextComponentFrontier(ClientLevel level) {
        ComponentFrontier best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (ComponentFrontier frontier : componentFrontiers.values()) {
            long key = frontier.unknownCell().asLong();
            if (rejectedComponentFrontiers.contains(key)) continue;
            double distance = frontier.knownCell().distSqr(player.blockPosition());
            if (best == null || distance < bestDistance) {
                best = frontier;
                bestDistance = distance;
            }
        }
        return best;
    }

    private TaskState finishConnectedObservation(ClientLevel level) {
        targetCells = List.copyOf(samples.values());
        if (targetCells.isEmpty()) {
            giveUp("no_matching_area_cells",
                    "connected-area discovery closed without retaining a matching cell",
                    FailureType.TARGET_LOST,
                    List.of("resolve a different semantic landmark seed",
                            "choose another coverage policy",
                            "cancel without changing the world"));
            return TaskState.FAILED;
        }
        for (Sample sample : targetCells) {
            if (!level.isLoaded(sample.pos())) {
                giveUp("discovered_area_no_longer_loaded",
                        "the connected component was larger than the currently verifiable client view",
                        FailureType.TARGET_LOST,
                        List.of("move near the component center and resume whole-area verification",
                                "increase client view distance if the complete area physically exceeds it",
                                "stop without claiming whole-area coverage"));
                return TaskState.FAILED;
            }
        }
        areaBoundaryVerified = true;
        retainAreaProtectionReceipt();
        evaluateCurrentLight(level);
        if (meetsRequirement()) return TaskState.SUCCESS;
        stage = Stage.PLAN;
        return TaskState.RUNNING;
    }

    private TaskState continueSurveyToward(
            ClientLevel level,
            BlockPos destination,
            SurveyMovePurpose purpose,
            long frontierKey) {
        BlockPos approach = level.isLoaded(destination)
                ? safeSurveyApproach(level, destination) : null;
        if (approach == null) approach = loadedTravelCellToward(level, destination);
        if (approach == null) {
            if (purpose == SurveyMovePurpose.LOAD_COMPONENT_FRONTIER
                    && frontierKey != Long.MIN_VALUE) {
                rejectedComponentFrontiers.add(frontierKey);
                return TaskState.RUNNING;
            }
            giveUp("semantic_area_seed_unreachable",
                    "no new loaded, safe first-person stance leads toward the semantic landmark seed",
                    FailureType.NO_PATH,
                    List.of("clear or authorize the semantic route obstacle, then resume",
                            "move closer to the remembered area and retry",
                            "cancel without changing the world"));
            return TaskState.FAILED;
        }
        startSurveyMove(approach, purpose, frontierKey);
        return TaskState.RUNNING;
    }

    private BlockPos loadedTravelCellToward(ClientLevel level, BlockPos destination) {
        BlockPos current = player.blockPosition();
        double dx = destination.getX() - current.getX();
        double dz = destination.getZ() - current.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0D) return null;
        double farthest = Math.min(64.0D, distance);
        for (double leg = farthest; leg >= Math.min(4.0D, farthest); leg -= 4.0D) {
            int x = (int) Math.round(current.getX() + dx / distance * leg);
            int z = (int) Math.round(current.getZ() + dz / distance * leg);
            BlockPos candidate = safeSurveyApproach(level,
                    new BlockPos(x, current.getY(), z));
            if (candidate != null) return candidate;
        }
        return null;
    }

    private BlockPos safeSurveyApproach(ClientLevel level, BlockPos around) {
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                int x = around.getX() + dx;
                int z = around.getZ() + dz;
                if (!columnLoaded(level, x, z)) continue;
                int surface = Math.clamp(ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z),
                        level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 2);
                for (int d = 0; d <= 8; d++) {
                    int[] ys = d == 0
                            ? new int[]{surface, around.getY()}
                            : new int[]{surface + d, surface - d, around.getY() + d,
                                    around.getY() - d};
                    for (int y : ys) {
                        if (y <= level.getMinBuildHeight()
                                || y >= level.getMaxBuildHeight() - 1) continue;
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (protectedNavigationCells.contains(candidate)
                                || attemptedSurveyMoves.contains(candidate.asLong())) continue;
                        if (isWalkable(level, candidate)) return candidate;
                    }
                }
            }
        }
        return null;
    }

    private void startSurveyMove(
            BlockPos target, SurveyMovePurpose purpose, long frontierKey) {
        String parent = r.getToolCallId() == null ? "light-area" : r.getToolCallId();
        long now = player.level().getGameTime();
        surveyMoveRecord = new MoveToTaskRecord(
                parent + "-internal-boundary-survey-" + (++surveyMoveSerial),
                now + 90L * 20L,
                (double) target.getX(), (double) target.getY(), (double) target.getZ(),
                null, false);
        surveyMoveChild = new MoveToCompanionTask(player, surveyMoveRecord);
        surveyMovePurpose = purpose;
        activeComponentFrontier = frontierKey;
        attemptedSurveyMoves.add(target.asLong());
        surveyLegs++;
        r.extendDeadlineTo(surveyMoveRecord.getDeadlineGameTime());
        stage = Stage.TRAVEL_SURVEY;
    }

    private TaskState tickSurveyMove() {
        TaskState terminal;
        if (player.level().getGameTime() >= surveyMoveRecord.getDeadlineGameTime()) {
            surveyMoveChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = NavigationSafetyContext.withForbiddenBodyCells(
                    protectedNavigationCells, () -> runChild(surveyMoveChild));
        }
        r.extendDeadlineTo(surveyMoveRecord.getDeadlineGameTime());
        if (terminal == null) return TaskState.RUNNING;
        surveyMoveChild.result(terminal);
        if (terminal != TaskState.SUCCESS) {
            surveyLegFailures++;
        } else {
            renewLightingProgress();
        }
        surveyMoveChild = null;
        surveyMoveRecord = null;
        surveyMovePurpose = null;
        activeComponentFrontier = Long.MIN_VALUE;
        stage = Stage.OBSERVE;
        return TaskState.RUNNING;
    }

    private boolean insideRequestedBoundary(BlockPos pos) {
        if (!r.hasExplicitRadius()) return true;
        long dx = (long) pos.getX() - r.center.getX();
        long dz = (long) pos.getZ() - r.center.getZ();
        long radius = r.radius;
        return dx * dx + dz * dz <= radius * radius;
    }

    private void observeColumn(ClientLevel level, int x, int z) {
        int surface = Math.clamp(ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z),
                level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 2);
        int minY = Math.max(level.getMinBuildHeight() + 1, r.center.getY() - 12);
        int maxY = Math.min(level.getMaxBuildHeight() - 2, r.center.getY() + 12);
        Set<Integer> ys = new LinkedHashSet<>();
        for (int y = minY; y <= maxY; y++) ys.add(y);
        for (int y = surface - 3; y <= surface + 2; y++) {
            if (y > level.getMinBuildHeight() && y < level.getMaxBuildHeight() - 1) ys.add(y);
        }
        for (int y : ys) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            String sensitive = sensitiveReason(level, pos, state);
            if (sensitive != null) {
                protectedFacts.merge(sensitive, 1, Integer::sum);
                protectedMutationCells.add(pos.immutable());
            }
            if (isGrowthCell(state)) {
                // 施工路线即使拥有地形修改许可，也不得清除或进入作物格。
                protectedMutationCells.add(pos.immutable());
                protectedNavigationCells.add(pos.immutable());
            } else if (state.getBlock() instanceof FarmBlock) {
                // 禁止角色进入每个已观察耕地上方的身体格，即使该格尚未种植且仍是有效的相邻放置目标。
                // 施工可以点击目标格，但不能把它当作行走或跳跃落脚点。
                protectedMutationCells.add(pos.immutable());
                protectedNavigationCells.add(pos.above().immutable());
            }
            if (r.coverage == SemanticLightAreaTaskRecord.Coverage.CROP_GROWTH) {
                if (isGrowthCell(state)) addSample(level, pos);
                else if (state.getBlock() instanceof FarmBlock) addSample(level, pos.above());
            } else if (isWalkable(level, pos)) {
                addSample(level, pos);
            }
        }
    }

    private void addSample(ClientLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        samples.putIfAbsent(pos.asLong(), new Sample(pos.immutable(),
                level.getBrightness(LightLayer.BLOCK, pos)));
    }

    private TaskState tickPlan() {
        // 根据上一轮实际暗格选灯位；候选传播量只是估计，用于减少重复照亮，最终仍以放置后的方块光验收。
        ClientLevel level = ClientRuntime.requireContext(player).level();
        if (r.hasPlacementBudget() && requestedPlacements >= r.maxPlacements) {
            return placementBudgetReached();
        }
        source = chooseSource();
        if (source == null) {
            giveUp("no_usable_light_source",
                    "no permitted preferred or standard light-emitting BlockItem can meet minimum_light=" + r.minimumLight,
                    FailureType.NO_MATERIAL,
                    List.of("acquire any suitable light-emitting block item",
                            "retry with a carried block_id/light_preferences value",
                            "lower minimum_light only if that outcome is acceptable"));
            return TaskState.FAILED;
        }
        List<Candidate> candidates = candidates(level, source);
        if (candidates.isEmpty()) {
            giveUp("no_safe_placement_candidates",
                    "loaded facts provide no safe supported placement outside protected footprints",
                    FailureType.NO_SUPPORT,
                    List.of("choose another style or a nearby area anchor",
                            "travel so more of the area is loaded",
                            "review protected_labels; no protected block was modified"));
            return TaskState.FAILED;
        }
        String candidateFingerprint = candidateFingerprint(source, candidates);
        if (candidateFingerprint.equals(lastUnproductiveCandidateFingerprint)) {
            return exhausted("a complete observe/build/verify round produced no additional lit "
                    + "cells and the same safe candidate frontier was observed again");
        }

        int stillNeeded = Math.max(1,
                (int) Math.ceil(targetCells.size() * r.coverage.requiredRatio()) - litCells);
        int cap = MAX_PASS_BATCH;
        if (r.hasPlacementBudget()) {
            cap = Math.min(cap, r.maxPlacements - requestedPlacements);
        }
        if (cap <= 0) return placementBudgetReached();
        List<Candidate> selected = greedy(candidates, stillNeeded, cap);
        if (selected.isEmpty()) return exhausted("the safe candidates cannot improve measured dark cells");

        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (Candidate candidate : selected) {
            targets.add(new BuildTaskRecord.Target(
                    candidate.state(), source.item(), candidate.pos(), source.id(),
                    null, null, null));
        }
        boolean consume = !WorkProfile.of(player).freeMaterials();
        int requiredMaterials = targets.stream()
                .mapToInt(BuildTaskRecord.Target::materialCount).sum();
        // 副手火把是本轮真正可用的材料；其他灯具仍沿用施工器可选择的背包范围。
        int availableMaterials = source.item() == Items.TORCH ? PlayerInv.count(player.getInventory(), source.item())
                : PlayerInv.buildableCount(player.getInventory(), source.item());
        if (consume && availableMaterials < requiredMaterials) {
            ResourceLocation sourceId = BuiltInRegistries.ITEM.getKey(source.item());
            pendingSupplySource = sourceId.toString();
            supply.begin(player, r.getToolCallId(), r.getDeadlineGameTime(),
                    new SemanticMaterialSupplyCoordinator.Demand(
                            List.of(sourceId), requiredMaterials, "investigated lighting layout"),
                    r.materialPolicy, r.allowedSources, r.allowHarm, r.protectedLabels,
                    protectedNavigationCells);
            r.extendDeadlineTo(supply.childDeadline());
            supplyRounds++;
            stage = Stage.SUPPLY;
            return TaskState.RUNNING;
        }
        startBuild(targets, consume, candidateFingerprint);
        return TaskState.RUNNING;
    }

    private TaskState tickSupply() {
        SemanticMaterialSupplyCoordinator.Tick tick = supply.tick(player, this::runChild);
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.RUNNING) {
            r.extendDeadlineTo(supply.childDeadline());
            return TaskState.RUNNING;
        }
        supplyReceipts.add(tick.receipt());
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.FAILED) {
            supplyFailure = tick.receipt();
            failureCode = stringValue(tick.receipt().get("failure_code"),
                    "material_supply_failed");
            requiresDecision = true;
            recoveryOptions = recoveryIds(tick.receipt().get("recovery_options"));
            if (recoveryOptions.isEmpty()) recoveryOptions = List.of(
                    "change material source policy", "provide a different light preference",
                    "stop without placing the unsupplied layout");
            pendingSupplySource = null;
            fail("lighting material supply stopped before construction: " + tick.message(),
                    tick.failureType());
            return TaskState.FAILED;
        }
        // 取料返场后场地可能已变化，先锁定刚补到的光源，再重扫范围和暗格，避免直接执行离开前的旧灯位。
        pinnedSuppliedSource = pendingSupplySource;
        pendingSupplySource = null;
        resetObservationForReplan();
        return TaskState.RUNNING;
    }

    private void startBuild(
            List<BuildTaskRecord.Target> targets,
            boolean consume,
            String candidateFingerprint) {
        boolean fastTorches = source.item() == Items.TORCH && consume;
        // 快速轮可能中途缺料；只有实际出手的灯位才算尝试，未提交的候选留给补料后的同一区域续作。
        if (!fastTorches) for (BuildTaskRecord.Target target : targets) attemptedPositions.add(target.pos().asLong());
        String parent = r.getToolCallId() == null ? "light_area" : r.getToolCallId();
        long now = player.level().getGameTime();
        buildRecord = new BuildTaskRecord(
                parent + "-lighting-pass-" + (passes + 1),
                now + BuildTool.timeoutTicksFor(targets.size(), consume),
                targets, false, consume, false);
        // 生存消耗模式的火把走副手快速放置；其他灯具和免费材料模式沿用施工器，最后都复核同一份光照样本。
        if (fastTorches) {
            buildChild = new TorchLightingPass(player, buildRecord, targetCells.stream().map(Sample::pos).toList(),
                    r.minimumLight, protectedMutationCells, protectedNavigationCells);
        } else {
            var builder = new BuildCompanionTask(player, buildRecord);
            builder.protectNavigationCells(protectedNavigationCells);
            buildChild = builder;
        }
        observedBuildProgress = 0;
        passBaselineLitCells = litCells;
        activeCandidateFingerprint = candidateFingerprint;
        if (!fastTorches) requestedPlacements += targets.size();
        passes++;
        r.extendDeadlineTo(buildRecord.getDeadlineGameTime());
        stage = Stage.BUILD;
    }

    private TaskState tickBuild() {
        TaskState terminal = runChild(buildChild);
        propagateBuildProgress();
        if (terminal == null) return TaskState.RUNNING;
        lastBuildResult = buildChild.result(terminal);
        if (buildChild instanceof TorchLightingPass torches) {
            requestedPlacements += torches.attemptedPositions().size();
            torches.attemptedPositions().forEach(pos -> attemptedPositions.add(pos.asLong()));
            // 闸内拒绝的灯位不再进入后续候选；明细跨轮累计进回执，整批零出手时可直接读因。
            for (TorchLightingPass.SiteRejection rejection : torches.rejections()) {
                attemptedPositions.add(rejection.pos().asLong());
                siteRejections.add(Map.of(
                        "pass", passes,
                        "position", List.of(rejection.pos().getX(), rejection.pos().getY(), rejection.pos().getZ()),
                        "gate", rejection.gate(), "detail", rejection.detail()));
            }
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("pass", passes);
        receipt.put("success", lastBuildResult.success());
        receipt.put("message", lastBuildResult.message());
        if (lastBuildResult.data() != null) {
            copyReceiptField(lastBuildResult.data(), receipt, "failure_type", "failure_code",
                    "placed", "remaining", "outcome_uncertain", "rejected_sites");
        }
        childReceipts.add(receipt);
        buildChild = null;
        buildRecord = null;
        // 子轮失败也先等待传播并量光；已确认插下的灯可能已经满足区域目标，不能只凭子轮终态丢掉实际效果。
        settledAt = SETTLE_TICKS;
        stage = Stage.SETTLE;
        return TaskState.RUNNING;
    }

    private TaskState tickSettle() {
        if (settledAt-- > 0) return TaskState.RUNNING;
        verifyIndex = 0;
        verifyLit = 0;
        verifyDark.clear();
        stage = Stage.VERIFY;
        return TaskState.RUNNING;
    }

    private TaskState tickVerify() {
        // 在冻结的目标样本上逐格读方块光；卸载格不能从分母删除，只有完整复测达到所选比例才结束区域任务。
        ClientLevel level = ClientRuntime.requireContext(player).level();
        int budget = VERIFY_PER_TICK;
        while (budget-- > 0 && verifyIndex < targetCells.size()) {
            Sample original = targetCells.get(verifyIndex++);
            if (!level.isLoaded(original.pos())) {
                giveUp("verification_area_unloaded",
                        "part of the observed area unloaded before actual block-light verification",
                        FailureType.TARGET_LOST,
                        List.of("return to the area and retry", "use a smaller radius"));
                return TaskState.FAILED;
            }
            int light = level.getBrightness(LightLayer.BLOCK, original.pos());
            if (light >= r.minimumLight) verifyLit++;
            else verifyDark.add(new Sample(original.pos(), light));
        }
        if (verifyIndex < targetCells.size()) return TaskState.RUNNING;
        litCells = verifyLit;
        darkCells = List.copyOf(verifyDark);
        achievedCoverage = targetCells.isEmpty() ? 0.0D
                : (double) litCells / (double) targetCells.size();
        if (litCells > passBaselineLitCells) {
            lastUnproductiveCandidateFingerprint = null;
            renewLightingProgress();
        } else {
            lastUnproductiveCandidateFingerprint = activeCandidateFingerprint;
        }
        activeCandidateFingerprint = null;
        if (meetsRequirement()) return TaskState.SUCCESS;
        stage = Stage.PLAN;
        return TaskState.RUNNING;
    }

    private void propagateBuildProgress() {
        if (buildRecord == null) return;
        r.extendDeadlineTo(buildRecord.getDeadlineGameTime());
        int current = buildRecord.completed() + buildRecord.placed() + buildRecord.broken();
        if (current > observedBuildProgress) {
            observedBuildProgress = current;
            renewLightingProgress();
        }
    }

    private void renewLightingProgress() {
        r.extendDeadlineTo(player.level().getGameTime() + LIGHTING_PROGRESS_LEASE_TICKS);
    }

    private void evaluateCurrentLight(ClientLevel level) {
        List<Sample> dark = new ArrayList<>();
        int lit = 0;
        for (Sample sample : targetCells) {
            int light = level.getBrightness(LightLayer.BLOCK, sample.pos());
            if (light >= r.minimumLight) lit++;
            else dark.add(new Sample(sample.pos(), light));
        }
        litCells = lit;
        darkCells = List.copyOf(dark);
        achievedCoverage = (double) lit / (double) targetCells.size();
    }

    private boolean meetsRequirement() {
        return areaBoundaryVerified
                && achievedCoverage + 1.0E-9D >= r.coverage.requiredRatio();
    }

    private LightSource chooseSource() {
        // 已补到的光源优先，其次比较显式偏好、随身灯具和默认备选；无法解析的偏好会被略过，而不是固定材质约束。
        boolean free = WorkProfile.of(player).freeMaterials();
        Map<Item, Integer> carried = new HashMap<>();
        for (int slot = 0; slot < Math.min(
                PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size()); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) carried.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        if (player.getOffhandItem().is(Items.TORCH)) carried.merge(Items.TORCH, player.getOffhandItem().getCount(), Integer::sum);
        List<Item> ordered = new ArrayList<>();
        addItem(pinnedSuppliedSource, ordered);
        for (String raw : r.lightPreferences) {
            addItem(raw, ordered);
        }
        carried.keySet().stream()
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .forEach(ordered::add);
        for (String fallback : DEFAULT_LIGHT_IDS) addItem(fallback, ordered);
        if (free) {
            for (Item item : BuiltInRegistries.ITEM) if (item instanceof BlockItem) ordered.add(item);
        }
        LightSource best = null;
        Set<Item> seen = new HashSet<>();
        for (Item item : ordered) {
            if (!seen.add(item) || !(item instanceof BlockItem blockItem)) continue;
            int available = free ? Integer.MAX_VALUE : carried.getOrDefault(item, 0);
            Block block = blockItem.getBlock();
            int emission = block.getStateDefinition().getPossibleStates().stream()
                    .mapToInt(BlockState::getLightEmission).max().orElse(0);
            if (emission < r.minimumLight) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            int priority = sourcePriority(id, available > 0);
            LightSource candidate = new LightSource(
                    item, block, id, emission, available, priority);
            if (best == null || priority < best.priority()
                    || (priority == best.priority()
                            && emission > best.emission())) best = candidate;
        }
        return best;
    }

    private int sourcePriority(String id, boolean carried) {
        if (id.equals(pinnedSuppliedSource)) return -1;
        // 未指定其他灯具时固定默认火把，避免背包里一块稀有光源让整个基地改用昂贵材料。
        if (r.lightPreferences.isEmpty() && id.equals("minecraft:torch")) return 0;
        for (int i = 0; i < r.lightPreferences.size(); i++) {
            if (id.equals(r.lightPreferences.get(i))) return i;
        }
        if (carried) return r.lightPreferences.size() + 1;
        int fallback = DEFAULT_LIGHT_IDS.indexOf(id);
        if (fallback >= 0) return r.lightPreferences.size() + 1 + fallback;
        return r.lightPreferences.size() + DEFAULT_LIGHT_IDS.size() + 2;
    }

    private static void addItem(String raw, List<Item> output) {
        if (raw == null || raw.isBlank()) return;
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) return;
        BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> {
            if (item instanceof BlockItem) output.add(item);
        });
        BuiltInRegistries.BLOCK.getOptional(id).ifPresent(block -> {
            Item item = block.asItem();
            if (item instanceof BlockItem) output.add(item);
        });
    }

    private void resetObservationForReplan() {
        explicitScanDx = r.hasExplicitRadius() ? -r.radius : 0;
        explicitScanDz = r.hasExplicitRadius() ? -r.radius : 0;
        explicitScanComplete = false;
        unloadedColumns = 0;
        loadedColumns = 0;
        samples.clear();
        protectedFacts.clear();
        protectedMutationCells.clear();
        protectedNavigationCells.clear();
        seedColumns.clear();
        queuedSeedColumns.clear();
        componentCells.clear();
        queuedComponentCells.clear();
        observedComponentCells.clear();
        componentFrontiers.clear();
        componentFrontierOrder.clear();
        rejectedComponentFrontiers.clear();
        placementFootprintColumns.clear();
        attemptedSurveyMoves.clear();
        seedSearchInitialized = false;
        componentSeeded = false;
        areaBoundaryVerified = false;
        seedColumnsObserved = 0;
        componentFrontiersObserved = 0;
        componentFrontiersLoaded = 0;
        surveyLegs = 0;
        surveyLegFailures = 0;
        targetCells = List.of();
        darkCells = List.of();
        achievedCoverage = 0.0D;
        litCells = 0;
        verifyIndex = 0;
        verifyLit = 0;
        verifyDark.clear();
        source = null;
        stage = Stage.OBSERVE;
    }

    private void retainAreaProtectionReceipt() {
        if (r.semanticTarget == null) return;
        List<Long> mutation = protectedMutationCells.stream()
                .map(BlockPos::asLong).sorted().toList();
        List<Long> body = protectedNavigationCells.stream()
                .map(BlockPos::asLong).sorted().toList();
        if (mutation.isEmpty() && body.isEmpty()) return;
        r.retainInternalAreaProtection(new InternalAreaProtectionReceipt.Footprint(
                r.semanticTarget,
                player.level().dimension().location().toString(),
                mutation,
                body));
    }

    private List<Candidate> candidates(ClientLevel level, LightSource light) {
        Map<Long, Candidate> byPos = new LinkedHashMap<>();
        int[] distances = {1, 2, 4, 6};
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (Sample dark : darkCells) {
            if (r.coverage == SemanticLightAreaTaskRecord.Coverage.CROP_GROWTH) {
                Candidate exact = groundCandidate(level, light, dark.pos());
                if (exact != null) byPos.putIfAbsent(exact.pos().asLong(), exact);
            }
            for (int distance : distances) {
                for (int[] direction : directions) {
                    int x = dark.pos().getX() + direction[0] * distance;
                    int z = dark.pos().getZ() + direction[1] * distance;
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos pos = new BlockPos(x, dark.pos().getY() + dy, z);
                        Candidate candidate = groundCandidate(level, light, pos);
                        if (candidate != null) byPos.putIfAbsent(pos.asLong(), candidate);
                        if (byPos.size() >= MAX_CANDIDATES) break;
                    }
                    if (byPos.size() >= MAX_CANDIDATES) break;
                }
                if (byPos.size() >= MAX_CANDIDATES) break;
            }
            if (byPos.size() >= MAX_CANDIDATES) break;
        }
        List<Candidate> result = new ArrayList<>();
        for (Candidate candidate : byPos.values()) {
            Set<Long> covers = new HashSet<>();
            for (Sample dark : darkCells) {
                if (candidate.state().getLightEmission()
                        - candidate.pos().distManhattan(dark.pos()) >= r.minimumLight) {
                    covers.add(dark.pos().asLong());
                }
            }
            if (!covers.isEmpty()) result.add(new Candidate(candidate.pos(), candidate.state(),
                    candidate.preferenceScore(), Set.copyOf(covers)));
        }
        return result;
    }

    private static String candidateFingerprint(
            LightSource light, List<Candidate> candidates) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < light.id().length(); i++) {
            hash ^= light.id().charAt(i);
            hash *= 0x100000001b3L;
        }
        List<Long> positions = candidates.stream()
                .map(candidate -> candidate.pos().asLong())
                .sorted()
                .toList();
        for (long position : positions) {
            hash ^= position;
            hash *= 0x100000001b3L;
        }
        return light.id() + ':' + positions.size() + ':' + Long.toUnsignedString(hash, 16);
    }

    private Candidate groundCandidate(ClientLevel level, LightSource light, BlockPos pos) {
        if (!inside(pos) || attemptedPositions.contains(pos.asLong())
                || !level.isLoaded(pos) || !level.isLoaded(pos.below())) return null;
        BlockState current = level.getBlockState(pos);
        BlockState support = level.getBlockState(pos.below());
        if (!current.canBeReplaced() || !current.getFluidState().isEmpty()) return null;
        String currentSensitive = sensitiveReason(level, pos, current);
        String supportSensitive = sensitiveReason(level, pos.below(), support);
        boolean unplantedFarmland = r.coverage == SemanticLightAreaTaskRecord.Coverage.CROP_GROWTH
                && (r.style == SemanticLightAreaTaskRecord.Style.AUTO
                        || r.style == SemanticLightAreaTaskRecord.Style.GROUND)
                && current.isAir() && support.getBlock() instanceof FarmBlock;
        if (currentSensitive != null
                || (supportSensitive != null
                        && !(unplantedFarmland && "cultivated_land".equals(supportSensitive)))
                || protectedByLabel(pos)
                || (!unplantedFarmland && narrowRoute(level, pos))) return null;
        if (!unplantedFarmland && !support.isFaceSturdy(level, pos.below(), Direction.UP)) return null;
        BlockState chosen = null;
        int emission = -1;
        for (BlockState state : light.block().getStateDefinition().getPossibleStates()) {
            if (state.hasProperty(BlockStateProperties.HANGING)
                    && state.getValue(BlockStateProperties.HANGING)) continue;
            if (state.getLightEmission() < r.minimumLight || !state.canSurvive(level, pos)) continue;
            if (state.getLightEmission() > emission) {
                chosen = state;
                emission = state.getLightEmission();
            }
        }
        int preferenceScore = placementPreferenceScore(pos, unplantedFarmland);
        return chosen == null ? null : new Candidate(
                pos.immutable(), chosen, preferenceScore, Set.of());
    }

    private int placementPreferenceScore(BlockPos pos, boolean unplantedFarmland) {
        int distanceFromCenter = horizontalDistanceSquared(pos, r.center);
        return switch (r.placementPreference) {
            case CENTRAL_UNPLANTED ->
                    (unplantedFarmland ? 1_000_000 : 0) - distanceFromCenter;
            case UNOBTRUSIVE -> distanceFromCenter;
            case COVERAGE_OPTIMAL ->
                    r.style == SemanticLightAreaTaskRecord.Style.UNOBTRUSIVE
                            ? distanceFromCenter : 0;
        };
    }

    private List<Candidate> greedy(List<Candidate> candidates, int needed, int cap) {
        List<Candidate> selected = new ArrayList<>();
        Set<Long> covered = new HashSet<>();
        while (selected.size() < cap && covered.size() < needed) {
            Candidate best = null;
            int bestGain = 0;
            for (Candidate candidate : candidates) {
                if (selected.contains(candidate)) continue;
                int gain = 0;
                for (long cell : candidate.covers()) if (!covered.contains(cell)) gain++;
                // 先比较对实测暗格的预计覆盖增益，再以放置偏好打破平局；距离估算未计遮挡，不能当作已量得的照明效果。
                if (gain > bestGain || gain == bestGain && gain > 0
                        && (best == null
                                || candidate.preferenceScore() > best.preferenceScore())) {
                    best = candidate;
                    bestGain = gain;
                }
            }
            if (best == null) break;
            selected.add(best);
            covered.addAll(best.covers());
        }
        return selected;
    }

    private boolean isGrowthCell(BlockState state) {
        return state.is(BlockTags.CROPS) || state.getBlock() instanceof CropBlock;
    }

    private boolean isWalkable(ClientLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.above()) || !level.isLoaded(feet.below())) return false;
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        BlockState support = level.getBlockState(feet.below());
        return feetState.getCollisionShape(level, feet).isEmpty()
                && headState.getCollisionShape(level, feet.above()).isEmpty()
                && feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && !support.getCollisionShape(level, feet.below()).isEmpty()
                && !support.getFluidState().is(FluidTags.LAVA);
    }

    private boolean narrowRoute(ClientLevel level, BlockPos cell) {
        if (!isWalkable(level, cell)) return false;
        int exits = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (isWalkable(level, cell.relative(direction))) exits++;
        }
        return exits <= 2;
    }

    private String sensitiveReason(ClientLevel level, BlockPos pos, BlockState state) {
        if (protectedByLabel(pos)) return "protected_label";
        if (state.hasBlockEntity()) return "block_entity";
        if (!state.getFluidState().isEmpty()) return "fluid";
        if (isGrowthCell(state) || state.getBlock() instanceof FarmBlock) return "cultivated_land";
        if (state.getBlock() instanceof DirtPathBlock) return "route";
        if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)
                || state.is(BlockTags.PORTALS) || state.getFluidState().is(FluidTags.LAVA)) return "hazard";
        if (state.isSignalSource()) return "mechanical_or_signal";
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id != null && !"minecraft".equals(id.getNamespace()) && !state.isAir()) {
            return "modded_or_mechanical";
        }
        return null;
    }

    private boolean protectedByLabel(BlockPos pos) {
        // 本任务把每个同维度保护地标扩成各轴正负四格的范围；名称本身没有提供任意形状的完整基地边界。
        for (BlockPos anchor : protectedAnchors) {
            if (Math.abs(anchor.getX() - pos.getX()) <= PROTECTED_LABEL_RADIUS
                    && Math.abs(anchor.getY() - pos.getY()) <= PROTECTED_LABEL_RADIUS
                    && Math.abs(anchor.getZ() - pos.getZ()) <= PROTECTED_LABEL_RADIUS) return true;
        }
        return false;
    }

    private boolean inside(BlockPos pos) {
        if (r.hasExplicitRadius()) return insideRequestedBoundary(pos);
        return placementFootprintColumns.contains(
                BlockPos.asLong(pos.getX(), 0, pos.getZ()));
    }

    private static int horizontalDistanceSquared(BlockPos left, BlockPos right) {
        int dx = left.getX() - right.getX();
        int dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private boolean columnLoaded(ClientLevel level, int x, int z) {
        int y = Math.clamp(r.center.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        return level.isLoaded(new BlockPos(x, y, z));
    }

    private TaskState exhausted(String reason) {
        // 失败话术区分「一轮都没出手」与「出手后仍不达标」；调用方不能把从未尝试的放置误读成放置过但没照亮。
        String attempts = requestedPlacements == 0
                ? "no placement attempt was ever submitted; the placement gate rejected every planned site "
                        + "before a native action was sent"
                : "requested_placements=" + requestedPlacements + " across " + passes + " build pass(es)";
        if (!siteRejections.isEmpty()) {
            attempts += "; per-site rejections: " + siteRejections.size()
                    + " (see placement_rejections for each site's gate and reason)";
        }
        List<String> options = new ArrayList<>(List.of("supply a different light source or style",
                "use a smaller area or lower required coverage",
                "inspect the aggregate dark-area evidence and choose a semantic prerequisite"));
        if (requestedPlacements == 0) {
            options.add("retry one dark-adjacent site with maicraft:place_block to expose the qualification mismatch");
        }
        giveUp("verified_coverage_not_reached", reason + "; actual achieved_coverage="
                        + String.format(Locale.ROOT, "%.3f", achievedCoverage)
                        + "; placement attempts: " + attempts,
                FailureType.NO_SUPPORT,
                List.copyOf(options));
        return TaskState.FAILED;
    }

    private TaskState placementBudgetReached() {
        giveUp("placement_budget_reached",
                "the explicit max_placements=" + r.maxPlacements
                        + " decision boundary was reached at achieved_coverage="
                        + String.format(Locale.ROOT, "%.3f", achievedCoverage),
                FailureType.INTERRUPTED,
                List.of("authorize a larger max_placements budget",
                        "change the light source, style or required coverage",
                        "stop with the already confirmed lighting changes"));
        return TaskState.FAILED;
    }

    private void giveUp(String code, String message, FailureType type, List<String> options) {
        failureCode = code;
        requiresDecision = true;
        recoveryOptions = List.copyOf(options);
        fail(message, type);
    }

    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("verified", meetsRequirement());
        data.put("semantic_target", r.semanticTarget == null ? "loaded_area" : r.semanticTarget);
        data.put("scope", r.resolveLoadedComponent
                ? "live_loaded_connected_component_with_first_person_frontier_loading"
                : "explicit_player_authored_geometric_boundary");
        data.put("area_boundary_verified", areaBoundaryVerified);
        data.put("boundary_source", r.hasExplicitRadius()
                ? "explicit_player_radius" : "observed_connected_component_closure");
        if (r.hasExplicitRadius()) data.put("radius", r.radius);
        data.put("minimum_light", r.minimumLight);
        data.put("coverage", r.coverage.name().toLowerCase(Locale.ROOT));
        data.put("placement_preference",
                r.placementPreference.name().toLowerCase(Locale.ROOT));
        data.put("required_coverage", r.coverage.requiredRatio());
        data.put("achieved_coverage", achievedCoverage);
        data.put("target_cells", targetCells.size());
        data.put("lit_cells", litCells);
        data.put("dark_cell_count", darkCells.size());
        // 默认回执直接交付未达标格与实测亮度，模型无需从旧规划或理论覆盖反推补漏位置。
        data.put("lighting_observation", Map.of("minimum_required", r.minimumLight,
                "minimum_observed_block_light", targetCells.stream().mapToInt(sample -> player.level().isLoaded(sample.pos())
                        ? player.level().getBrightness(LightLayer.BLOCK, sample.pos()) : -1).min().orElse(-1),
                "dark_cells", darkCells.stream().map(sample -> Map.of("position",
                        List.of(sample.pos().getX(), sample.pos().getY(), sample.pos().getZ()), "block_light", sample.light())).toList(),
                "sampled_cells", targetCells.size(), "lit_cells", litCells, "boundary_verified", areaBoundaryVerified));
        data.put("loaded_columns", loadedColumns);
        data.put("unloaded_columns", unloadedColumns);
        data.put("seed_columns_observed", seedColumnsObserved);
        data.put("component_frontiers_observed", componentFrontiersObserved);
        data.put("component_frontiers_loaded", componentFrontiersLoaded);
        data.put("unresolved_component_frontiers", componentFrontiers.size());
        data.put("survey_legs", surveyLegs);
        data.put("survey_leg_failures", surveyLegFailures);
        data.put("passes", passes);
        data.put("requested_placements", requestedPlacements);
        data.put("placement_rejections", List.copyOf(siteRejections));
        data.put("placement_budget_explicit", r.hasPlacementBudget());
        if (r.hasPlacementBudget()) data.put("max_placements", r.maxPlacements);
        data.put("protected_footprint_facts", Map.copyOf(protectedFacts));
        data.put("build_receipts", List.copyOf(childReceipts));
        data.put("material_policy", r.materialPolicy.id());
        data.put("supply_rounds", supplyRounds);
        data.put("supply_receipts", List.copyOf(supplyReceipts));
        if (!supplyFailure.isEmpty()) data.put("supply_failure", supplyFailure);
        if (source != null) data.put("light_source", source.id());
        if (failureCode != null) {
            data.put("failure_code", failureCode);
            data.put("requires_decision", requiresDecision);
            data.put("recovery_options", recoveryOptions);
        }
        return data;
    }

    @Override protected String successMessage() {
        return "semantic area boundary and actual block light verified across "
                + litCells + "/" + targetCells.size()
                + " semantic area cells (coverage "
                + String.format(Locale.ROOT, "%.1f%%", achievedCoverage * 100.0D) + ")";
    }

    @Override protected String timeoutMessage() {
        return "semantic lighting timed out before actual block-light verification completed";
    }

    /** 面板行动行的一句话汇报；阶段来自当前 {@link Stage}，灯具名是正在出手的本地化物品名。 */
    @Override
    public String describeCurrentAction() {
        return switch (stage == null ? Stage.OBSERVE : stage) {
            case OBSERVE -> "正在观察照明区域的光照样本";
            case TRAVEL_SURVEY -> "正在前往暗区边缘查看光照";
            case PLAN -> "正在规划灯位布局";
            case SUPPLY -> "正在补充照明材料";
            case BUILD -> source == null ? "正在放置灯具"
                    : "正在放置 " + new ItemStack(source.item()).getHoverName().getString();
            case SETTLE -> "正在等待光照变化稳定";
            case VERIFY -> "正在复核实际光照覆盖";
        };
    }

    @Override protected String cancelledMessage() {
        return "semantic lighting was interrupted; already confirmed placements remain";
    }

    @Override protected void cleanup() {
        // 结束时停掉勘测、放置和供料子任务，不拆回已放灯具；当前子轮在这里结算，但其结果尚未追加到父级 build_receipts。
        if (surveyMoveChild != null) {
            surveyMoveChild.stop(player, Task.StopReason.REPLACED);
            surveyMoveChild.result(TaskState.CANCELLED);
            surveyMoveChild = null;
        }
        surveyMoveRecord = null;
        if (buildChild != null) {
            buildChild.stop(player, Task.StopReason.REPLACED);
            buildChild.result(TaskState.CANCELLED);
            buildChild = null;
        }
        buildRecord = null;
        if (supply.active()) supply.cancel(player);
        super.cleanup();
    }

    private static void copyReceiptField(
            Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String key : keys) if (from.containsKey(key)) to.put(key, from.get(key));
    }

    private static String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static List<String> recoveryIds(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map && map.get("id") != null) {
                result.add(map.get("id").toString());
            } else if (entry != null) result.add(entry.toString());
        }
        return List.copyOf(result);
    }
}
