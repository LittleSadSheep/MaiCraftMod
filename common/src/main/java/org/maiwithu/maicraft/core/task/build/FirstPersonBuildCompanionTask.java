// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.build.BuildValidity;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;

import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.InternalPositionReceipt;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import net.minecraft.world.level.block.FallingBlock;
import org.maiwithu.maicraft.client.preview.PreviewSession;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineSession;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.core.pathing.settings.ClearanceWhitelist;
import org.maiwithu.maicraft.core.task.acquire.WorkToolPreparation;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;

/** 实际施工：先逐格检查，再走到合适位置拿材料、瞄准、放置；最后复查成品并拆掉自己搭的临时支撑。 */
class FirstPersonBuildCompanionTask extends AbstractCompanionTask<BuildTaskRecord>
        implements BuildPlacementRegistry.Provider, PlayerNav.ContextProvider {
    /** 真正确认挖掉、放下或完成更多格子时，给施工再留两分钟；单纯原地空转不会因此无限续期。 */
    private static final long BUILD_PROGRESS_LEASE_TICKS = 2L * 60L * 20L;
    private static final int PREFLIGHT_BUDGET = 24;
    private static final int USE_TIMEOUT = 40;

    // 刨坑 -> 出坑 -> 拿材料 -> 建房；每一步都等真实游戏操作确认后，再交给下一步接管角色。
    private enum Phase { PREFLIGHT, CLEARANCE_REPORT, EXCAVATE, EXCAVATE_EXIT, SELECT, CLEAR_NAV, CLEAR, CLEAR_RELEASE, PLACE_NAV, WORKSITE, SELECT_ITEM,
        AIM, WAIT_USE, EDGE_RETURN, CLEAR_CREATIVE, VERIFY, SCAFFOLD_SELECT, SCAFFOLD_NAV, SCAFFOLD_BREAK, SCAFFOLD_DESCENT, SCAFFOLD_ACCESS, FINAL_STATE, ROUTE_VERIFY }
    private record CellPlan(BuildTaskRecord.Target target,
                            List<BuildPlacementGeometry.GeneratedCell> generated) {
        CellPlan { generated = List.copyOf(generated); }
    }
    private record PlacementAttemptSignature(long playerFeet, long worldStateHash) {}
    private record ObservedCell(long pos, BlockState state) {}
    private record VerificationFailureSignature(List<ObservedCell> cells) {
        VerificationFailureSignature { cells = List.copyOf(cells); }
    }

    private final BuildCellRules rules;
    private final BuildInventory inventory;
    private final BlockDigger digger;
    private final BuildExcavationFrontier excavation = new BuildExcavationFrontier();
    private final Map<Long, CellPlan> excavationOwners = new HashMap<>();
    private final Set<BlockPos> excavationAuthority;
    private boolean excavating;
    private final BuildExcavationTools excavationTools;
    private UltimineSession ultimine;
    private boolean ultimineArmed;
    private String ultimineFailure;
    private int ultimineBatches;
    private int ultimineHeldTicks;
    private Map<String, Object> lastUltimineHold = Map.of();
    private Map<String, Object> ultimineEvidence = Map.of();
    private BlockPos excavationExit;
    private boolean supplyAccessReady;
    private Phase afterExcavationExit;
    private final BuildExcavationSpoilSupply spoilSupply = new BuildExcavationSpoilSupply();
    private final List<Map<String, Object>> spoilReceipts = new ArrayList<>();
    private final BuildFoodPreparation foodPreparation = new BuildFoodPreparation();
    private final BiFunction<LocalPlayer, Double, HitResult> placementRay;
    private final Map<Long, BuildTaskRecord.Target> targets = new LinkedHashMap<>();
    private final LongOpenHashSet protectedCells = new LongOpenHashSet();
    private final LongOpenHashSet scaffoldAirCells = new LongOpenHashSet();
    private final LongOpenHashSet forbiddenBodyCells = new LongOpenHashSet();
    /** 从前序语义步骤继承的区域格；与蓝图 sacred 格不同，这里的可变目标会被拒绝。 */
    private final LongOpenHashSet inheritedProtectedMutationCells = new LongOpenHashSet();
    private final LongOpenHashSet completed = new LongOpenHashSet();
    private final List<BuildTaskRecord.Target> preflightOrder = new ArrayList<>();
    private final List<CellPlan> plans = new ArrayList<>();
    private final Map<Long, BuildTaskRecord.Target> temporaryTargets = new LinkedHashMap<>();
    private final Map<Long, CellPlan> plansByPrimary = new LinkedHashMap<>();
    private CellPlan supportedCell;
    private List<BlockPos> supportChain = List.of();
    private BlockState supportMaterial;
    private final Map<Item, Integer> required = new LinkedHashMap<>();
    private final Map<Item, Integer> missing = new LinkedHashMap<>();
    private final List<Map<String, Object>> unsupported = new ArrayList<>();
    private final List<Map<String, Object>> blocked = new ArrayList<>();
    private final List<Map<String, Object>> targetDiagnostics = new ArrayList<>();
    private final LinkedHashSet<BlockPos> scaffolds = new LinkedHashSet<>();
    private final Map<Long, Set<PlacementAttemptSignature>> exhaustedPlacementStates =
            new HashMap<>();
    private final Set<VerificationFailureSignature> exhaustedVerificationStates = new HashSet<>();
    private final Map<Long, Integer> finalStateAttempts = new HashMap<>();
    private BuildDoorStateRepair doorRepair;
    private int finalStateAt;

    private Phase phase = Phase.PREFLIGHT;
    private boolean preflightDone, providerRegistered, uncertain;
    private int preflightAt, queueAt, clearAt, gestureAt, useCount;
    private BuildPlacementConfirmation lastUseConfirmation;
    private int verifyAt, scaffoldAt;
    private List<CellPlan> queue = new ArrayList<>();
    private CellPlan cell;
    private List<BlockPos> clearQueue = List.of();
    private BlockPos clearing;
    private List<BuildPlacementGeometry.Gesture> liveGestures = List.of();
    private BuildPlacementGeometry.PlanSearch gestureSearch;
    private BuildPlacementGeometry.PlanProgress gestureProgress;
    private BuildPlacementGeometry.Gesture gesture;
    private boolean gestureFromCurrent;
    private Vec3 placementWalkTarget;
    private BuildWorksitePlanner.Search worksiteSearch;
    private BuildWorksitePlanner.Worksite worksite;
    private BuildWorksitePlanner.Progress worksiteProgress;
    private int worksiteRouteAt;
    private int worksitePass, worksiteAttempts;
    private final BuildWorksiteProgress worksiteMovement = new BuildWorksiteProgress();
    private boolean worksiteSearched;
    private final Set<BlockPos> rejectedWorksites = new HashSet<>();
    private final PlacementAttemptLedger placementAttempts = new PlacementAttemptLedger();
    private final BuildStanceNavigation stanceNavigation = new BuildStanceNavigation(this);
    private BuildPlacementAccessDrive placementAccess;
    private BlockPos placementAccessTarget;
    private String edgeReturnFailure, edgeReturnFailureCode;
    private Map<String, Object> temporarySupportDemand = Map.of();
    private boolean layerKnown;
    private int constructionLayer = Integer.MAX_VALUE;
    private BuildRegions regions;
    private int constructionRegion;
    private BlockPos lastPlacedTarget;
    private BlockPos footingSearchAfter;
    private final BuildAimProgress aimProgress = new BuildAimProgress();
    private String aimWaitReason = "not_aiming", aimHit = "not_checked";
    private Map<String, Object> lastPlacementRejection = Map.of();
    private Map<String, Object> lastAimObservation = Map.of();
    private double aimError;
    private NativeActionReceipt useReceipt;
    private final CreativeBuildMaterialSupply creativeMaterials = new CreativeBuildMaterialSupply();
    private BuildTraversabilityVerifier.Result traversabilityResult;
    private BuildTraversabilityVerifier.Verification traversabilityScan;
    private FirstPersonActionGate selection = new FirstPersonActionGate();
    private final ActualViewConvergenceGate aimConvergence = new ActualViewConvergenceGate();
    private final BuildPlacementSettling placementSettling = new BuildPlacementSettling();
    // 镜头收敛不代表身体落稳；另记实际脚位，避免登阶半空的点击见证在落回后反复失败。
    private final BuildPlacementFooting placementFooting = new BuildPlacementFooting();
    private Vec3 placementProofFeet;
    private BuildPlacementGeometry.Gesture placementProofGesture;
    private final LinkedHashSet<Long> verifyFailed = new LinkedHashSet<>();
    private final List<ObservedCell> verifyFailureStates = new ArrayList<>();
    private List<BlockPos> scaffoldQueue = List.of();
    private final LinkedHashSet<BlockPos> deferredScaffolds = new LinkedHashSet<>();
    private int scaffoldCleanupPasses, scaffoldPassRemovals, scaffoldConfirmedRemovals;
    private Map<String, Object> lastDeferredScaffold = Map.of();
    private BuildScaffoldCleanup scaffoldCleanup;
    private BuildScaffoldDescentDrive scaffoldDescent;
    private final Set<BlockPos> rejectedScaffoldDescents = new HashSet<>();
    private BuildScaffoldCleanupAccess scaffoldAccess;
    private final Map<BlockPos, NavGoal> scaffoldAccessGoals = new LinkedHashMap<>();
    private final Set<BlockPos> attemptedScaffoldAccess = new HashSet<>();
    private int scaffoldAccessAttempts, cleanupNewSupports;
    private Map<String, Object> lastScaffoldAccess = Map.of();
    private BlockPos scaffold, siteMin, siteMax, failurePos;
    private String failureCode, note = "all native actions re-verified";
    private BuildClearanceSurvey clearanceSurvey;
    private BlockPos clearanceDeniedAt;
    private Map<String, Object> clearanceTrigger = Map.of();

    FirstPersonBuildCompanionTask(LocalPlayer player, BuildTaskRecord record) {
        this(player, record, Interaction::nativeRaytrace);
    }

    FirstPersonBuildCompanionTask(LocalPlayer player, BuildTaskRecord record,
            BiFunction<LocalPlayer, Double, HitResult> placementRay) {
        // 保存全部目标并按施工顺序排序，记住不能让导航破坏的格子，以及上一批留下的临时支撑。
        super(player, record);
        this.placementRay = placementRay;
        excavationAuthority = record.targets.stream().map(BuildTaskRecord.Target::pos)
                .collect(Collectors.toUnmodifiableSet());
        rules = new BuildCellRules(player, record);
        inventory = new BuildInventory(player);
        digger = new BlockDigger(player);
        excavationTools = new BuildExcavationTools(record);
        digger.beforeBreak(this::prepareExcavationBreak);
        for (BuildTaskRecord.Target target : record.targets) {
            targets.put(target.pos().asLong(), target);
            protectedCells.add(target.pos().asLong());
            if (BuildCellRules.isAirTarget(target)) scaffoldAirCells.add(target.pos().asLong());
            preflightOrder.add(target);
        }
        inheritedProtectedMutationCells.addAll(
                NavigationSafetyContext.protectedMutationCells());
        protectedCells.addAll(inheritedProtectedMutationCells);
        forbiddenBodyCells.addAll(NavigationSafetyContext.forbiddenBodyCells());
        addProtectedNavigationCells(record.protectedNavigationCells());
        scaffolds.addAll(record.scaffoldLedger().snapshot().keySet());
        preflightOrder.sort(BuildOrder.BUILD_ORDER);
    }

    /** 开工前补充不能碰的位置；开始检查或施工后不允许再偷偷更换这份保护范围。 */
    protected final void addProtectedNavigationCells(Iterable<BlockPos> cells) {
        if (preflightAt != 0 || preflightDone || providerRegistered || phase != Phase.PREFLIGHT) {
            throw new IllegalStateException(
                    "navigation protection must be supplied before construction starts");
        }
        if (cells == null) return;
        for (BlockPos cell : cells) {
            if (cell != null) {
                protectedCells.add(cell.asLong());
                inheritedProtectedMutationCells.add(cell.asLong());
                // 前一施工层仍需在受保护目标为空时进入该格；目标建成后，实时碰撞会阻止角色再次进入。
                if (!targets.containsKey(cell.asLong())) forbiddenBodyCells.add(cell.asLong());
            }
        }
    }

    @Override protected void onStart() {
        // 先拒绝普通放块做不了的内容，例如直接写箱子数据、生成画或组合摆设，避免建到一半才暴露不支持。
        bounds();
        if (r.targets.isEmpty()) { r.completed(0); succeed(); return; }
        // 蓝图读取能保存装饰数据，不等于当前施工能应用它；方块实体数据、摆设实体和特殊料单在这里整项拒绝。
        if (!r.blockEntityData.isEmpty()) addUnsupported("block_entity_data", null,
                "generic first-person clicks cannot author block-entity NBT",
                List.of("plain_blocks_only", "finish_contents_separately", "cancel"));
        if (!r.entities.isEmpty()) addUnsupported("fixture_entities", null,
                "blueprint entities need separate native item-use goals",
                List.of("build_without_fixtures", "place_fixtures_separately", "cancel"));
        if (!r.cellNeeds().isEmpty()) addUnsupported("multi_item_cell", null,
                "a multi-item or exact-component fixture needs a typed native sequence",
                List.of("plain_substitute", "place_fixture_separately", "cancel"));
        if (!unsupported.isEmpty()) failPreflight(
                "unsupported blueprint effects", FailureType.UNSUPPORTED, "unsupported_blueprint_effects");
    }

    @Override protected TaskState onTick() {
        // Dev 预览还没确认就等待，点取消就结束；确认后才按当前阶段逐步施工。
        var preview = BuildPreviewGate.await(r, r);
        if (preview == PreviewSession.Decision.WAITING)
            return TaskState.RUNNING;
        if (preview == PreviewSession.Decision.CANCELLED)
            return TaskState.CANCELLED;
        // 高处回收接近使用带禁拆观察的代理，整个跨刻导航期间保持其身份，原生支撑确认仍转回本项目。
        if (scaffoldAccess != null) BuildPlacementRegistry.register(player, scaffoldAccess.provider());
        else if (preflightDone) registerProvider();
        drainScaffolds();
        // 一刻内可接着处理不需要等待的阶段；已发点击、正在改创造背包或本刻不能再操作时停下来。
        TaskState result = BuildTickPipeline.advance(() -> phase, () -> {
            // 刨完一批或准备下一格 -> 松开已结算的施工动作 -> 吃东西 -> 必要时等血量恢复 -> 继续原阶段。
            // 检查放在每次阶段推进前，防止同一刻从连锁松键直接跳到下一批，把低饥饿检查一直跳过去。
            boolean boundary = phase == Phase.EXCAVATE || phase == Phase.SELECT || phase == Phase.SCAFFOLD_SELECT
                    || phase == Phase.EXCAVATE_EXIT && nav == null; // 仅出坑补料也先保养，但不抢走已经启动的出口导航。
            boolean settled = useReceipt == null && !selection.pending() && digger.current() == null
                    && !digger.hasPendingBreak() && ultimine == null && !ultimineArmed
                    && !excavationTools.active() && !spoilSupply.active() && player.onGround()
                    && (nav == null || nav.isSafeToCancel());
            if (boundary && settled && (foodPreparation.active() || !player.isUsingItem())
                    && foodPreparation.shouldPrepare(player)) {
                if (!foodPreparation.active()) { stopNav(); InputDriver.halt(player); }
                var food = foodPreparation.tick(player, r, this::runChild);
                r.extendDeadlineTo(foodPreparation.deadline());
                if (food == BuildFoodPreparation.Status.FAILED) {
                    failAt(player.blockPosition(), foodPreparation.message(), foodPreparation.failureType(), foodPreparation.failure(), false);
                    return TaskState.FAILED;
                }
                if (food == BuildFoodPreparation.Status.RUNNING) return TaskState.RUNNING;
                selection.reset(); // 食物可能从主背包换入手中，恢复施工时重新确认所需建材或工具。
            }
            TaskState step = stepPhase();
            // 已贴边后，选物、瞄准和等待确认也续潜行；移动阶段由边缘执行器供给方向，不能被这里清零。
            if (placementAccess != null && placementAccess.edgeActive() && phase != Phase.PLACE_NAV && phase != Phase.EDGE_RETURN
                    && placementAccess.hold() == BuildPlacementAccessDrive.Status.FAILED && useReceipt == null) {
                failAt(cell == null ? player.blockPosition() : cell.target().pos(), placementAccess.failure(), FailureType.NO_PATH,
                        "placement_edge_hold_failed", false); return TaskState.FAILED;
            }
            return step;
        },
                () -> phase != Phase.WAIT_USE && phase != Phase.CLEAR_CREATIVE
                        && ClientRuntime.requireContext(player).mutationAvailable());
        if (result == TaskState.SUCCESS && player.getAbilities().instabuild && creativeMaterials.hasOwned(player)) {
            phase = Phase.CLEAR_CREATIVE;
            return TaskState.RUNNING;
        }
        return result;
    }

    private TaskState stepPhase() {
        return switch (phase) {
            case PREFLIGHT -> preflightTick(); case EXCAVATE -> excavationTick(); case SELECT -> selectTick();
            case CLEARANCE_REPORT -> clearanceReportTick();
            case EXCAVATE_EXIT -> excavationExitTick();
            case CLEAR_NAV -> clearNavTick(); case CLEAR -> clearTick();
            case CLEAR_RELEASE -> nextClear();
            case PLACE_NAV -> placeNavTick(); case SELECT_ITEM -> selectItemTick();
            case WORKSITE -> worksiteTick();
            case AIM -> aimTick(); case WAIT_USE -> waitUseTick();
            case EDGE_RETURN -> returnFromPlacementEdge();
            case CLEAR_CREATIVE -> clearCreativeTick(); case VERIFY -> verifyTick();
            case SCAFFOLD_SELECT -> scaffoldSelectTick(); case SCAFFOLD_NAV -> scaffoldNavTick();
            case SCAFFOLD_BREAK -> scaffoldBreakTick();
            case SCAFFOLD_DESCENT -> scaffoldDescentTick();
            case SCAFFOLD_ACCESS -> scaffoldAccessTick();
            case FINAL_STATE -> finalStateTick();
            case ROUTE_VERIFY -> routeVerifyTick();
        };
    }

    private TaskState preflightTick() {
        // 每刻最多检查二十四个目标，避免大蓝图一次卡住游戏；目标没加载时先走近，加载后继续。
        int budget = PREFLIGHT_BUDGET;
        while (preflightAt < preflightOrder.size() && budget-- > 0) {
            BuildTaskRecord.Target target = preflightOrder.get(preflightAt);
            BlockPos primary = BuildPlacementGeometry.primaryOf(target);
            if (!primary.equals(target.pos())) { validateSecondary(target, primary); preflightAt++; continue; }
            if (!player.level().isLoaded(target.pos())) return travelToLoad(target.pos(), true);
            stopNav(); inspectPrimary(target); preflightAt++;
        }
        return preflightAt < preflightOrder.size() ? TaskState.RUNNING : finishPreflight();
    }

    private TaskState travelToLoad(BlockPos target, boolean preflight) {
        // 这里只为了让目标区块进入视野，采用不改地形的导航；加载到目标后还要回来继续原来的检查。
        if (nav == null) {
            int x = target.getX(), z = target.getZ();
            nav = PlayerNav.toGoal(player, () -> NavGoal.column(x, z), 1.0,
                    () -> player.level().isLoaded(target), PlayerNav.ContextProvider.DEFAULT);
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                stopNav();
                if (!player.level().isLoaded(target)) {
                    failAt(target, "site cell did not load", FailureType.TARGET_LOST,
                            preflight ? "site_not_loaded" : "verification_chunk_unloaded", false);
                    yield TaskState.FAILED;
                }
                yield TaskState.RUNNING;
            }
            case FAILED -> {
                FailureType type = nav.failType(); String reason = nav.failReason(); stopNav();
                failAt(target, (preflight ? "could not inspect site before changing it: "
                        : "could not revisit finished cell: ") + reason, type,
                        preflight ? "preflight_travel_failed" : "verification_travel_failed", false);
                yield TaskState.FAILED;
            }
        };
    }

    private void validateSecondary(BuildTaskRecord.Target secondary, BlockPos primaryPos) {
        // 门上半、床头等应由另一半放置时一起生成；要求蓝图两半互相匹配，不能分别指向冲突状态。
        BuildTaskRecord.Target primary = targets.get(primaryPos.asLong());
        if (primary != null) for (BuildPlacementGeometry.GeneratedCell generated
                : BuildPlacementGeometry.generatedBy(primary)) {
            if (generated.pos().equals(secondary.pos())
                    && BuildValidity.sameBlockState(generated.expected(), secondary.desiredState())) return;
        }
        addUnsupported(primary == null ? "orphan_secondary_half" : "secondary_half_conflict",
                secondary.pos(), primary == null ? "secondary half has no matching primary placement"
                        : "secondary half disagrees with the primary item's generated state",
                List.of("accept_generated_half", "replace_with_plain_blocks", "cancel"));
    }

    private void inspectPrimary(BuildTaskRecord.Target target) {
        // 看这一格是否已满足目标；不满足时先检查能否替换、能否手工放成，并统计还需多少材料。
        BlockState live = player.level().getBlockState(target.pos());
        if (live.isAir()) r.scaffoldLedger().cleared(target.pos());
        List<BuildPlacementGeometry.GeneratedCell> generated = BuildPlacementGeometry.generatedBy(target);
        if (!target.constructionMatches(live)
                && inheritedProtectedMutationCells.contains(target.pos().asLong())) {
            addBlocked("inherited_semantic_area_protection", target.pos(),
                    "a previously observed protected semantic area occupies this cell");
        }
        if (!target.constructionMatches(live)) {
            if (rules.blockedByMode(target) && !ownedAirScaffold(target, live)) addBlocked("replacement_policy", target.pos(),
                    "existing block is protected by replacement policy");
            else if (rules.hopeless(target)) addBlocked("unbreakable_or_outside_world", target.pos(),
                    "cell is outside the world or has an unbreakable obstruction");
        }
        inspectGenerated(target, generated);
        boolean done = constructionMatches(target, generated);
        if (!BuildCellRules.isAirTarget(target) && !done) {
            if (!(target.item() instanceof BlockItem)) addUnsupported("no_native_block_item", target.pos(),
                    "requested state has no block item that can be placed by hand",
                    List.of("placeable_substitute", "leave_for_player", "cancel"));
            // 当前世界中缺少可用手势并不代表方块状态不可支持：前序施工、不同放置顺序或可拆除的点击支撑都可能打开所需表面。
            // 真正放置前仍由原生实时预测核对蓝图指定的确切状态。
        }
        CellPlan plan = new CellPlan(target, generated);
        plans.add(plan); plansByPrimary.put(target.pos().asLong(), plan);
        if (done) markComplete(plan);
        else if (!BuildCellRules.isAirTarget(target) && target.costsMaterial())
            required.merge(target.item(), target.materialCount(), Integer::sum);
    }

    private void inspectGenerated(BuildTaskRecord.Target primary,
                                   List<BuildPlacementGeometry.GeneratedCell> generated) {
        // 一次放门或床会影响不止一格，连自动生成的另一半也要检查加载、保护、占用和不可破坏方块。
        for (BuildPlacementGeometry.GeneratedCell effect : generated) {
            BuildTaskRecord.Target declared = targets.get(effect.pos().asLong());
            if (declared != null && !BuildValidity.sameBlockState(declared.desiredState(), effect.expected()))
                addUnsupported("generated_cell_conflict", effect.pos(),
                        "placing " + primary.label() + " generates a different state here",
                        List.of("accept_generated_half", "move_multiblock", "cancel"));
            if (!player.level().isLoaded(effect.pos())) {
                addUnsupported("generated_cell_not_loaded", effect.pos(),
                        "generated half could not be inspected before construction",
                        List.of("load_whole_site", "shrink_build", "cancel")); continue;
            }
            BlockState live = player.level().getBlockState(effect.pos());
            if (!generatedConstructionMatches(primary, effect, live)
                    && inheritedProtectedMutationCells.contains(effect.pos().asLong())) {
                addBlocked("inherited_semantic_area_protection", effect.pos(),
                        "a generated placement would alter a previously observed protected semantic area");
                continue;
            }
            if (live.isAir() || generatedConstructionMatches(primary, effect, live)) continue;
            if (!r.replaceMode.allows(live, effect.expected()))
                addBlocked("generated_cell_occupied", effect.pos(), "protected secondary cell is occupied");
            else if (live.hasBlockEntity() && !r.replaceBlockEntities)
                addBlocked("generated_cell_block_entity", effect.pos(), "block entity occupies secondary cell");
            else if (live.getDestroySpeed(player.level(), effect.pos()) < 0.0F)
                addBlocked("generated_cell_unbreakable", effect.pos(), "unbreakable secondary cell");
        }
    }

    private TaskState finishPreflight() {
        // 全场加载核对后先检查清障名单，再给出最近选址；遇到人工障碍时不能先拆掉其余半栋再报告。
        if (clearanceSurvey == null) clearanceSurvey = BuildClearanceSurvey.forPlan(player, r, inheritedProtectedMutationCells);
        if (!clearanceSurvey.advance(512)) return TaskState.RUNNING;
        if (clearanceSurvey.blocked()) {
            failPreflight("Clearance whitelist excludes site obstacles; inspect clearance_report and consider a nearby site.",
                    FailureType.NO_SUPPORT, BuildClearanceSurvey.FAILURE);
            return TaskState.FAILED;
        }
        var terrain = BuildSiteConstraints.conflicts(player, r.targets);
        if (!terrain.isEmpty()) {
            failPreflight("The design intersects terrain that cannot be excavated: " + String.join("; ", terrain)
                    + ". Revise basement depth, raise the building or choose another site.",
                    FailureType.NO_SUPPORT, "build_terrain_conflict");
            return TaskState.FAILED;
        }
        // 全部检查结束后汇总拒绝原因；允许分批时只要还有可做的格子就开工，材料不足留给供料父任务处理。
        if (!r.preflightGuardMatches(player)) {
            failAt(siteMin, "observed build region changed before construction", FailureType.TARGET_LOST,
                    "build_observation_stale", false); return TaskState.FAILED;
        }
        if (!unsupported.isEmpty()) {
            failPreflight("some cells cannot be expressed by a verified first-person gesture",
                    FailureType.UNSUPPORTED, "unsupported_cells"); return TaskState.FAILED;
        }
        if (!blocked.isEmpty()) {
            failPreflight("site contains protected or unbreakable cells",
                    FailureType.NO_SUPPORT, "blocked_site_cells"); return TaskState.FAILED;
        }
        if (r.supplyAccessOnly()) {
            // 地层和保护预检通过后先证明离场；高处或坑内均不得因缺料直接交给只能沿已有道路走的仓库任务。
            preflightDone = true;
            var access = BuildExcavationFrontier.supplyAccess(player, r);
            if (access.status() == BuildExcavationFrontier.AccessStatus.READY) {
                supplyAccessReady = true; return TaskState.SUCCESS;
            }
            if (access.status() == BuildExcavationFrontier.AccessStatus.BLOCKED) return supplyExitUnavailable(access.code());
            excavationExit = access.exit();
            registerProvider(); phase = Phase.EXCAVATE_EXIT; return TaskState.RUNNING;
        }
        r.excavationCargo().begin(player);
        excavationExit = null; // 真正要离开时按已挖现场重查，不缓存尚未开挖时的地表或后来留下的柱顶。
        for (CellPlan plan : plans) {
            if (ownedAirScaffold(plan.target(), player.level().getBlockState(plan.target().pos()))) continue;
            for (BlockPos pos : clearCells(plan)) {
                excavation.add(pos); excavationOwners.put(pos.asLong(), plan);
                r.excavationCargo().observedTerrain(player.level().getBlockState(pos));
            }
        }
        // 预检按未完成目标的完整材料数核对背包，不扣除现场已有的半层等中间状态。
        if (r.consumeMaterials || !player.getAbilities().instabuild) required.forEach((item, count) -> {
            int have = inventory.mainInventoryCount(item); if (have < count) missing.put(item, count - have);
        });
        if (!missing.isEmpty() && !(r.allowPartial && (anyAffordable() || excavation.remaining() > 0))) {
            failureCode = "missing_materials";
            fail("construction did not start; missing " + BuildMaterialSummary.summarizeShortfall(missing),
                    FailureType.NO_MATERIAL); return TaskState.FAILED;
        }
        if (!missing.isEmpty()) note = "partial mode pauses when carried materials run out";
        preflightDone = true; queue = new ArrayList<>(plans); queueAt = 0;
        queue.sort(Comparator.comparing(CellPlan::target, BuildLayerFrontier.order(targets)));
        registerProvider(); phase = Phase.EXCAVATE; return TaskState.RUNNING;
    }

    private TaskState excavationTick() {
        // 先处理需要存回仓库的土石，再从当前最高的阻挡层继续开挖，不能直接奔向还埋在地下的地板。
        resetCell();
        if (spoilSupply.active()) {
            var deposited = NavigationSafetyContext.withProtectedArea(
                    r.targets.stream().map(BuildTaskRecord.Target::pos).toList(), List.of(),
                    () -> spoilSupply.tick(player, this::runChild));
            r.extendDeadlineTo(spoilSupply.childDeadline());
            if (deposited.status() == BuildExcavationSpoilSupply.Status.RUNNING) return TaskState.RUNNING;
            spoilReceipts.add(deposited.receipt());
            if (deposited.status() == BuildExcavationSpoilSupply.Status.FAILED) {
                failAt(player.blockPosition(), "Excavation surplus could not be stored: " + deposited.receipt(),
                        FailureType.NO_SPACE, "excavation_spoil_storage_failed", false);
                return TaskState.FAILED;
            }
        }
        if (r.excavationCargo().capacityLow(player)) {
            var excess = r.excavationCargo().unloadable(player, scaffoldReservations());
            if (!excess.isEmpty() && r.toolSupply().policy()
                    != SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY) {
                TaskState access = leaveExcavationBefore(Phase.EXCAVATE);
                if (access != null) return access;
                spoilSupply.begin(player, r.getToolCallId() + "/excavation-spoil", r.getDeadlineGameTime(),
                        excess, r.toolSupply().protectedLabels(), 48);
                return TaskState.RUNNING;
            }
        }
        BlockPos next = excavation.next(player);
        if (next == null) {
            excavating = false;
            digger.preferTopFace(false);
            if (excavation.remaining() == 0) {
                if (r.toolSupply().policy() != SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY
                        && required.entrySet().stream().anyMatch(e -> inventory.mainInventoryCount(e.getKey()) < e.getValue())) {
                    TaskState access = leaveExcavationBefore(Phase.SELECT);
                    if (access != null) return access;
                }
                phase = Phase.SELECT; return TaskState.RUNNING;
            }
            failAt(siteMin, "No exposed ground approach reaches the remaining excavation layer; "
                    + "access outside the authored cells needs a revised site plan", FailureType.NO_PATH,
                    "excavation_access_blocked", false);
            return TaskState.FAILED;
        }
        excavating = true;
        // 地面向下挖时优先上表面；修正头顶洞口时使用真实可见面，不要求从天花板背后点击。
        digger.preferTopFace(next.getY() < player.getY());
        cell = excavationOwners.get(next.asLong());
        clearing = next; clearQueue = List.of(next); clearAt = 0;
        phase = Phase.CLEAR_NAV;
        return TaskState.RUNNING;
    }

    private TaskState leaveExcavationBefore(Phase resume) {
        var access = BuildExcavationFrontier.supplyAccess(player, r);
        if (access.status() == BuildExcavationFrontier.AccessStatus.READY) return null;
        if (access.status() == BuildExcavationFrontier.AccessStatus.BLOCKED) return supplyExitUnavailable(access.code());
        excavationExit = access.exit();
        stopNav(); afterExcavationExit = resume; phase = Phase.EXCAVATE_EXIT;
        return TaskState.RUNNING;
    }

    private TaskState supplyExitUnavailable(String reason) {
        // 缺少真实出口时保留现场，明确报告通行准备失败，不能拿当前位置充当离场完成。
        failAt(player.blockPosition(), "No verified exterior supply exit: " + reason,
                FailureType.NO_PATH, "construction_supply_exit_unavailable", false);
        return TaskState.FAILED;
    }

    private TaskState excavationExitTick() {
        // 刨坑 -> 站到外部地面 -> 拿材料；坑边低一格的近点不算出口，否则普通仓库寻路仍会被困住。
        if (nav == null) nav = PlayerNav.toGoal(player, () -> NavGoal.exact(excavationExit), BuildStanceNavigation.PRECISE_WALK,
                () -> player.blockPosition().equals(excavationExit), this).walkingOnly();
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                stopNav(); drainScaffolds();
                if (!player.blockPosition().equals(excavationExit)) {
                    failAt(excavationExit, "The exact exterior supply exit has not been reached",
                            FailureType.NO_PATH, "supply_access_not_reached", false);
                    yield TaskState.FAILED;
                }
                if (r.supplyAccessOnly()) {
                    supplyAccessReady = BuildExcavationFrontier.supplyAccess(player, r).status() == BuildExcavationFrontier.AccessStatus.READY;
                    if (supplyAccessReady) yield TaskState.SUCCESS;
                    failAt(excavationExit, "The body has not reached a verified exterior supply route",
                            FailureType.NO_PATH, "supply_access_not_reached", false);
                    yield TaskState.FAILED;
                }
                phase = afterExcavationExit; yield TaskState.RUNNING;
            }
            case FAILED -> {
                String reason = nav.failReason(); stopNav();
                failAt(excavationExit, "Could not establish a ground exit before resupply: " + reason,
                        FailureType.NO_PATH, "excavation_exit_blocked", false);
                yield TaskState.FAILED;
            }
        };
    }

    // 允许部分施工时，只要还有一格能用现有物品完成，或有一格可清空，就可以先开工。
    private boolean anyAffordable() {
        for (CellPlan plan : plans) {
            if (constructionMatches(plan.target(), plan.generated())) continue;
            if (BuildCellRules.isAirTarget(plan.target())
                    || inventory.mainInventoryCount(plan.target().item()) >= plan.target().materialCount()) return true;
        }
        return false;
    }

    private TaskState selectTick() {
        // 找下一格不符合蓝图的位置，先清掉冲突方块，再选择放置手法；整轮做完进入成品复查。
        resetCell();
        if (supportedCell == null) { promoteConstructionLayer(); retainUsefulWorksite(); }
        // 留在当前尚未完成的施工层，把这一站位能顺手放到的格子做完，再继续队列。
        if (supportedCell == null && queueAt < queue.size() && !isTemporary(queue.get(queueAt))
                && selectNearbyPlacement(worksite == null ? null : worksite.feet()))
            return TaskState.RUNNING;
        while (queueAt < queue.size()) {
            cell = queue.get(queueAt);
            if (constructionMatches(cell.target(), cell.generated())) {
                markComplete(cell); if (cell == supportedCell) releaseSupportPlan(); queueAt++; continue;
            }
            if (isTemporary(cell) && (!player.level().isLoaded(cell.target().pos())
                    || !scaffoldPermitted(cell.target().pos(), null))) {
                failAt(cell.target().pos(), "temporary support cell changed or gained protection",
                        FailureType.TARGET_LOST, "temporary_support_changed", false); return TaskState.FAILED;
            }
            // 上一材料批次已确认的临时支撑要保留到所有永久方块完成；它仍计为未完成，最后进入脚手架清理阶段。
            if (player.level().isLoaded(cell.target().pos()) && ownedAirScaffold(cell.target(),
                    player.level().getBlockState(cell.target().pos()))) { queueAt++; continue; }
            clearQueue = clearCells(cell); clearAt = 0;
            if (!clearQueue.isEmpty()) { clearing = clearQueue.get(0); phase = Phase.CLEAR_NAV; }
            else if (BuildCellRules.isAirTarget(cell.target())) finishCell();
            else {
                if (r.allowPartial && (r.consumeMaterials || !player.getAbilities().instabuild)
                        && !inventory.hasItems(cell.target().item(), cell.target().materialCount(), true)) {
                    // 缺砖就留在能够补料的位置，不能空手回到坑底后才宣布材料不足。
                    failAt(cell.target().pos(), "required block item is not in synchronized inventory; resupply before approaching the cell",
                            FailureType.NO_MATERIAL, "material_exhausted", false);
                    return TaskState.FAILED;
                }
                phase = Phase.PLACE_NAV;
            }
            return TaskState.RUNNING;
        }
        beginVerify(); return TaskState.RUNNING;
    }

    private List<BlockPos> clearCells(CellPlan plan) {
        // 收集蓝图主格及门、床另一半的现存阻挡；真正下手时还会重读替换许可和保护条件。
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        if (player.level().isLoaded(plan.target().pos())) {
            BlockState live = player.level().getBlockState(plan.target().pos());
            if (!live.isAir() && !plan.target().constructionMatches(live)) out.add(plan.target().pos());
        }
        for (BuildPlacementGeometry.GeneratedCell generated : plan.generated()) {
            if (!player.level().isLoaded(generated.pos())) continue;
            BlockState live = player.level().getBlockState(generated.pos());
            if (!live.isAir() && !generatedConstructionMatches(plan.target(), generated, live)) out.add(generated.pos());
        }
        return List.copyOf(out);
    }

    private TaskState clearNavTick() {
        // 还没开始补工具时先看眼前障碍，避免为名单外建筑跑一趟仓库之后才告知需要换场地。
        if (!excavationTools.active() && player.level().isLoaded(clearing)) {
            var live = player.level().getBlockState(clearing);
            var declared = targets.get(clearing.asLong());
            if (!ClearanceWhitelist.allows(live) && (declared == null || !declared.constructionMatches(live))) {
                beginClearanceReport(clearing); return TaskState.RUNNING;
            }
        }
        if (excavating && !excavationTools.active() && player.level().isLoaded(clearing)
                && !player.level().getBlockState(clearing).isAir()
                && r.toolSupply().policy() != SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY
                && WorkToolPreparation.excavationTool(
                        player, player.level().getBlockState(clearing), excavation.remaining()) != null) {
            TaskState access = leaveExcavationBefore(Phase.CLEAR_NAV);
            if (access != null) return access;
        }
        if (excavating && (excavationTools.active() || player.level().isLoaded(clearing)
                && !player.level().getBlockState(clearing).isAir())
                && !excavationTools.ready(player, r, clearing, excavation.remaining(), this::runChild)) {
            stopNav();
            if (excavationTools.failure() == null) return TaskState.RUNNING;
            failAt(clearing, excavationTools.failure(), FailureType.NO_MATERIAL, "excavation_tool_unavailable", false);
            return TaskState.FAILED;
        }
        digger.minimumToolDurability(excavating ? Math.min(9, excavation.remaining()) + 1 : 0);
        // 走到能挖的位置前再次核对专用观察守卫和箱子等保护；普通建造的守卫默认允许通过。
        if (!player.level().isLoaded(clearing)) {
            failAt(clearing, "cell unloaded after preflight", FailureType.TARGET_LOST,
                    "cell_unloaded", false); return TaskState.FAILED;
        }
        BlockState live = player.level().getBlockState(clearing);
        if (!r.mutationGuardMatches(player, clearing)) {
            failAt(clearing, "observed target changed before clearing", FailureType.TARGET_LOST,
                    "build_target_changed", false); return TaskState.FAILED;
        }
        if (live.isAir()) return nextClear();
        if (live.hasBlockEntity() && !r.replaceBlockEntities) {
            failAt(clearing, "protected block entity appeared after preflight",
                    FailureType.TARGET_LOST, "site_changed_after_preflight", false);
            return TaskState.FAILED;
        }
        if (!clearingPermitted(live)) return phase == Phase.CLEARANCE_REPORT ? TaskState.RUNNING : TaskState.FAILED;
        if (excavating && BuildExcavationFrontier.safeDescent(player, clearing) && digger.reachableHit(clearing) != null) {
            stopNav(); phase = Phase.CLEAR; return TaskState.RUNNING;
        }
        if (nav == null) {
            BlockPos at = clearing.immutable();
            if (excavating) {
                var approaches = BuildExcavationFrontier.approaches(player, at);
                if (approaches.isEmpty()) { excavation.reject(at); phase = Phase.EXCAVATE; return TaskState.RUNNING; }
                nav = PlayerNav.toGoal(player, () -> NavGoal.composite(approaches), BuildStanceNavigation.PRECISE_WALK,
                        () -> BuildExcavationFrontier.safeDescent(player, at) && digger.reachableHit(at) != null, this).walkingOnly();
            } else nav = PlayerNav.toGoal(player, () -> NavGoal.mineStance(at), BuildStanceNavigation.PRECISE_WALK, () -> inReach(at), this).walkingOnly();
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> { stopNav(); phase = Phase.CLEAR; yield TaskState.RUNNING; }
            case FAILED -> {
                FailureType type = nav.failType(); String reason = nav.failReason(); stopNav();
                if (excavating) { excavation.reject(clearing); phase = Phase.EXCAVATE; yield TaskState.RUNNING; }
                failAt(clearing, "no breaking stance: " + reason, type, "clear_stance_failed", false);
                yield TaskState.FAILED;
            }
        };
    }

    private TaskState clearTick() {
        if (ultimineArmed && digger.hasPendingBreak()) {
            var decision = ultimine.tickInFlight(ClientRuntime.requireContext(player));
            // 连锁九格中的任一格变成人工障碍都先松键停挖，再报告实际遇到的位置。
            if (clearanceDeniedAt != null) { beginClearanceReport(clearanceDeniedAt); return TaskState.RUNNING; }
            if (decision.ready()) ultimineHeldTicks++;
            var holding = new LinkedHashMap<>(ultimineEvidence);
            holding.putAll(ultimine.holdEvidence()); holding.put("status", "holding_native_break");
            ultimineEvidence = Map.copyOf(holding);
            if (decision.status() == UltimineSession.Status.ABORT
                    || decision.status() == UltimineSession.Status.BLOCKED) {
                digger.cancel();
                failAt(clearing, decision.code(), FailureType.TARGET_LOST, "ultimine_break_interrupted", true);
                return TaskState.FAILED;
            }
        }
        // 对准目标持续挖掘；每次下手前重读现场保护，已开始的连锁则持续保持原生启用键直到破坏确认。
        if (!player.level().isLoaded(clearing)) {
            failAt(clearing, "break target unloaded", FailureType.TARGET_LOST,
                    "clear_target_lost", false); return TaskState.FAILED;
        }
        if (player.level().getBlockState(clearing).isAir()) {
            if (!r.hasExecutionGuards() && !clearing.equals(digger.current())) return nextClear();
            // 受保护的机器编辑只能凭自己的待处理破坏回执确认目标消失。
            return switch (digger.settleGone(true)) {
                case PROGRESSING -> TaskState.RUNNING;
                case BROKE_TARGET -> {
                    confirmedBlockChange(clearing);
                    r.brokeOne(); renewBuildProgress(); yield nextClear();
                }
                default -> {
                    failAt(clearing, "target disappeared without a confirmed owned break", FailureType.TARGET_LOST,
                            "build_target_changed", false); yield TaskState.FAILED;
                }
            };
        }
        // 先留下新出现的名单外障碍和选址证据，再检查机器观察守卫；任一条件不符都不会继续挖掘。
        if (!clearingPermitted(player.level().getBlockState(clearing)))
            return phase == Phase.CLEARANCE_REPORT ? TaskState.RUNNING : TaskState.FAILED;
        if (!r.mutationGuardMatches(player, clearing)) {
            failAt(clearing, "observed target changed before breaking", FailureType.TARGET_LOST,
                    "build_target_changed", false); return TaskState.FAILED;
        }
        return switch (digger.digTargetStep(clearing)) {
            case PROGRESSING -> ultimineFailure == null ? TaskState.RUNNING : TaskState.FAILED;
            case BROKE_TARGET -> {
                confirmedBlockChange(clearing);
                r.brokeOne(); renewBuildProgress(); yield nextClear();
            }
            case BROKE_OCCLUDER -> {
                failAt(clearing, "target-only breaker changed another block", FailureType.INTERNAL,
                        "unexpected_break_target", true); yield TaskState.FAILED;
            }
            case NO_SHOT -> {
                if (ultimine != null) { ultimine.close(); ultimine = null; ultimineArmed = false; }
                if (excavating) { digger.cancel(); excavation.reject(clearing); phase = Phase.EXCAVATE; yield TaskState.RUNNING; }
                failAt(clearing, "no verified crosshair ray reaches obstruction", FailureType.OCCLUDED,
                        "clear_occluded", false); yield TaskState.FAILED;
            }
        };
    }

    private TaskState nextClear() {
        // 本批破坏确认后才松开连锁键，再等 FTB 确认松开，避免下一项普通操作误带连锁效果。
        if (ultimine != null) {
            var release = ultimine.finish(ClientRuntime.requireContext(player));
            if (release.status() == UltimineSession.Status.WAITING) {
                phase = Phase.CLEAR_RELEASE; return TaskState.RUNNING;
            }
            if (release.status() == UltimineSession.Status.ABORT
                    || release.status() == UltimineSession.Status.BLOCKED) {
                failAt(clearing, release.code(), FailureType.TARGET_LOST, "ultimine_release_interrupted", false);
                return TaskState.FAILED;
            }
            if (ultimineArmed) {
                ultimineBatches++; lastUltimineHold = ultimine.holdEvidence();
                // 本批松键后更新显示状态，上一批的持键证明不能再被当作角色此刻仍按着连锁键。
                var released = new LinkedHashMap<>(ultimineEvidence);
                released.put("status", "released_after_confirmation"); released.put("confirmed_native_batches", ultimineBatches);
                ultimineEvidence = Map.copyOf(released);
            }
            ultimine = null; ultimineArmed = false;
        }
        // 清完一格继续下一格；冲突全部清完后，要么空气目标已完成，要么进入放置阶段。
        if (clearing != null && player.level().isLoaded(clearing)
                && player.level().getBlockState(clearing).isAir()) r.scaffoldLedger().cleared(clearing);
        if (excavating) {
            excavation.cleared(clearing); markComplete(cell); stopNav(); phase = Phase.EXCAVATE;
            return TaskState.RUNNING;
        }
        clearAt++; stopNav();
        if (clearAt < clearQueue.size()) { clearing = clearQueue.get(clearAt); phase = Phase.CLEAR_NAV; }
        else if (BuildCellRules.isAirTarget(cell.target())) finishCell();
        else {
            liveGestures = List.of(); gestureSearch = null; gestureProgress = null;
            phase = Phase.PLACE_NAV;
        }
        return TaskState.RUNNING;
    }

    private boolean clearingPermitted(BlockState live) {
        var declared = targets.get(clearing.asLong());
        // 每次实际下手仍重读类型，预检后新出现的建筑也必须留在现场交回 LLM 重新选址。
        if (!ClearanceWhitelist.allows(live) && (declared == null || !declared.constructionMatches(live))) {
            beginClearanceReport(clearing); return false;
        }
        BlockState desired = declared == null ? Blocks.AIR.defaultBlockState() : declared.desiredState();
        if (inheritedProtectedMutationCells.contains(clearing.asLong())
                || !r.replaceMode.allows(live, desired) || live.getDestroySpeed(player.level(), clearing) < 0
                || !live.getFluidState().isEmpty() || live.hasBlockEntity() && !r.replaceBlockEntities
                || declared != null && declared.constructionMatches(live)) {
            failAt(clearing, "Excavation cell changed or is protected, fluid-filled or unbreakable",
                    FailureType.TARGET_LOST, "excavation_cell_changed", false);
            return false;
        }
        return true;
    }

    private boolean prepareExcavationBreak(BlockHitResult hit) {
        // 按准星实际命中面使用原生选区；蓝图中已经挖空的格子仍在施工范围内，成品和受保护方块始终排除。
        if (!excavating) return true;
        if (ultimine == null) ultimine = new UltimineSession();
        var decision = ultimine.prepare(ClientRuntime.requireContext(player), hit, excavationAuthority, at -> {
            var target = targets.get(at.asLong());
            // 原生连锁可能一次选中多格；把名单外格记为触发证据，绝不能让副目标绕过单格清障检查。
            if (target != null && !ClearanceWhitelist.allows(player.level().getBlockState(at))
                    && !r.scaffoldLedger().owns(at, player.level().getBlockState(at))
                    && !target.constructionMatches(player.level().getBlockState(at))) {
                clearanceDeniedAt = at.immutable(); return true;
            }
            return r.hasExecutionGuards() || target == null || at.equals(player.blockPosition().below())
                    || inheritedProtectedMutationCells.contains(at.asLong()) || r.scaffoldLedger().contains(at)
                    || !r.replaceMode.allows(player.level().getBlockState(at), target.desiredState())
                    || !BuildCellRules.isAirTarget(target) && target.constructionMatches(player.level().getBlockState(at));
        });
        if (clearanceDeniedAt != null) { beginClearanceReport(clearanceDeniedAt); return false; }
        ultimineEvidence = Map.of("status", decision.status().name().toLowerCase(Locale.ROOT),
                "reason", decision.code(), "native_selected_cells", decision.completeSelection().size(),
                "confirmed_native_batches", ultimineBatches);
        return switch (decision.status()) {
            case READY -> { ultimineArmed = true; yield true; }
            case SINGLE_BLOCK -> true;
            case WAITING -> false;
            case ABORT, BLOCKED -> {
                ultimineFailure = decision.code();
                failAt(clearing, decision.code(), FailureType.TARGET_LOST, "ultimine_preparation_interrupted", false);
                yield false;
            }
        };
    }

    // 清障过程中发现新障碍时先交还身体和挖掘控制；只读选址检查可跨刻完成，不会让角色继续挥镐。
    private void beginClearanceReport(BlockPos at) {
        uncertain |= digger.hasPendingBreak();
        clearanceDeniedAt = at.immutable();
        clearanceTrigger = Map.of("at", List.of(at.getX(), at.getY(), at.getZ()),
                "block_id", BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(at).getBlock()).toString());
        stopNav(); digger.cancel();
        if (ultimine != null) { ultimine.close(); ultimine = null; }
        ultimineArmed = false;
        clearanceSurvey = BuildClearanceSurvey.forPlan(player, r, inheritedProtectedMutationCells); phase = Phase.CLEARANCE_REPORT;
    }

    private TaskState clearanceReportTick() {
        if (!clearanceSurvey.advance(512)) return TaskState.RUNNING;
        failAt(clearanceDeniedAt, "Clearance whitelist excludes this obstacle; consider the site offsets in clearance_report.",
                FailureType.NO_SUPPORT, BuildClearanceSurvey.FAILURE, uncertain);
        return TaskState.FAILED;
    }

    private TaskState placeNavTick() {
        // 起跳或落地动作未交还控制时，只继续原导航；不能因路过可放位置而抢换槽、转头或取消腾空路线。
        if (nav != null && (!nav.isSafeToCancel() || !placementFooting.ready(player))) {
            nav.tick();
            return TaskState.RUNNING;
        }
        // 外界或延迟同步已经完成目标时也先退回实地，不能为一个已完成格继续走向檐边。
        if (currentPlacementComplete()) { finishPlaced(); return TaskState.RUNNING; }
        stanceNavigation.forTarget(cell.target().pos(), PlayerNav.playerFeet(player));
        if (!cell.target().pos().equals(placementAccessTarget)) {
            if (placementAccess != null) placementAccess.stop();
            placementAccess = null; placementAccessTarget = cell.target().pos();
        }
        if (placementAccess != null) {
            TaskState access = drivePlacementAccess(); if (access != null) return access;
        }
        // 普通登阶允许取消并不表示人已站上台面；没有导航时先自然落稳，有导航时由上方分支继续原路线。
        if (!placementFooting.ready(player)) { InputDriver.halt(player); return TaskState.RUNNING; }
        if (seekRaisedFooting()) {
            footingSearchAfter = lastPlacedTarget; worksiteSearched = false;
            phase = Phase.WORKSITE; return TaskState.RUNNING;
        }
        // 先完成已确认的中间半砖或施工层，再转向其他目标。
        if (useCount > 0) { worksite = null; worksiteSearch = null; worksiteSearched = true; }
        Vec3 approaching = worksite != null ? worksite.feet()
                : nav != null && gesture != null ? Vec3.atBottomCenterOf(gesture.stance()) : null;
        if (supportedCell == null && useCount == 0 && !isTemporary(cell) && selectNearbyPlacement(approaching)) return TaskState.RUNNING;
        BuildPlacementGeometry.Gesture nearby = BuildPlacementGeometry.currentGesture(player, cell.target(), targets,
                candidate -> placementAttempts.allows(cell.target(), candidate));
        if (nearby != null && !seekBetterFooting(cell.target())) {
            placementWalkTarget = approaching;
            stopNav(); gesture = nearby; gestureFromCurrent = true; phase = Phase.SELECT_ITEM;
            return TaskState.RUNNING;
        }
        // 一种放法不通就试下一种；站位必须精确到指定格，不能像长途旅行那样“附近就算到了”。
        if (currentPlacementComplete()) { finishPlaced(); return TaskState.RUNNING; }
        if (placementAccess == null && nav == null) {
            // 同层楼梯也可先登上已建台阶再贴边续放；这里只筛近处，最终脚位、碰撞与原生朝向仍由实际搜索证明。
            boolean edgeCandidate = !BuildCellRules.isAirTarget(cell.target()) && Math.abs(player.getY() - cell.target().pos().getY()) <= 3
                    && cell.target().pos().distToCenterSqr(player.position()) <= 144
                    && (!seekBetterFooting(cell.target()) || worksite != null && worksite.constructionAccess());
            // 正式方块和垫块都使用当前脚下的边缘接近，不等待预先生成的整链站位。
            if (edgeCandidate && (worksite == null || worksite.constructionAccess())) {
                var accessTarget = cell.target();
                placementAccess = new BuildPlacementAccessDrive(player, accessTarget, stanceNavigation.walkingContext(Integer.MIN_VALUE),
                        () -> r.mutationGuardMatches(player, accessTarget.pos())
                                && !inheritedProtectedMutationCells.contains(accessTarget.pos().asLong()), null,
                        () -> switch (prepareHeldItem()) {
                            case SUCCESS -> BuildPlacementAccessDrive.Status.READY;
                            case FAILED -> BuildPlacementAccessDrive.Status.FAILED;
                            default -> BuildPlacementAccessDrive.Status.RUNNING;
                        }, candidate -> placementAttempts.allows(accessTarget, candidate));
                TaskState access = drivePlacementAccess(); if (access != null) return access;
            }
        }
        if (supportedCell == null && useCount == 0 && !worksiteSearched && !isTemporary(cell)) {
            stopNav(); phase = Phase.WORKSITE; return TaskState.RUNNING;
        }
        if (worksite != null) return walkToWorksite();
        if (nav == null) {
            if (liveGestures.isEmpty()) {
                if (gestureSearch == null) {
                    gestureSearch = new BuildPlacementGeometry.PlanSearch(player, cell.target(), targets);
                    gestureAt = 0;
                }
                gestureProgress = gestureSearch.advance(64);
                if (!gestureProgress.complete()) { InputDriver.halt(player); return TaskState.RUNNING; }
                liveGestures = gestureSearch.results();
            }
            var footing = new HashMap<BlockPos, Boolean>(); int footingChecks = 0;
            while (gestureAt < liveGestures.size()) {
                var candidate = liveGestures.get(gestureAt);
                boolean usable = stanceNavigation.allows(candidate.stance()) && supportExists(candidate)
                        && placementAttempts.allows(cell.target(), candidate);
                // 保持地形阶段先验证实际落脚点，每刻最多二十四个不同站位；多种面内取样共用检查，空中格不创建导航。
                if (usable && stanceNavigation.requiresExistingFooting()) {
                    Boolean standing = footing.get(candidate.stance());
                    if (standing == null) {
                        if (footingChecks++ >= 24) return TaskState.RUNNING;
                        standing = stanceNavigation.existingFooting(player, candidate.stance()); footing.put(candidate.stance(), standing);
                    }
                    usable = standing;
                }
                if (usable) break;
                stanceNavigation.skipped(candidate.stance()); gestureAt++;
            }
            if (gestureAt >= liveGestures.size()) {
                if (stanceNavigation.nextExistingPass()) { gestureAt = 0; return TaskState.RUNNING; }
                return deferOrFail();
            }
            gesture = liveGestures.get(gestureAt);
            gestureFromCurrent = false;
            BlockPos stance = gesture.stance();
            if (!stanceNavigation.claimRoute(stance)) return deferOrFail();
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(stance), BuildStanceNavigation.PRECISE_WALK,
                    () -> placementStanceReached(stance), stanceNavigation.contextFor(stance));
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> { stopNav(); phase = Phase.SELECT_ITEM; yield TaskState.RUNNING; }
            case FAILED -> {
                stanceNavigation.failed(gesture.stance(), nav.failReason());
                stopNav();
                gestureAt++; yield TaskState.RUNNING;
            }
        };
    }

    // 跳升时跨入目的格不算到站；真实落地后再交还导航，半砖与台阶仍使用导航原有的脚格映射。
    private boolean placementStanceReached(BlockPos stance) {
        return player.onGround() && PlayerNav.playerFeet(player).equals(stance);
    }

    // 一处站位连放的候选必须不用先清障、有材料、尚未完成且最新修改守卫允许；临时垫块另走自己的队列。
    private boolean readyForWorksite(CellPlan candidate) {
        return !isTemporary(candidate) && onConstructionLayer(candidate)
                && !BuildCellRules.isAirTarget(candidate.target())
                && (!r.consumeMaterials || inventory.hasItems(candidate.target().item(), candidate.target().materialCount(), true))
                && player.level().isLoaded(candidate.target().pos())
                && !constructionMatches(candidate.target(), candidate.generated()) && clearCells(candidate).isEmpty()
                && r.mutationGuardMatches(player, candidate.target().pos());
    }

    private boolean layerPending(CellPlan candidate) {
        return !constructionMatches(candidate.target(), candidate.generated())
                && !(player.level().isLoaded(candidate.target().pos())
                && ownedAirScaffold(candidate.target(), player.level().getBlockState(candidate.target().pos())));
    }

    // 只约束当前结构区域的最低未完成层；独立门柱等区域不再把主体拉回地面。
    private int constructionLayer() {
        if (!layerKnown) {
            if (regions == null) regions = new BuildRegions(targets);
            var pending = queue.subList(queueAt, queue.size()).stream()
                    .filter(candidate -> !isTemporary(candidate) && layerPending(candidate)).map(CellPlan::target).toList();
            constructionRegion = regions.choose(pending, constructionRegion);
            constructionLayer = Integer.MAX_VALUE;
            for (int i = queueAt; i < queue.size(); i++) {
                CellPlan candidate = queue.get(i);
                if (isTemporary(candidate) || regions.region(candidate.target()) != constructionRegion) continue;
                int candidateLayer = BuildLayerFrontier.layer(candidate.target());
                if (candidateLayer < constructionLayer && layerPending(candidate)) constructionLayer = candidateLayer;
            }
            layerKnown = true;
        }
        return constructionLayer;
    }

    private boolean onConstructionLayer(CellPlan candidate) {
        return constructionLayer() == Integer.MAX_VALUE
                || regions.region(candidate.target()) == constructionRegion
                && BuildLayerFrontier.layer(candidate.target()) == constructionLayer();
    }

    private void promoteConstructionLayer() {
        if (queueAt >= queue.size() || isTemporary(queue.get(queueAt))) return;
        int best = -1;
        var preference = BuildPlacementPreference.targets(player.position(), lastPlacedTarget, targets);
        for (int i = queueAt; i < queue.size(); i++) {
            CellPlan candidate = queue.get(i);
            if (!isTemporary(candidate) && onConstructionLayer(candidate) && layerPending(candidate)
                    && (best < 0 || preference.compare(candidate.target(), queue.get(best).target()) < 0)) best = i;
        }
        if (best >= 0) Collections.swap(queue, queueAt, best);
    }

    // 先找当前伸手就够得着的待建格，最多验证十六格或约四毫秒；找到就移到队首，减少来回走路。
    private boolean selectNearbyPlacement(Vec3 approach) {
        // 路过半空时看到可点击面不能抢走导航，否则停键潜行会使玩家掉回下一级台阶。
        if (!placementFooting.ready(player)) return false;
        if (seekRaisedFooting()) return false;
        int proofs = 0;
        long deadline = System.nanoTime() + 4_000_000;
        var candidates = new ArrayList<Integer>();
        for (int i = queueAt; i < queue.size(); i++) {
            var target = queue.get(i).target();
            if (target.pos().distToCenterSqr(player.getEyePosition()) <= 36 && onConstructionLayer(queue.get(i))) candidates.add(i);
        }
        candidates.sort(Comparator.comparing(i -> queue.get(i).target(),
                BuildPlacementPreference.targets(player.position(), lastPlacedTarget, targets)));
        for (int i : candidates) {
            if (proofs >= 16 || System.nanoTime() >= deadline) break;
            CellPlan candidate = queue.get(i);
            if (approach != null && candidate != cell && i != queueAt && !coveredByWorksite(candidate) && (lastPlacedTarget == null
                    || lastPlacedTarget.getY() != candidate.target().pos().getY()
                    || lastPlacedTarget.distManhattan(candidate.target().pos()) != 1)) continue;
            if (!readyForWorksite(candidate) || seekBetterFooting(candidate.target())) continue;
            proofs++;
            var available = BuildPlacementGeometry.currentGesture(player, candidate.target(), targets,
                    g -> placementAttempts.allows(candidate.target(), g));
            if (available == null) continue;
            Collections.swap(queue, queueAt, i); cell = candidate;
            stanceNavigation.forTarget(cell.target().pos(), PlayerNav.playerFeet(player));
            placementWalkTarget = approach; stopNav();
            liveGestures = List.of(); gestureSearch = null; gestureProgress = null;
            gestureAt = 0; useCount = 0; selection.reset(); aimConvergence.reset();
            gesture = available; gestureFromCurrent = true; phase = Phase.SELECT_ITEM; return true;
        }
        return false;
    }

    private boolean coveredByWorksite(CellPlan candidate) {
        return worksite != null && worksite.placements().stream().anyMatch(p -> p.target().pos().equals(candidate.target().pos()));
    }

    private boolean seekBetterFooting(BuildTaskRecord.Target target) {
        return supportedCell == null && useCount == 0 && !isTemporary(cell)
                && (worksite != null && worksite.feet().y > player.getY() + .51
                || target.pos().getY() > player.getY() + .5 && !worksiteSearched);
    }

    private boolean raisedPermanentFooting() {
        if (lastPlacedTarget == null || lastPlacedTarget.distToCenterSqr(player.position()) > 9
                || !player.level().isLoaded(lastPlacedTarget)) return false;
        var target = targets.get(lastPlacedTarget.asLong());
        if (target == null || !target.constructionMatches(player.level().getBlockState(lastPlacedTarget))) return false;
        var shape = player.level().getBlockState(lastPlacedTarget).getCollisionShape(player.level(), lastPlacedTarget);
        if (shape.isEmpty()) return false;
        double rise = lastPlacedTarget.getY() + shape.bounds().maxY - player.getY();
        return rise > .51 && rise <= 1.01 && constructionLayer() >= lastPlacedTarget.getY();
    }

    private boolean seekRaisedFooting() {
        return supportedCell == null && worksite == null && useCount == 0 && queueAt < queue.size()
                && !Objects.equals(lastPlacedTarget, footingSearchAfter) && raisedPermanentFooting();
    }

    // 做完一格后尝试继续利用原站位。现场、材料或剩余任务变了就重新检查，没有可做的格子才放弃该站位。
    private void retainUsefulWorksite() {
        if (worksite == null) return;
        long deadline = System.nanoTime() + 4_000_000;
        for (int at = 0; at < worksite.placements().size(); at++) {
            if (System.nanoTime() >= deadline) {
                // 下次只继续尚未检查的尾部；当前时间片结束不代表已核验结果失效。
                worksite = new BuildWorksitePlanner.Worksite(worksite.stance(), worksite.feet(),
                        worksite.placements().subList(at, worksite.placements().size()), worksite.distanceSquared(),
                        worksite.heightLoss(), worksite.route(), worksite.constructionAccess());
                return;
            }
            var placement = worksite.placements().get(at);
            var target = placement.target();
            CellPlan pending = plansByPrimary.get(target.pos().asLong());
            int index = pending == null ? -1 : queue.subList(queueAt, queue.size()).indexOf(pending);
            if (index >= 0 && readyForWorksite(pending)
                    && BuildPlacementGeometry.liveGestureFrom(player, target, targets, worksite.feet(),
                    g -> placementAttempts.allows(target, g)) != null) {
                Collections.swap(queue, queueAt, queueAt + index);
                return;
            }
        }
        // 只有重新确认目标仍有助于未完成施工时，才保留当前目的地。
        worksite = null; worksiteSearched = false; worksiteProgress = null;
    }

    // 把当前不用清障的目标交给多格站位搜索，逐刻累计检查；全部查完后才采用最佳站位。
    private TaskState worksiteTick() {
        if (worksiteAttempts >= 4) return nextWorksitePass("four worksite approaches exhausted without completing a block");
        if (worksiteSearch == null) {
            var pending = queue.subList(queueAt, queue.size()).stream().filter(this::readyForWorksite)
                    .map(CellPlan::target).toList();
            worksiteSearch = new BuildWorksitePlanner.Search(player, pending, targets,
                    pos -> !forbiddenBodyCells.contains(pos.asLong()) && !NavigationSafetyContext.forbidsBody(pos),
                    unionForbidden(NavigationSafetyContext.forbiddenBodyCells()), rejectedWorksites,
                    placementAttempts::allows, worksitePass, this::canPrepareWorksite);
            stanceNavigation.selectPass(worksitePass);
        }
        var progress = worksiteSearch.advance(256);
        worksiteProgress = progress;
        note = "evaluating build worksite coverage: " + progress.candidateChecks() + " stances, "
                + (progress.best() == null ? 0 : progress.best().coverage()) + " placeable cells";
        if (!progress.complete()) return TaskState.RUNNING;
        worksite = progress.best(); worksiteSearch = null; worksiteSearched = true;
        worksiteRouteAt = 0;
        if (worksite == null) return nextWorksitePass("no usable worksite covers the unfinished construction layer");
        if (worksite != null) {
            worksiteAttempts++; worksiteMovement.reset();
            BuildTaskRecord.Target first = worksite.placements().getFirst().target();
            for (int i = queueAt; i < queue.size(); i++) if (queue.get(i).target() == first) {
                Collections.swap(queue, queueAt, i); cell = queue.get(queueAt); break;
            }
            gesture = worksite.placements().getFirst().gesture(); liveGestures = List.of(); gestureAt = 0;
            stanceNavigation.forTarget(cell.target().pos(), PlayerNav.playerFeet(player));
            stanceNavigation.selectPass(worksitePass);
        }
        // 没有直线路径不代表无路可走，普通寻路仍会尝试绕过障碍物。
        phase = Phase.PLACE_NAV; return TaskState.RUNNING;
    }

    private TaskState nextWorksitePass(String reason) {
        worksite = null; worksiteSearch = null; worksiteSearched = false;
        if (worksitePass < 2 && (worksitePass == 0 || permit().mayAlter())) {
            worksitePass++; worksiteAttempts = 0; rejectedWorksites.clear();
            stanceNavigation.selectPass(worksitePass); phase = Phase.WORKSITE;
            note = reason + "; evaluating shared " + stanceNavigation.stage();
            return TaskState.RUNNING;
        }
        // 共享路线预算有界，但不能因此跳过独立核验的支撑方案回退。
        if (cell != null) {
            TaskState support = prepareTemporarySupports();
            if (support != null) return support;
        }
        failAt(cell == null ? siteMin : cell.target().pos(), reason + "; no shared construction access was proven",
                FailureType.NO_PATH, "construction_worksite_unproven", false);
        return TaskState.FAILED;
    }

    private boolean canPrepareWorksite(BlockPos feet) {
        if (!player.level().isLoaded(feet) || !player.level().isLoaded(feet.below())) return false;
        if (feet.getY() <= player.level().getMinBuildHeight() || feet.getY() > player.level().getMaxBuildHeight()) return false;
        try {
        var box = player.getBoundingBox();
        var corridor = new GroundCorridor(player.level(), player.level()::isLoaded,
                box.getXsize(), box.getYsize(), unionForbidden(NavigationSafetyContext.forbiddenBodyCells()),
                EmbeddedBaritoneRuntime.physicalObstacles());
        Vec3 standing = corridor.stance(feet);
        return standing != null && Math.abs(standing.y - feet.getY()) < 1e-5
                || player.level().getBlockState(feet.below()).isAir() && scaffoldPermitted(feet.below(), null);
        } catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    // 只沿现有地面走向选中的站位。走途中若能放置，placeNavTick 会先接手；到达后仍没有机会则排除该站位重找。
    private TaskState walkToWorksite() {
        var context = ClientRuntime.requireContext(player);
        if (nav != null && worksiteMovement.observe(player.position(), worksite.feet(), context.tickRevision(), nav.executionStep(context.tickRevision()))
                && nav.isSafeToCancel()) return rejectWorksite("worksite approach made no useful movement or confirmed construction progress");
        if (worksite.constructionAccess()) {
            if (nav == null) {
                BlockPos destination = worksite.stance(); stanceNavigation.attempted();
                nav = PlayerNav.toGoal(player, () -> NavGoal.exact(destination), BuildStanceNavigation.PRECISE_WALK,
                        () -> placementStanceReached(destination), this).walkingOnly();
            }
            return switch (nav.tick()) {
                case RUNNING -> TaskState.RUNNING;
                case FAILED -> rejectWorksite(nav.failReason());
                case ARRIVED -> {
                    stopNav();
                    worksite = new BuildWorksitePlanner.Worksite(worksite.stance(), player.position(), worksite.placements(),
                            0, 0, List.of(player.position()), false);
                    worksiteRouteAt = 0; yield TaskState.RUNNING;
                }
            };
        }
        if (nav == null) for (int at = worksiteRouteAt; at < worksite.route().size(); at++)
            if (BlockPos.containing(worksite.route().get(at)).equals(PlayerNav.playerFeet(player))
                    && Math.abs(worksite.route().get(at).y - player.getY()) < .51) worksiteRouteAt = at + 1;
        if (worksiteRouteAt >= worksite.route().size()) {
            return rejectWorksite("worksite reached but no current native placement was available");
        }
        if (nav == null) {
            Vec3 next = worksite.route().get(worksiteRouteAt);
            BlockPos destination = BlockPos.containing(next);
            stanceNavigation.attempted();
            // 共线小段已合并：这一份导航持续走到拐角或最终站位，途中原生路径仍实时检查障碍和禁行格。
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(destination), BuildStanceNavigation.PRECISE_WALK,
                    () -> placementStanceReached(destination),
                    stanceNavigation.walkingContext((int) Math.floor(Math.min(player.getY(), next.y)))).walkingOnly();
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> { stopNav(); worksiteRouteAt++; yield TaskState.RUNNING; }
            case FAILED -> rejectWorksite(nav.failReason());
        };
    }

    private TaskState rejectWorksite(String reason) {
        stanceNavigation.failed(worksite.stance(), reason); rejectedWorksites.add(worksite.stance());
        stopNav(); worksite = null; worksiteSearch = null; worksiteSearched = false;
        note = reason; phase = Phase.WORKSITE; return TaskState.RUNNING;
    }

    private boolean supportExists(BuildPlacementGeometry.Gesture g) {
        if (!player.level().isLoaded(g.clicked())) return false;
        BlockState live = player.level().getBlockState(g.clicked());
        if (g.clicked().equals(cell.target().pos())) return !live.isAir();
        return !live.isAir() && !live.canBeReplaced();
    }

    private TaskState selectItemTick() {
        // 外界已完成这一格时仍优先退回实地，不为无需再点击的目标等待选物姿态。
        if (currentPlacementComplete()) { finishPlaced(); return TaskState.RUNNING; }
        // 原路线刚结束或身体意外腾空时先落稳，不能提前开背包、选物或用潜行截断登阶。
        if (!placementFooting.ready(player)) { placementProofFeet = null; return holdWhileFootingSettles(); }
        if (!EmbeddedBaritoneRuntime
                .yieldActiveForExternalAction(player)) return TaskState.RUNNING;
        placementPostureAndMotion();
        if (placementAccess != null && placementAccess.edgeActive()) {
            // 材料必须在锚点选好；檐边若被外来操作换掉手持物，就先退回，不在此处开背包松潜行。
            if (placementAccess.hold() == BuildPlacementAccessDrive.Status.FAILED
                    || !player.getMainHandItem().is(cell.target().item()) || selection.pending())
                return failAfterEdgeReturn("The held construction item changed at the edge", "placement_edge_item_changed");
            phase = Phase.AIM; aimConvergence.reset(); aimProgress.reset(); lastAimObservation = Map.of();
            return TaskState.RUNNING;
        }
        TaskState selected = prepareHeldItem(); if (selected != TaskState.SUCCESS) return selected;
        TaskState footing = refreshPlacementFooting(); if (footing != null) return footing;
        phase = Phase.AIM; aimConvergence.reset(); aimProgress.reset(); lastAimObservation = Map.of();
        return TaskState.RUNNING;
    }

    private TaskState prepareHeldItem() {
        // 普通放置与檐边前的锚点准备共用同一原生取物流程，等可见背包与选中回执收尾后才继续。
        int slot;
        // 根据交换后的背包核对物品归属前，必须先完成原生交换回执。
        // 换槽已经开始就继续等待同一个槽位，不能在交易尚未确认时因为背包暂态又改选其他物品。
        if (selection.started()) slot = selection.requestedSlot();
        else if (player.getAbilities().instabuild) {
            var status = creativeMaterials.ensure(ClientRuntime.requireContext(player), cell.target().item(), creativeNeeds());
            if (status == CreativeBuildMaterialSupply.Status.FAILED) return creativeSupplyFailed();
            if (status == CreativeBuildMaterialSupply.Status.WAITING) return TaskState.RUNNING;
            slot = creativeMaterials.slot();
        } else slot = inventory.findSlot(cell.target().item(), true);
        if (slot < 0) {
            missing.clear();
            missing.putAll(currentShortfall());
            failAt(cell.target().pos(), "required block item is not in synchronized inventory",
                    FailureType.NO_MATERIAL, "material_exhausted", false); return TaskState.FAILED;
        }
        FirstPersonActionGate.Status status = selection.select(player, slot);
        creativeMaterials.swapped(player, selection.takeConfirmedSwap());
        if (status == FirstPersonActionGate.Status.RUNNING) return TaskState.RUNNING;
        if (status == FirstPersonActionGate.Status.FAILED) {
            failAt(cell.target().pos(), "item selection failed: " + selection.failure(),
                    FailureType.UNKNOWN, "item_selection_failed", false); return TaskState.FAILED;
        }
        if (!player.getMainHandItem().is(cell.target().item())) { selection.reset(); return TaskState.RUNNING; }
        return TaskState.SUCCESS;
    }

    private TaskState aimTick() {
        // 先真正转头到位，复查视线会点到哪，再预测会放成什么状态，并检查是否把自己或生物卡进方块。
        if (currentPlacementComplete()) { finishPlaced(); return TaskState.RUNNING; }
        // 支撑与身体证明必须在新点击之前检查；已有点击由 WAIT_USE 先收回执，不能中途重发。
        if (placementAccess != null && placementAccess.edgeActive()
                && placementAccess.hold() == BuildPlacementAccessDrive.Status.FAILED)
            return failAfterEdgeReturn(placementAccess.failure(), "placement_edge_hold_failed");
        if (!player.getMainHandItem().is(cell.target().item())) {
            selection.reset(); phase = Phase.SELECT_ITEM; return TaskState.RUNNING;
        }
        TaskState footing = refreshPlacementFooting(); if (footing != null) return footing;
        Vec3 eye = player.getEyePosition();
        Vec3 desired = gesture.point().subtract(eye).normalize();
        aimError = Math.toDegrees(Math.acos(Math.clamp(player.getViewVector(1).normalize().dot(desired), -1, 1)));
        placementPostureAndMotion();
        InputDriver.lookAt(player, gesture.point());
        if (player.isShiftKeyDown() != gesture.sneak()) return waitForAim("waiting_for_posture", false);
        HitResult crosshair = placementRay.apply(player, 4.5);
        aimHit = crosshair.getType().name().toLowerCase(Locale.ROOT);
        if (crosshair instanceof BlockHitResult blockHit && crosshair.getType() == HitResult.Type.BLOCK)
            aimHit += ":" + BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(blockHit.getBlockPos()).getBlock());
        if (crosshair.getType() != HitResult.Type.BLOCK || !(crosshair instanceof BlockHitResult hit)
                || !placesAt(hit, cell.target().pos())) {
            return waitForAim("crosshair_does_not_place_target", aimConvergence.ready(player, gesture.point().subtract(eye)));
        }
        BlockState before = player.level().getBlockState(cell.target().pos());
        BlockState predicted = BuildPlacementGeometry.predict(
                player, cell.target(), hit, player.getYRot(), player.getXRot());
        if (predicted == null || (!cell.target().itemPlace() && !cell.target().acceptsPlacedState(predicted)
                && !BuildPlacementGeometry.isProgress(cell.target(), before, predicted))) {
            return waitForAim("native_placement_state_mismatch", aimConvergence.ready(player, gesture.point().subtract(eye)));
        }
        // 转动镜头跨过朝向边界时，可能短暂出现有效射线和方块状态；点击前先让实际视角稳定，给普通玩家 tick 时间同步。
        if (!placementSettling.ready(player, gesture, cell.target().pos(), hit, predicted))
            return waitForAim("waiting_for_view_settle", false);
        BlockState placedPrimary = predicted == null ? cell.target().desiredState() : predicted;
        if (placementBlockedByPlayer(placedPrimary)) return rejectPlayerOccupiedGesture();
        if (placementBlockedByEntity(placedPrimary)) {
            InputDriver.halt(player);
            failAt(cell.target().pos(),
                    "a living or building-blocking entity occupies the placement effect",
                    FailureType.ENTITY_BLOCKED, "placement_entity_blocked", false);
            return TaskState.FAILED;
        }
        Map<Long, BlockState> frozen = freeze(cell);
        // 点击前保存相关格子的状态，之后用变化来确认这一次点击是否生效，不能仅凭“发出点击”算完成。
        if (!r.mutationGuardMatches(player, cell.target().pos())
                || cell.generated().stream().anyMatch(effect -> !r.mutationGuardMatches(player, effect.pos()))) {
            failAt(cell.target().pos(), "observed target changed before placement", FailureType.TARGET_LOST,
                    "build_target_changed", false); return TaskState.FAILED;
        }
        LocalPlayerContext ctx = ClientRuntime.requireContext(player);
        if (isTemporary(cell) && !scaffoldPermitted(cell.target().pos(), null))
            return failAfterEdgeReturn("The next support position changed before the native click", "support_step_site_changed");
        aimWaitReason = "placement_submitted";
        useReceipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, hit,
                confirmation(cell, frozen, placedPrimary), USE_TIMEOUT);
        phase = Phase.WAIT_USE; return TaskState.RUNNING;
    }

    // 停键 -> 落稳 -> 重证实际脚位。身体变化使旧证明失效时返回导航，不把下落造成的遮挡记成手法或站位失败。
    private TaskState refreshPlacementFooting() {
        if (!placementFooting.ready(player)) {
            placementProofFeet = null;
            aimWaitReason = "waiting_for_stable_footing";
            return holdWhileFootingSettles();
        }
        if (gesture == placementProofGesture && placementProofFeet != null
                && placementProofFeet.distanceToSqr(player.position()) <= .0001) return null;
        var refreshed = BuildPlacementGeometry.recheckCurrentGesture(player, cell.target(), targets, gesture);
        if (refreshed == null) {
            // 檐边证明失效也先由原控制器保持潜行并退回锚点，不能当作普通地面重规划而先松开 Shift。
            if (placementAccess != null && placementAccess.edgeActive())
                return failAfterEdgeReturn("The actual edge placement could not be re-proved", "placement_footing_changed");
            InputDriver.halt(player); stopNav(); selection.reset(); aimConvergence.reset();
            gesture = null; gestureFromCurrent = false; placementProofFeet = null;
            phase = Phase.PLACE_NAV; note = "placement footing changed; proving a new live approach";
            return TaskState.RUNNING;
        }
        // 平地连续走放已有独立通道证明：只刷新当前射线证明，不因同层水平小步每刻重置镜头收敛。
        boolean sameApproach = gesture == placementProofGesture && placementProofFeet != null
                && Math.abs(placementProofFeet.y - player.getY()) <= .01
                && gesture.stance().equals(player.blockPosition());
        if (!sameApproach) {
            // 同一脚下格的旧点击点仍成立就保留其身份，避免浮点重裁剪制造新的失败账条目。
            if (!gesture.stance().equals(refreshed.stance()) || gesture.sneak() != refreshed.sneak()) gesture = refreshed;
            aimConvergence.reset(); aimProgress.reset();
        }
        placementProofGesture = gesture; placementProofFeet = player.position();
        return null;
    }

    // 普通实地等待可停键；檐边必须续原生潜行，保持失败则完整交还退回阶段，不能吞成普通等待。
    private TaskState holdWhileFootingSettles() {
        if (placementAccess != null && placementAccess.edgeActive()) {
            if (placementAccess.hold() == BuildPlacementAccessDrive.Status.FAILED)
                return failAfterEdgeReturn(placementAccess.failure(), "placement_edge_hold_failed");
        } else InputDriver.halt(player);
        return TaskState.RUNNING;
    }

    // 瞄准还在改善就继续等；视角已稳定且误差极小，或四十刻没有足够改善时，记录这次点击失败并换方案。
    private TaskState waitForAim(String reason, boolean settled) {
        aimWaitReason = reason;
        lastAimObservation = placementDiagnostics();
        // 接近半砖高度边界时，即使视线只差一度，实际命中也可能不同。
        if (settled && aimError <= .05 || aimProgress.stalled(ClientRuntime.requireContext(player).tickRevision(), aimError)) {
            if (!settled) aimWaitReason = reason + "_not_progressing";
            return rejectGesture();
        }
        return TaskState.RUNNING;
    }

    // 能保证放置前后通道都安全时继续短距离走动，否则停下，并按这次点击需要选择站立或蹲下。
    private void placementPostureAndMotion() {
        if (gesture == null || cell == null) { InputDriver.halt(player); return; }
        Map<BlockPos, BlockState> effects = new LinkedHashMap<>();
        effects.put(cell.target().pos(), cell.target().desiredState());
        cell.generated().forEach(generated -> effects.put(generated.pos(), generated.expected()));
        if (!gesture.sneak() && BuildPlacementMotion.continueApproach(
                ClientRuntime.requireContext(player), placementWalkTarget, effects, forbiddenBodyCells)) return;
        InputDriver.halt(player);
        InputDriver.sneak(player, gesture.sneak());
    }

    private boolean placementBlockedByPlayer(BlockState primary) {
        if (rules.blockedByPlayer(cell.target().pos(), primary)) return true;
        for (BuildPlacementGeometry.GeneratedCell generated : cell.generated()) {
            if (rules.blockedByPlayer(generated.pos(), generated.expected())) return true;
        }
        return false;
    }

    private boolean placementBlockedByEntity(BlockState primary) {
        if (rules.blockedByEntity(cell.target().pos(), primary)) return true;
        for (BuildPlacementGeometry.GeneratedCell generated : cell.generated()) {
            if (rules.blockedByEntity(generated.pos(), generated.expected())) return true;
        }
        return false;
    }

    private TaskState rejectPlayerOccupiedGesture() {
        // 自己站在准备放块的位置上，就跳过这个站位的所有手法，换一个位置，避免原地换角度仍把自己堵住。
        BlockPos occupiedFeet = PlayerNav.playerFeet(player);
        placementAttempts.reject(cell.target(), gesture);
        placementAttempts.rejectStance(cell.target(), occupiedFeet);
        if (placementAccess != null) placementAccess.rejectedGesture();
        InputDriver.halt(player); stopNav(); selection.reset(); aimConvergence.reset();
        gesture = null;
        if (!gestureFromCurrent) gestureAt++;
        while (gestureAt < liveGestures.size()
                && liveGestures.get(gestureAt).stance().equals(occupiedFeet)) gestureAt++;
        gestureFromCurrent = false;
        phase = Phase.PLACE_NAV;
        return TaskState.RUNNING;
    }

    private TaskState rejectGesture() {
        placementAttempts.reject(cell.target(), gesture);
        if (placementAccess != null) placementAccess.rejectedGesture();
        lastPlacementRejection = placementDiagnostics();
        InputDriver.halt(player); stopNav(); selection.reset();
        aimConvergence.reset(); gesture = null;
        if (!gestureFromCurrent) gestureAt++;
        gestureFromCurrent = false; phase = Phase.PLACE_NAV;
        return TaskState.RUNNING;
    }

    private TaskState waitUseTick() {
        // 点击已发出就等待这一次的结果；明确没生效才换手法，结果不确定或变成别的东西则停止并报告。
        placementPostureAndMotion();
        LocalPlayerContext ctx = ClientRuntime.requireContext(player);
        useReceipt = ctx.actions().poll(ctx, useReceipt);
        if (!useReceipt.terminal()) return TaskState.RUNNING;
        NativeActionReceipt.Status status = useReceipt.status();
        String detail = useReceipt.detail(); useReceipt = null;
        if (status == NativeActionReceipt.Status.CONFIRMED_NOT_APPLIED) return rejectGesture();
        if (status != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            failAt(cell.target().pos(), "native placement not safely confirmed: " + detail,
                    FailureType.UNKNOWN,
                    "placement_" + status.name().toLowerCase(),
                    status == NativeActionReceipt.Status.UNCERTAIN
                            || status == NativeActionReceipt.Status.DIVERGED);
            return TaskState.FAILED;
        }
        useCount++;
        confirmedBlockChange(cell.target().pos());
        for (BuildPlacementGeometry.GeneratedCell effect : cell.generated()) confirmedBlockChange(effect.pos());
        // 支撑收到原生确认即进入既有账本；即使随后退回锚点受阻，也不能把这块已消耗材料遗忘为无主方块。
        if (isTemporary(cell)) confirmedScaffold(cell.target().pos(), player.level().getBlockState(cell.target().pos()));
        if (currentPlacementComplete()) {
            if (placementAccess != null && placementAccess.edgeActive()) phase = Phase.EDGE_RETURN;
            else finishPlaced();
            return TaskState.RUNNING;
        }
        BlockState live = player.level().getBlockState(cell.target().pos());
        if (useCount < BuildPlacementGeometry.maximumUses(cell.target())
                && BuildPlacementGeometry.isProgress(cell.target(), Blocks.AIR.defaultBlockState(), live)) {
            // 雪层、双层台阶等需要多次放置；看到已经朝目标前进时，继续补下一次，而不是把中间状态拆掉。
            renewBuildProgress();
            liveGestures = List.of(); gestureSearch = null; gestureProgress = null;
            gestureAt = 0; gesture = null; selection.reset(); aimConvergence.reset();
            phase = Phase.PLACE_NAV;
            return TaskState.RUNNING;
        }
        failAt(cell.target().pos(), "click changed world but not to the requested verified state",
                FailureType.UNSUPPORTED, "placement_state_mismatch", true); return TaskState.FAILED;
    }

    // 把这一格记入 placed 并续期。某些入口在别人刚好完成目标时也会调用这里，因此 placed 并不严格等于本任务确认点击数。
    private void finishPlaced() {
        // 所有成功入口共用安全退回：SELECT_ITEM、AIM 和外界已完成都不能绕过这个步骤。
        if (placementAccess != null && placementAccess.edgeActive()) { phase = Phase.EDGE_RETURN; return; }
        if (isTemporary(cell) && useCount > 0)
            confirmedScaffold(cell.target().pos(), player.level().getBlockState(cell.target().pos()));
        // 保留创造模式所需材料供后续格使用，不要每次点击后清空快捷栏槽位。
        r.placedOne(); renewBuildProgress(); markComplete(cell);
        finishCell();
    }

    private TaskState drivePlacementAccess() {
        var access = placementAccess.tick(); r.extendDeadlineTo(placementAccess.deadline());
        if (access == BuildPlacementAccessDrive.Status.UNAVAILABLE) return null;
        if (access == BuildPlacementAccessDrive.Status.FAILED) {
            return failAfterEdgeReturn(placementAccess.failure(), "placement_access_failed");
        }
        if (access == BuildPlacementAccessDrive.Status.READY) {
            gesture = placementAccess.gesture(); gestureFromCurrent = true; placementWalkTarget = null; phase = Phase.SELECT_ITEM;
        }
        return TaskState.RUNNING;
    }

    private TaskState returnFromPlacementEdge() {
        // 本次点击已经结算，只做原生潜行退回；退不回时保留已放方块和支撑账，不再发第二次放置。
        var result = placementAccess.returnToAnchor();
        if (result == BuildPlacementAccessDrive.Status.RUNNING) return TaskState.RUNNING;
        if (result != BuildPlacementAccessDrive.Status.READY) {
            failAt(cell.target().pos(), "Placement was confirmed but the safe anchor could not be regained: " + placementAccess.failure(),
                    FailureType.NO_PATH, "placement_edge_return_failed", false); return TaskState.FAILED;
        }
        if (edgeReturnFailure != null) {
            String message = edgeReturnFailure, code = edgeReturnFailureCode;
            edgeReturnFailure = edgeReturnFailureCode = null;
            failAt(cell.target().pos(), message, FailureType.NO_PATH, code, false); return TaskState.FAILED;
        }
        finishPlaced(); return TaskState.RUNNING;
    }

    private TaskState failAfterEdgeReturn(String message, String code) {
        // 失败也先尝试原生退回已验证锚点；不为失败补点、不把已确认放置记成未发生。
        if (placementAccess != null && placementAccess.edgeActive()) {
            edgeReturnFailure = message; edgeReturnFailureCode = code; phase = Phase.EDGE_RETURN;
            return TaskState.RUNNING;
        }
        if (failureCode == null) failAt(cell.target().pos(), message, FailureType.NO_PATH, code, false);
        return TaskState.FAILED;
    }

    // 动作确认后通知外部观察守卫更新事实，再使附近的失败点击和搜索结果失效。
    private void confirmedBlockChange(BlockPos pos) {
        r.confirmedMutation(player, pos);
        placementEnvironmentChanged(pos);
    }

    // 附近方块变了，旧的“这里放不了”可能已经不成立；清掉六格内对应记录，允许用新支撑重试。
    private void placementEnvironmentChanged(BlockPos pos) {
        if (worksite != null) worksiteMovement.changed(pos);
        placementAttempts.changedNear(pos);
        exhaustedPlacementStates.keySet().removeIf(key -> BlockPos.of(key).distSqr(pos) <= 36);
        if (cell != null && cell.target().pos().distSqr(pos) <= 36) {
            stanceNavigation.environmentChanged();
            gestureSearch = null; gestureProgress = null;
            liveGestures = List.of(); gestureAt = 0;
            // 让当前行走继续绑定原有交互动作；等动作稳定后再重新枚举候选。
        }
    }

    // 只回收以后不再需要的自有创造材料；每次改槽都等确认，清完再重新验收世界。
    private TaskState clearCreativeTick() {
        var status = creativeMaterials.releaseUnused(ClientRuntime.requireContext(player), creativeNeeds());
        if (status == CreativeBuildMaterialSupply.Status.FAILED) return creativeSupplyFailed();
        if (status == CreativeBuildMaterialSupply.Status.WAITING) return TaskState.RUNNING;
        beginVerify(); return TaskState.RUNNING;
    }

    private TaskState creativeSupplyFailed() {
        failAt(cell == null ? siteMin : cell.target().pos(), creativeMaterials.failure(), creativeMaterials.failureType(),
                "creative_material_supply_failed", creativeMaterials.uncertain());
        return TaskState.FAILED;
    }

    // 按剩余队列记录每种材料下一次使用有多远，并补上世界里又变坏的目标；没有后续用途才能清理。
    private ToIntFunction<Item> creativeNeeds() {
        return new ToIntFunction<>() {
            private Map<Item, Integer> next;
            public int applyAsInt(Item item) {
                if (next == null) {
                    next = new HashMap<>(); int order = 0;
                    for (int i = queueAt; i < queue.size(); i++, order++) {
                        var pending = queue.get(i);
                        if (pending.target().costsMaterial() && !constructionMatches(pending.target(), pending.generated()))
                            next.putIfAbsent(pending.target().item(), order);
                    }
                    // 核验后目标发生变化时，仍需保留其缓存材料。
                    for (var target : r.targets) if (target.costsMaterial() && (!player.level().isLoaded(target.pos())
                            || !target.constructionMatches(player.level().getBlockState(target.pos())))) next.putIfAbsent(target.item(), order++);
                }
                return next.getOrDefault(item, CreativeBuildInventory.NO_FUTURE_USE);
            }
        };
    }

    private TaskState deferOrFail() {
        // 所有放法都试完，可能只是支撑尚未建好，就先做别的格；若同样身体和世界状态再次失败，停止空转。
        long key = cell.target().pos().asLong();
        PlacementAttemptSignature signature = placementAttemptSignature();
        if (!exhaustedPlacementStates.computeIfAbsent(key, ignored -> new HashSet<>()).add(signature)) {
            if (tryTerrainPass()) return TaskState.RUNNING;
            TaskState recovery = prepareTemporarySupports();
            if (recovery != null) return recovery;
            failAt(cell.target().pos(),
                    "the complete finite stance set reached the same body and world state again without progress",
                    FailureType.NO_PATH, "placement_no_progress", false);
            return TaskState.FAILED;
        }

        List<CellPlan> pending = BuildTemporarySupportPlan.defer(queue, queueAt, this::layerPending);
        if (!isTemporary(cell) && pending.stream().anyMatch(candidate -> candidate != cell && onConstructionLayer(candidate))) {
            pending.sort(Comparator.comparingInt(candidate -> BuildLayerFrontier.layer(candidate.target())));
            queue = pending;
            queueAt = 0;
            note = "trying other cells in the same structural layer before temporary supports";
            phase = Phase.SELECT; return TaskState.RUNNING;
        }
        if (tryTerrainPass()) return TaskState.RUNNING;
        TaskState recovery = prepareTemporarySupports();
        if (recovery != null) return recovery;
        failAt(cell.target().pos(), "the complete finite first-person stance set was exhausted",
                FailureType.NO_PATH, "placement_stances_exhausted", false); return TaskState.FAILED;
    }

    private boolean tryTerrainPass() {
        if (supportedCell != null || isTemporary(cell) || liveGestures.isEmpty() || !stanceNavigation.allowTerrain()) return false;
        gestureAt = 0; phase = Phase.PLACE_NAV;
        note = "existing-footing alternatives exhausted; trying permitted construction access";
        return true;
    }

    // 临时点击支撑最终会拆掉，因此原目标必须本来就能存活，不能靠临时垫块永久托住沙子或悬空装饰。
    private TaskState prepareTemporarySupports() {
        // 只为获得可点击表面而放置的临时支撑，不得成为最终生存结构的必需方块。
        if (supportedCell != null || isTemporary(cell) || cell.target().block() instanceof FallingBlock
                || !cell.target().desiredState().canSurvive(player.level(), cell.target().pos())) return null;
        // 目标缺少点击面时直接寻找可搭建的支撑，不因预计拆除后的掉落物会靠近机器而提前停工。
        List<BlockPos> chain = BuildTemporarySupportPlan.find(player.level(), player.level()::isLoaded,
                cell.target().pos(), pos -> scaffoldPermitted(pos, null));
        if (chain.isEmpty()) return null;
        Item material = BuildTemporarySupportMaterials.choose(
                ScaffoldMaterials.of(player), scaffoldReservations(),
                inventory::mainInventoryCount, chain.size(), player.getAbilities().instabuild && !r.consumeMaterials);
        if (material == null) {
            // 原生施工准确告诉供料父任务缺哪种垫块；补齐后继续这份目标和支撑账，不让模型猜一个不在白名单里的方块。
            var demand = BuildTemporarySupportMaterials.supplyNeed(ScaffoldMaterials.of(player), scaffoldReservations(),
                    inventory::mainInventoryCount, chain.size());
            if (demand != null) temporarySupportDemand = Map.of(
                    "item_id", BuiltInRegistries.ITEM.getKey(demand.item()).toString(),
                    "required_final_count", demand.requiredFinalCount(), "support_blocks", chain.size(),
                    "allowed_items", ScaffoldMaterials.effectiveIds(player));
            failAt(cell.target().pos(), "placement needs " + chain.size()
                            + " spare full temporary support blocks after reserving every remaining permanent build target",
                    FailureType.NO_MATERIAL, "temporary_support_materials_missing", false);
            return TaskState.FAILED;
        }
        supportedCell = cell; supportChain = chain;
        supportMaterial = ((BlockItem) material).getBlock().defaultBlockState();
        // 垫块 -> 原目标逐块执行；每块沿用普通导航和右键，不预先证明整条支撑放完后还能走到哪里。
        stopNav();
        return enqueueSupports();
    }

    // 从接地端开始把支撑排进普通施工队列，实际放置成功后才记账，再继续原来的目标。
    private TaskState enqueueSupports() {
        List<CellPlan> prepared = new ArrayList<>();
        for (BlockPos pos : supportChain) {
            var target = new BuildTaskRecord.Target(supportMaterial, supportMaterial.getBlock().asItem(),
                    pos, "temporary click support", null, null, null).asItemPlace();
            temporaryTargets.put(pos.asLong(), target);
            prepared.add(new CellPlan(target, List.of()));
        }
        prepared.add(supportedCell);
        for (int i = queueAt + 1; i < queue.size(); i++) prepared.add(queue.get(i));
        queue = prepared; queueAt = 0; phase = Phase.SELECT;
        worksite = null; worksiteSearch = null; worksiteProgress = null;
        note = "placing " + supportChain.size() + " supports before the original target";
        return TaskState.RUNNING;
    }

    private boolean isTemporary(CellPlan plan) {
        return plan != null && temporaryTargets.get(plan.target().pos().asLong()) == plan.target();
    }

    private PlacementAttemptSignature placementAttemptSignature() {
        // 记录玩家站位、可用手法和目标附近方块，用来识别“又回到同样情况，没有进展”的重复尝试。
        BlockPos feet = PlayerNav.playerFeet(player);
        long hash = 0xcbf29ce484222325L;
        hash = mixStateHash(hash, liveGestures.size());
        for (BuildPlacementGeometry.Gesture candidate : liveGestures) {
            hash = mixStateHash(hash, candidate.stance().asLong());
            hash = mixStateHash(hash, candidate.clicked().asLong());
            hash = mixStateHash(hash, candidate.face().ordinal());
        }

        BlockPos target = cell.target().pos();
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos observed = target.offset(dx, dy, dz);
                    hash = mixStateHash(hash, observed.asLong());
                    boolean loaded = player.level().isLoaded(observed);
                    hash = mixStateHash(hash, loaded ? 1L : 0L);
                    if (loaded) hash = mixStateHash(
                            hash, player.level().getBlockState(observed).hashCode());
                }
            }
        }
        return new PlacementAttemptSignature(feet.asLong(), hash);
    }

    private static long mixStateHash(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private void finishCell() {
        if (!isTemporary(cell) && !BuildCellRules.isAirTarget(cell.target())) lastPlacedTarget = cell.target().pos();
        if (cell == supportedCell) releaseSupportPlan();
        exhaustedPlacementStates.remove(cell.target().pos().asLong());
        markComplete(cell); queueAt++; resetCell(); phase = Phase.SELECT;
    }
    private void releaseSupportPlan() {
        supportedCell = null; supportChain = List.of(); supportMaterial = null;
    }
    private void resetCell() {
        worksitePass = 0; worksiteAttempts = 0; worksiteMovement.reset();
        if (worksite != null && worksite.heightLoss() > 1e-5) worksite = null;
        layerKnown = false;
        stanceNavigation.startAt(PlayerNav.playerFeet(player));
        placementWalkTarget = null;
        gestureFromCurrent = false;
        worksiteSearch = null; worksiteSearched = worksite != null; rejectedWorksites.clear();
        if (worksite == null) worksiteProgress = null;
        aimProgress.reset(); aimWaitReason = "not_aiming"; aimHit = "not_checked";
        stopNav(); clearing = null; clearQueue = List.of(); clearAt = 0;
        liveGestures = List.of(); gestureSearch = null; gestureProgress = null; gestureAt = 0; gesture = null;
        useCount = 0; useReceipt = null; selection.reset(); aimConvergence.reset();
    }

    private void beginVerify() {
        // 开始新一轮成品复查，清掉上轮失败列表和通行检查结果，避免把旧证据当新结果。
        stopNav(); verifyAt = 0; verifyFailed.clear(); verifyFailureStates.clear();
        traversabilityScan = null;
        traversabilityResult = null;
        phase = Phase.VERIFY;
    }

    private TaskState verifyTick() {
        // 分刻重读每一格。发现变化会尝试有限修补；同一批错误状态重复出现时失败，而不是永远拆建循环。
        int budget = PREFLIGHT_BUDGET;
        while (verifyAt < r.targets.size() && budget-- > 0) {
            BuildTaskRecord.Target target = r.targets.get(verifyAt);
            if (!player.level().isLoaded(target.pos())) return travelToLoad(target.pos(), false);
            BlockState state = player.level().getBlockState(target.pos());
            if (r.scaffoldLedger().contains(target.pos()) && !state.isAir()
                    && !r.scaffoldLedger().owns(target.pos(), state)) {
                failAt(target.pos(), "a confirmed temporary scaffold changed before final verification",
                        FailureType.TARGET_LOST, "scaffold_verification_changed", true);
                return TaskState.FAILED;
            }
            if (target.matches(state)) completed.add(target.pos().asLong());
            else {
                completed.remove(target.pos().asLong());
                if (!target.constructionMatches(state) && !ownedAirScaffold(target, state)) {
                    verifyFailed.add(BuildPlacementGeometry.primaryOf(target).asLong());
                    verifyFailureStates.add(new ObservedCell(target.pos().asLong(), state));
                }
            }
            verifyAt++;
        }
        r.completed(completed.size());
        if (verifyAt < r.targets.size()) return TaskState.RUNNING;
        if (!verifyFailed.isEmpty()) {
            VerificationFailureSignature signature = verificationFailureSignature();
            if (!exhaustedVerificationStates.add(signature)) {
                failAt(BlockPos.of(verifyFailed.iterator().next()),
                        "final verification repeated the same mismatched world state without net progress",
                        FailureType.TARGET_LOST, "final_verification_no_progress", false);
                return TaskState.FAILED;
            }
            List<CellPlan> repairs = new ArrayList<>();
            for (long key : verifyFailed) {
                CellPlan plan = plansByPrimary.get(key);
                if (plan != null && !repairs.contains(plan)) repairs.add(plan);
            }
            if (repairs.size() != verifyFailed.size()) {
                failAt(BlockPos.of(verifyFailed.iterator().next()),
                        "a pre-existing cell changed and has no primary repair plan",
                        FailureType.TARGET_LOST, "externally_changed_preexisting_cell", false);
                return TaskState.FAILED;
            }
            queue = repairs; queueAt = 0; phase = Phase.SELECT;
            note = "final verification repaired bounded outside changes";
            return TaskState.RUNNING;
        }
        drainScaffolds(); unregisterProvider();
        scaffoldQueue = scaffolds.stream().filter(p -> !targets.containsKey(p.asLong())
                        || BuildCellRules.isAirTarget(targets.get(p.asLong())))
                .sorted(Comparator.comparingInt((BlockPos position) -> position.getY()).reversed()
                        .thenComparingDouble(p -> p.distSqr(player.blockPosition()))).toList();
        deferredScaffolds.clear(); scaffoldPassRemovals = 0; scaffoldCleanupPasses++;
        scaffoldAt = 0; phase = Phase.SCAFFOLD_SELECT; return TaskState.RUNNING;
    }

    private VerificationFailureSignature verificationFailureSignature() {
        return new VerificationFailureSignature(verifyFailureStates);
    }

    private TaskState scaffoldSelectTick() {
        // 成品格子都对上后，先清理自己的临时支撑；还有支撑或目标不匹配时不能宣布整项成功。
        while (scaffoldAt < scaffoldQueue.size()) {
            scaffold = scaffoldQueue.get(scaffoldAt);
            if (!player.level().isLoaded(scaffold)) {
                failAt(scaffold, "path scaffold unloaded before cleanup", FailureType.TARGET_LOST,
                        "scaffold_unloaded", false); return TaskState.FAILED;
            }
            if (player.level().getBlockState(scaffold).isAir()) {
                r.scaffoldLedger().cleared(scaffold); scaffoldAt++; continue;
            }
            // 先找当前位置就能安全拆的自有支撑，避免为远处高柱反复走动，仍在破坏边界重查所有权。
            for (int i = scaffoldAt; i < scaffoldQueue.size(); i++) {
                BlockPos at = scaffoldQueue.get(i);
                if (onOwnedColumn(at) || !player.level().isLoaded(at) || !scaffoldMutationAllowed(at, player.level().getBlockState(at))
                        || at.distToCenterSqr(player.getEyePosition()) > 36) continue;
                var ready = new BuildScaffoldCleanup(player, at, forbiddenBodyCells);
                if (!ready.ready()) continue;
                var ordered = new ArrayList<>(scaffoldQueue); ordered.remove(i); ordered.add(scaffoldAt, at);
                scaffoldQueue = List.copyOf(ordered); scaffold = at; scaffoldCleanup = ready;
                phase = Phase.SCAFFOLD_BREAK; return TaskState.RUNNING;
            }
            // 身边可安全拆的都先拆完，再按已证明的整柱退路下降一格；保留脚下整柱，不能从中间掏空回程。
            BlockPos underfoot = player.blockPosition().below();
            if (player.onGround() && r.scaffoldLedger().contains(underfoot) && !rejectedScaffoldDescents.contains(underfoot)) {
                scaffold = underfoot;
                scaffoldDescent = new BuildScaffoldDescentDrive(player, underfoot, r.scaffoldLedger().snapshot(),
                        at -> player.level().isLoaded(at) && scaffoldMutationAllowed(at, player.level().getBlockState(at)),
                        forbiddenBodyCells, stanceNavigation.walkingContext(Integer.MIN_VALUE), this::recordScaffoldBreak);
                phase = Phase.SCAFFOLD_DESCENT; return TaskState.RUNNING;
            }
            if (onOwnedColumn(scaffold)) {
                deferredScaffolds.add(scaffold); scaffoldAt++; continue;
            }
            scaffoldCleanup = new BuildScaffoldCleanup(player, scaffold, forbiddenBodyCells);
            phase = Phase.SCAFFOLD_NAV; return TaskState.RUNNING;
        }
        if (!deferredScaffolds.isEmpty()) {
            // 只有本轮真实确认拆掉了支撑，才有新的通路证据可重试；整轮无变化就保留剩余账并停止。
            if (scaffoldPassRemovals == 0) {
                if (beginScaffoldAccess()) return TaskState.RUNNING;
                failAt(deferredScaffolds.iterator().next(), "No support was removed in the complete cleanup pass; unreachable supports remain tracked",
                        FailureType.NO_PATH, "scaffold_cleanup_no_progress", false); return TaskState.FAILED;
            }
            scaffoldQueue = List.copyOf(deferredScaffolds); deferredScaffolds.clear();
            scaffoldAt = 0; scaffoldPassRemovals = 0; scaffoldCleanupPasses++;
            phase = Phase.SCAFFOLD_SELECT; return TaskState.RUNNING;
        }
        finalStateAt = 0; phase = Phase.FINAL_STATE;
        return TaskState.RUNNING;
    }

    /**
     * 普通结构与临时支撑处理完后，逐格验收明确要求的最终状态。当前只会自动修复可手开木门的开关状态。
     * 其他状态不符时保留已有方块并报告无法调整；门修好后从头复查，避免走动又打开前面刚关上的门。
     */
    private TaskState finalStateTick() {
        if (doorRepair != null) {
            TaskState status = doorRepair.tick();
            if (status == TaskState.RUNNING) return status;
            if (status != TaskState.SUCCESS) {
                failAt(cell.target().pos(), doorRepair.failure(), doorRepair.failureType(),
                        "final_state_adjustment_failed", doorRepair.uncertain());
                doorRepair.stop(); doorRepair = null; return TaskState.FAILED;
            }
            if (doorRepair.changed()) {
                confirmedBlockChange(cell.target().pos());
                cell.generated().forEach(effect -> confirmedBlockChange(effect.pos()));
                renewBuildProgress();
            }
            doorRepair.stop(); doorRepair = null; finalStateAt = 0;
        }
        int budget = PREFLIGHT_BUDGET;
        while (finalStateAt < r.targets.size() && budget-- > 0) {
            var target = r.targets.get(finalStateAt);
            if (!player.level().isLoaded(target.pos())) return travelToLoad(target.pos(), false);
            stopNav();
            BlockState live = player.level().getBlockState(target.pos());
            cell = plansByPrimary.get(BuildPlacementGeometry.primaryOf(target).asLong());
            if (cell != null) for (var generated : cell.generated())
                if (!player.level().isLoaded(generated.pos())) return travelToLoad(generated.pos(), false);
            if (target.matches(live) && (cell == null || generatedFinalMatches(cell))) { finalStateAt++; continue; }
            if (!target.constructionMatches(live)) { beginVerify(); return TaskState.RUNNING; }
            if (cell == null || !BuildDoorStateRepair.canRepair(cell.target(), targets,
                    player.level()::isLoaded, player.level()::getBlockState, this::stateAdjustmentAllowed)) {
                failAt(target.pos(), "the required final state has no safe native adjustment; existing blocks were retained",
                        FailureType.UNSUPPORTED, "final_state_adjustment_unsupported", false);
                return TaskState.FAILED;
            }
            if (finalStateAttempts.merge(cell.target().pos().asLong(), 1, Integer::sum) > 3) {
                failAt(target.pos(), "door state repeatedly changed during final verification",
                        FailureType.TARGET_LOST, "final_state_adjustment_no_progress", false);
                return TaskState.FAILED;
            }
            stopNav(); doorRepair = new BuildDoorStateRepair(player, cell.target(), targets,
                    this::stateAdjustmentAllowed, placementRay);
            return TaskState.RUNNING;
        }
        if (finalStateAt < r.targets.size()) return TaskState.RUNNING;
        r.completed(countMatching());
        if (r.completed() != r.targets.size() || !plans.stream().allMatch(this::generatedFinalMatches)) {
            finalStateAt = 0; return TaskState.RUNNING;
        }
        if (r.traversabilityContract() != null) {
            traversabilityScan = BuildTraversabilityVerifier.begin(
                    player.clientLevel, r.traversabilityContract());
            phase = Phase.ROUTE_VERIFY;
            return TaskState.RUNNING;
        }
        retainVerifiedSitePosition();
        return TaskState.SUCCESS;
    }

    // 收尾调整仍要经过继承保护、当前导航保护和工程现场检查，不能因为是“收尾”就获得额外修改许可。
    private boolean stateAdjustmentAllowed(BlockPos pos) {
        return !inheritedProtectedMutationCells.contains(pos.asLong())
                && !NavigationSafetyContext.protectsMutation(pos) && !NavigationSafetyContext.forbidsBody(pos)
                && r.mutationGuardMatches(player, pos);
    }

    private TaskState routeVerifyTick() {
        // 有进门／上楼通行要求的方案，还要检查这些地方实际连得通；方块都对并不一定代表房子能用。
        traversabilityResult = traversabilityScan.tick();
        if (traversabilityResult == null) {
            // 本 tick 已完成新的有限核验工作；让出线程时不应因主线程调度公平性而扣减施工剩余的原生操作预算。
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return TaskState.RUNNING;
        }
        if (!traversabilityResult.valid()) {
            failAt(traversabilityResult.position(), traversabilityResult.message(),
                    FailureType.NO_PATH, traversabilityResult.code(), false);
            return TaskState.FAILED;
        }
        if (countMatching() != r.targets.size() || !plans.stream().allMatch(this::generatedFinalMatches)) {
            finalStateAt = 0; phase = Phase.FINAL_STATE; return TaskState.RUNNING;
        }
        retainVerifiedSitePosition();
        return TaskState.SUCCESS;
    }

    private void retainVerifiedSitePosition() {
        if (siteMin == null || siteMax == null) return;
        BlockPos center = new BlockPos(
                Math.floorDiv(siteMin.getX() + siteMax.getX(), 2),
                siteMin.getY(),
                Math.floorDiv(siteMin.getZ() + siteMax.getZ(), 2));
        r.retainVerifiedPosition(new InternalPositionReceipt.Position(
                center.getX(), center.getY(), center.getZ(),
                player.level().dimension().location().toString()));
    }

    // 只走已有地面，找到实际可见且不依赖待拆支撑的站位；导航到达并不替代真实射线验收。
    private TaskState scaffoldNavTick() {
        if (nav == null && (!player.level().isLoaded(scaffold) || player.level().getBlockState(scaffold).isAir())) {
            stopNav(); phase = Phase.SCAFFOLD_BREAK; return TaskState.RUNNING;
        }
        if (nav == null) {
            if (scaffoldCleanup.ready()) { phase = Phase.SCAFFOLD_BREAK; return TaskState.RUNNING; }
            var cleanupGoal = scaffoldCleanup.goal();
            if (cleanupGoal == null) {
                if (!scaffoldCleanup.exhausted()) return TaskState.RUNNING;
                // 这一根暂时无路不代表其他支撑都无路；本轮只推迟一次，不清所有权、不立即重试。
                lastDeferredScaffold = scaffoldCleanup.evidence(); deferredScaffolds.add(scaffold);
                note = "cleanup deferred until another support is removed"; scaffoldAt++;
                phase = Phase.SCAFFOLD_SELECT; return TaskState.RUNNING;
            }
            scaffoldAccessGoals.put(scaffold.immutable(), cleanupGoal);
            nav = PlayerNav.toGoal(player, () -> cleanupGoal, BuildStanceNavigation.PRECISE_WALK,
                    scaffoldCleanup::ready, stanceNavigation.walkingContext(Integer.MIN_VALUE)).walkingOnly();
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                stopNav();
                if (scaffoldCleanup.ready()) phase = Phase.SCAFFOLD_BREAK;
                else scaffoldCleanup.rejectCurrent();
                yield TaskState.RUNNING;
            }
            case FAILED -> {
                scaffoldCleanup.routeFailed(nav.failReason());
                note = "cleanup stance unreachable: " + nav.failReason(); stopNav();
                yield TaskState.RUNNING;
            }
        };
    }

    private TaskState scaffoldBreakTick() {
        // 只拆自己曾确认放下、现在仍是原状态的临时支撑；被别人换过或新加保护的就停，不误拆新东西。
        if (!player.level().isLoaded(scaffold)) {
            failAt(scaffold, "temporary scaffold unloaded before cleanup", FailureType.TARGET_LOST,
                    "scaffold_unloaded", false); return TaskState.FAILED;
        }
        BlockState live = player.level().getBlockState(scaffold);
        if (live.isAir()) {
            // 自己刚挖的支撑可能先在画面上消失，必须等原生回执再记拆除进展，不能被“已是空气”捷径吞掉。
            if (scaffold.equals(digger.current())) {
                return switch (digger.settleGone(true)) {
                    case PROGRESSING -> TaskState.RUNNING;
                    case BROKE_TARGET -> { confirmedScaffoldBreak(); yield TaskState.RUNNING; }
                    default -> {
                        failAt(scaffold, "temporary support disappeared without the pending native break being confirmed",
                                FailureType.TARGET_LOST, "scaffold_cleanup_confirmation_missing", true);
                        yield TaskState.FAILED;
                    }
                };
            }
            r.scaffoldLedger().cleared(scaffold);
            scaffoldAt++; phase = Phase.SCAFFOLD_SELECT; return TaskState.RUNNING;
        }
        if (!scaffoldMutationAllowed(scaffold, live)) {
            failAt(scaffold, "temporary scaffold changed or gained protection before cleanup",
                    FailureType.TARGET_LOST, "scaffold_cleanup_guard_changed", true);
            return TaskState.FAILED;
        }
        // 走到站位后也要保留自己脚下的整柱；未发出的破坏交给逐格下撤，已在途回执仍先结算。
        if (onOwnedColumn(scaffold) && !digger.hasPendingBreak()) {
            digger.cancel(); phase = Phase.SCAFFOLD_SELECT; return TaskState.RUNNING;
        }
        if (!scaffoldCleanup.ready()) {
            digger.cancel(); scaffoldCleanup.rejectCurrent(); phase = Phase.SCAFFOLD_NAV;
            return TaskState.RUNNING;
        }
        return switch (digger.digTargetStep(scaffold)) {
            case PROGRESSING -> TaskState.RUNNING;
            case BROKE_TARGET -> {
                confirmedScaffoldBreak(); yield TaskState.RUNNING;
            }
            case BROKE_OCCLUDER -> {
                failAt(scaffold, "scaffold cleanup changed another block", FailureType.INTERNAL,
                        "scaffold_cleanup_wrong_block", true); yield TaskState.FAILED;
            }
            case NO_SHOT -> {
                digger.cancel(); scaffoldCleanup.rejectCurrent(); phase = Phase.SCAFFOLD_NAV;
                yield TaskState.RUNNING;
            }
        };
    }

    private boolean scaffoldMutationAllowed(BlockPos at, BlockState live) {
        return !inheritedProtectedMutationCells.contains(at.asLong()) && !forbiddenBodyCells.contains(at.asLong())
                && !NavigationSafetyContext.protectsMutation(at) && !NavigationSafetyContext.forbidsBody(at)
                && r.scaffoldLedger().owns(at, live) && r.mutationGuardMatches(player, at);
    }

    private void confirmedScaffoldBreak() {
        // 仅由原生 BROKE_TARGET 回执进入；看到空气或尝试过一次导航都不能算作开启下一轮的拆除进展。
        recordScaffoldBreak(scaffold); scaffoldAt++;
        phase = Phase.SCAFFOLD_SELECT;
    }

    private void recordScaffoldBreak(BlockPos at) {
        // 拆柱下降也共用同一份真实破坏账；身体尚在下落时不提前把控制交给下一条导航。
        r.scaffoldLedger().cleared(at); r.brokeOne(); renewBuildProgress();
        scaffoldPassRemovals++; scaffoldConfirmedRemovals++; rejectedScaffoldDescents.clear();
    }

    private boolean onOwnedColumn(BlockPos at) {
        // 在自己的实心临时柱上时保留整条下撤路径；一般侧站回收只处理其他柱与横向支撑。
        if (!player.onGround()) return false;
        BlockPos below = player.blockPosition().below();
        for (int n = 0; n < 32 && r.scaffoldLedger().contains(below); n++, below = below.below()) if (below.equals(at)) return true;
        return false;
    }

    private TaskState scaffoldDescentTick() {
        var result = scaffoldDescent.tick();
        if (result == BuildScaffoldDescentDrive.Status.RUNNING) return TaskState.RUNNING;
        if (result == BuildScaffoldDescentDrive.Status.FAILED) {
            failAt(scaffold, scaffoldDescent.reason(), FailureType.NO_PATH, "scaffold_descent_failed", true);
            return TaskState.FAILED;
        }
        if (result == BuildScaffoldDescentDrive.Status.UNAVAILABLE) rejectedScaffoldDescents.add(scaffold);
        else {
            // 已确认下降后重新查看全部剩余支撑，先回收新高度伸手可及的方块，再考虑下一格下降。
            scaffoldQueue = r.scaffoldLedger().snapshot().keySet().stream()
                    .sorted(Comparator.comparingInt((BlockPos at) -> at.getY()).reversed()).toList();
            scaffoldAt = 0; deferredScaffolds.clear(); scaffoldCleanupPasses++;
        }
        scaffoldDescent.stop(); scaffoldDescent = null; phase = Phase.SCAFFOLD_SELECT;
        return TaskState.RUNNING;
    }

    private boolean beginScaffoldAccess() {
        // 现有通路整轮都无效后才补施工接近；每个目标只尝试一次，整项最多四次、额外三十二块支撑。
        if (scaffoldAccessAttempts >= 4 || cleanupNewSupports >= 32) return false;
        for (BlockPos at : deferredScaffolds) {
            if (scaffoldAccessAttempts >= 4) return false;
            NavGoal goal = scaffoldAccessGoals.get(at);
            if (goal == null || attemptedScaffoldAccess.contains(at) || !player.level().isLoaded(at)
                    || !scaffoldMutationAllowed(at, player.level().getBlockState(at))) continue;
            attemptedScaffoldAccess.add(at); scaffoldAccessAttempts++; scaffold = at;
            scaffoldCleanup = new BuildScaffoldCleanup(player, at, forbiddenBodyCells);
            try {
                scaffoldAccess = new BuildScaffoldCleanupAccess(player, goal, scaffoldCleanup::ready, this, siteMin, siteMax);
            } catch (IllegalArgumentException unsupported) {
                lastScaffoldAccess = Map.of("failure", "cleanup_access_scope_unsupported"); continue;
            }
            stopNav(); unregisterProvider();
            BuildPlacementRegistry.register(player, scaffoldAccess.provider());
            phase = Phase.SCAFFOLD_ACCESS; return true;
        }
        return false;
    }

    private TaskState scaffoldAccessTick() {
        var result = scaffoldAccess.tick(); r.extendDeadlineTo(scaffoldAccess.deadline() + 100);
        if (result == BuildScaffoldCleanupAccess.Status.RUNNING) return TaskState.RUNNING;
        lastScaffoldAccess = scaffoldAccess.evidence();
        BuildPlacementRegistry.unregister(player, scaffoldAccess.provider()); scaffoldAccess = null; drainScaffolds();
        // 接近中真实搭过的支撑也进入同一回收账；随后只做就地拆除或已证明的逐格下撤，不把搭路当成回收进展。
        scaffoldQueue = r.scaffoldLedger().snapshot().keySet().stream()
                .sorted(Comparator.comparingInt((BlockPos at) -> at.getY()).reversed()).toList();
        scaffoldAt = 0; deferredScaffolds.clear(); scaffoldPassRemovals = 0; scaffoldCleanupPasses++;
        phase = Phase.SCAFFOLD_SELECT; return TaskState.RUNNING;
    }

    private NativeConfirmation confirmation(CellPlan plan, Map<Long, BlockState> before, BlockState predicted) {
        // 上下两半沿用本次原生预测，同时保留每格作者明确指定的摆放属性；只确认这次点击，不拆掉已正确放好的门。
        lastUseConfirmation = new BuildPlacementConfirmation(plan.target(), plan.generated(), before, predicted, targets).trackMaterial(player);
        return lastUseConfirmation;
    }

    private Map<Long, BlockState> freeze(CellPlan plan) {
        Map<Long, BlockState> out = new LinkedHashMap<>();
        out.put(plan.target().pos().asLong(), player.level().getBlockState(plan.target().pos()));
        for (BuildPlacementGeometry.GeneratedCell effect : plan.generated())
            out.put(effect.pos().asLong(), player.level().getBlockState(effect.pos()));
        return Map.copyOf(out);
    }

    // 已有同种方块可以先复用；这一轮已经点过的数量型放置，则必须先补完当前数量再离开。
    private boolean currentPlacementComplete() {
        return constructionMatches(cell.target(), cell.generated()) && (useCount == 0
                || BuildPlacementGeometry.placementComplete(cell.target(), player.level().getBlockState(cell.target().pos())));
    }

    private boolean constructionMatches(BuildTaskRecord.Target target,
                             List<BuildPlacementGeometry.GeneratedCell> generated) {
        if (temporaryTargets.get(target.pos().asLong()) == target && player.level().isLoaded(target.pos())) {
            BlockState live = player.level().getBlockState(target.pos());
            if (r.scaffoldLedger().owns(target.pos(), live) && !live.canBeReplaced()
                    && !live.getShape(player.level(), target.pos()).isEmpty()) return true;
        }
        // 不只看主格，自动生成的另一半也必须已加载且正确；缺观察不能当成正确。
        if (!player.level().isLoaded(target.pos())
                || !target.constructionMatches(player.level().getBlockState(target.pos()))) return false;
        for (BuildPlacementGeometry.GeneratedCell effect : generated)
            if (!player.level().isLoaded(effect.pos())
                    || !generatedConstructionMatches(target, effect, player.level().getBlockState(effect.pos()))) return false;
        return true;
    }

    private boolean generatedConstructionMatches(BuildTaskRecord.Target primary,
            BuildPlacementGeometry.GeneratedCell effect, BlockState live) {
        var declared = targets.get(effect.pos().asLong());
        return declared != null ? declared.constructionMatches(live) : primary.finalProperties() != null
                ? live.is(effect.expected().getBlock()) : BuildValidity.valid(live, effect.expected(), false);
    }

    private boolean generatedFinalMatches(CellPlan plan) {
        for (var effect : plan.generated()) {
            if (!player.level().isLoaded(effect.pos())) return false;
            var target = targets.get(effect.pos().asLong());
            var primary = plan.target();
            if (target == null) target = new BuildTaskRecord.Target(effect.expected(), primary.item(), effect.pos(),
                    primary.label(), null, null, null, primary.itemPlace(), primary.exactProperties(),
                    primary.strictIdentity(), primary.finalProperties());
            if (!target.matches(player.level().getBlockState(effect.pos()))) return false;
        }
        return true;
    }

    private void markComplete(CellPlan plan) {
        if (isTemporary(plan)) return;
        // 只把此刻确实匹配的已声明目标计入完成数；后来外界改坏时，复查会把它从完成数里移出。
        int before = r.completed();
        if (player.level().isLoaded(plan.target().pos())
                && plan.target().matches(player.level().getBlockState(plan.target().pos()))) {
            completed.add(plan.target().pos().asLong());
            placementAttempts.complete(plan.target());
        }
        for (BuildPlacementGeometry.GeneratedCell effect : plan.generated()) {
            BuildTaskRecord.Target declared = targets.get(effect.pos().asLong());
            if (declared != null && player.level().isLoaded(effect.pos())
                    && declared.matches(player.level().getBlockState(effect.pos())))
                completed.add(effect.pos().asLong());
        }
        r.completed(completed.size());
        if (r.completed() > before) renewBuildProgress();
    }

    private void renewBuildProgress() {
        exhaustedPlacementStates.clear();
        r.extendDeadlineTo(player.level().getGameTime() + BUILD_PROGRESS_LEASE_TICKS);
    }

    private int countMatching() {
        int n = 0;
        for (BuildTaskRecord.Target target : r.targets)
            if (player.level().isLoaded(target.pos())
                    && target.matches(player.level().getBlockState(target.pos()))) n++;
        return n;
    }

    private boolean placesAt(BlockHitResult hit, BlockPos target) {
        return hit.getBlockPos().equals(target)
                || hit.getBlockPos().relative(hit.getDirection()).equals(target);
    }
    private boolean inReach(BlockPos pos) {
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= 20.25;
    }

    private void bounds() {
        for (BuildTaskRecord.Target target : r.targets) {
            BlockPos p = target.pos();
            siteMin = siteMin == null ? p : new BlockPos(Math.min(siteMin.getX(), p.getX()),
                    Math.min(siteMin.getY(), p.getY()), Math.min(siteMin.getZ(), p.getZ()));
            siteMax = siteMax == null ? p : new BlockPos(Math.max(siteMax.getX(), p.getX()),
                    Math.max(siteMax.getY(), p.getY()), Math.max(siteMax.getZ(), p.getZ()));
        }
    }
    private void addUnsupported(String code, BlockPos pos, String detail, List<String> decisions) {
        if (unsupported.size() >= 64) return;
        recordTargetDiagnostic(code, pos);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code); if (pos != null) data.put("pos", pos.toShortString());
        data.put("detail", detail); data.put("decision_options", decisions); unsupported.add(data);
    }
    private void addBlocked(String code, BlockPos pos, String detail) {
        recordTargetDiagnostic(code, pos);
        if (blocked.size() < 64) blocked.add(Map.of(
                "code", code, "pos", pos.toShortString(), "detail", detail));
    }
    private void recordTargetDiagnostic(String code, BlockPos pos) {
        if (targetDiagnostics.size() < 16) targetDiagnostics.add(BuildFailureEvidence.describe(
                code, pos, r.targets, player.level()::isLoaded, player.level()::getBlockState));
    }
    private void failPreflight(String message, FailureType type, String code) {
        failureCode = code; fail(message + "; no mutation was submitted", type);
    }
    private void failAt(BlockPos pos, String message, FailureType type, String code, boolean unknown) {
        failurePos = pos == null ? null : pos.immutable(); failureCode = code; uncertain = unknown; fail(message, type);
    }

    private void drainScaffolds() {
        // 导航过程中可能为到达工作位置搭过支撑，把它们收进施工清理名单，避免任务结束后留一堆脚手架。
        boolean added = false;
        for (BlockPos pos : BuildPlacementRegistry.drainScaffold(player))
            if (!targets.containsKey(pos.asLong())) added |= scaffolds.add(pos.immutable());
        if (added) renewBuildProgress();
    }

    private boolean ownedAirScaffold(BuildTaskRecord.Target target, BlockState live) {
        return BuildCellRules.isAirTarget(target) && r.scaffoldLedger().owns(target.pos(), live);
    }

    @Override public void confirmedScaffold(BlockPos placeAt, BlockState state) {
        // 只有嵌入的原生回执，而不是一次尝试点击，才授予后续清理所有权。
        BuildTaskRecord.Target target = targets.get(placeAt.asLong());
        if (target != null && !BuildCellRules.isAirTarget(target)) return;
        if (!state.isAir() && state.getFluidState().isEmpty()) {
            if (scaffoldAccess != null && !r.scaffoldLedger().owns(placeAt, state)) cleanupNewSupports++;
            if (!r.scaffoldLedger().owns(placeAt, state)) placementEnvironmentChanged(placeAt);
            r.scaffoldLedger().confirmed(placeAt, state); scaffolds.add(placeAt.immutable());
            renewBuildProgress();
        }
    }

    @Override public void confirmedScaffoldRemoval(BlockPos pos) {
        if (worksite != null) worksiteMovement.changed(pos);
        if (r.scaffoldLedger().contains(pos)) placementEnvironmentChanged(pos);
        r.scaffoldLedger().cleared(pos);
    }

    @Override public boolean permitsScaffoldSupport(BlockPos clicked, BlockPos placeAt, BlockState support) {
        // 可以借已建好的成品作支点，但不能借被明确保护的位置；落下临时支撑的位置也要单独检查。
        BuildTaskRecord.Target target = targets.get(clicked.asLong());
        return preflightDone && target != null && !BuildCellRules.isAirTarget(target) && target.matches(support)
                && !inheritedProtectedMutationCells.contains(clicked.asLong())
                && !NavigationSafetyContext.protectsMutation(clicked) && !NavigationSafetyContext.forbidsBody(clicked)
                && player.level().isLoaded(placeAt) && scaffoldPermitted(placeAt, null);
    }

    @Override public boolean permitsTemporaryScaffold(BlockPos placeAt) { return scaffoldPermitted(placeAt, null); }

    private boolean scaffoldPermitted(BlockPos pos, LongSet additionalProtection) {
        // 回收接近的垫脚材料有整项上限，不能为了收旧支撑不断造出新的高柱。
        if (scaffoldAccess != null && cleanupNewSupports >= 32) return false;
        if (!player.level().isLoaded(pos)) return false;
        boolean hard = inheritedProtectedMutationCells.contains(pos.asLong())
                || forbiddenBodyCells.contains(pos.asLong())
                || additionalProtection != null && additionalProtection.contains(pos.asLong())
                || NavigationSafetyContext.protectsMutation(pos) || NavigationSafetyContext.forbidsBody(pos);
        // 施工和寻路共用当前落点的许可；附近有机器或容器仍允许搭垫块，实际操作结果由放置和清理流程处理。
        return r.mutationGuardMatches(player, pos)
                && r.scaffoldLedger().permits(targets.get(pos.asLong()), player.level().getBlockState(pos), hard);
    }
    private void registerProvider() {
        if (!providerRegistered) { BuildPlacementRegistry.register(player, this); providerRegistered = true; }
    }
    private void unregisterProvider() {
        if (providerRegistered) {
            drainScaffolds(); BuildPlacementRegistry.unregister(player, this); providerRegistered = false;
        }
    }

    @Override public BlockState desiredState(BlockPos pos) {
        BuildTaskRecord.Target target = targets.get(pos.asLong());
        if (target == null || BuildCellRules.isAirTarget(target) || !player.level().isLoaded(pos)
                || target.constructionMatches(player.level().getBlockState(pos))) return null;
        return target.desiredState();
    }
    @Override public boolean acceptsPlacement(BlockPos pos, BlockState state) {
        BuildTaskRecord.Target target = targets.get(pos.asLong());
        return target != null && target.acceptsPlacedState(state);
    }
    @Override public Map<Item, Integer> scaffoldReservations() {
        if (player.getAbilities().instabuild && !r.consumeMaterials) return Map.of();
        // SemanticBuildSupply 会将完整冻结的 source.targets 传给每个随身材料批次。
        return BuildTemporarySupportMaterials.remaining(r.targets,
                target -> constructionMatches(target, BuildPlacementGeometry.generatedBy(target)));
    }
    @Override public TerrainPermit permit() { return TerrainPermit.TERRAFORM; }
    @Override public LongSet embeddedProtectedMutationCells() {
        return union(NavigationSafetyContext.protectedMutationCells());
    }
    @Override public LongSet embeddedForbiddenBodyCells() {
        return unionForbidden(NavigationSafetyContext.forbiddenBodyCells());
    }
    private LongSet union(LongSet other) {
        LongOpenHashSet hard = new LongOpenHashSet(inheritedProtectedMutationCells);
        hard.addAll(forbiddenBodyCells);
        hard.addAll(NavigationSafetyContext.protectedMutationCells());
        hard.addAll(NavigationSafetyContext.forbiddenBodyCells());
        return r.scaffoldLedger().navigationProtection(protectedCells, hard, other, scaffoldAirCells,
                targets, player.level(), player.level()::isLoaded, preflightDone);
    }
    private LongSet unionForbidden(LongSet other) {
        if (other == null || other.isEmpty()) return forbiddenBodyCells;
        LongOpenHashSet out = new LongOpenHashSet(forbiddenBodyCells);
        out.addAll(other);
        return out;
    }

    @Override public Map<String, Object> progress() {
        Map<String, Object> data = new LinkedHashMap<>(Map.of("task", name(), "phase", phase.name().toLowerCase(Locale.ROOT),
                "planned_cells", r.targets.size(), "verified_cells", r.completed(),
                "requested_blocks", r.targets.size(), "verified_blocks", r.completed(),
                "placed_blocks", r.placed(), "cleared_blocks", r.broken(),
                "temporary_supports_remaining", r.scaffoldLedger().snapshot().size()));
        if (phase == Phase.AIM) data.put("placement", placementDiagnostics());
        if (placementAccess != null) data.put("placement_access", placementAccess.evidence());
        if (layerKnown && constructionLayer != Integer.MAX_VALUE) data.put("construction_layer", constructionLayer);
        if (regions != null) data.put("construction_region", Map.of("id", constructionRegion, "count", regions.count()));
        data.put("construction_access", stanceNavigation.stage());
        data.put("construction_navigation", navigationDiagnostics());
        data.put("excavation_remaining", excavation.remaining());
        data.put("food_preparation", foodPreparation.progress(player));
        if (excavationTools.active()) data.put("excavation_tool_supply", excavationTools.progress());
        if (!ultimineEvidence.isEmpty()) data.put("ultimine", ultimineEvidence);
        if (excavating && clearing != null) data.put("excavation_target", clearing.toShortString());
        data.put("creative_materials", creativeMaterials.progress());
        if (scaffoldCleanup != null) data.put("scaffold_cleanup", scaffoldCleanup.evidence());
        if (scaffoldDescent != null) data.put("scaffold_descent", scaffoldDescent.evidence());
        if (scaffoldAccess != null) data.put("scaffold_access", scaffoldAccess.evidence());
        if (gestureProgress != null) data.put("placement_search", Map.of("complete", gestureProgress.complete(),
                "stance_checks", gestureProgress.stanceChecks(), "face_checks", gestureProgress.probeCount(),
                "candidates", gestureProgress.gestureCount()));
        if (worksiteProgress != null || worksite != null) data.put("worksite", worksiteProgress());
        if (!lastPlacementRejection.isEmpty()) data.put("last_placement_rejection", lastPlacementRejection);
        return Map.copyOf(data);
    }

    private Map<String, Object> worksiteProgress() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("approach_attempts", worksiteAttempts); data.put("stagnant_ticks", worksiteMovement.stagnantTicks());
        if (worksiteProgress != null) {
            data.put("search_complete", worksiteProgress.complete());
            data.put("candidate_checks", worksiteProgress.candidateChecks());
            data.put("placement_checks", worksiteProgress.placementChecks());
            data.put("best_coverage", worksiteProgress.best() == null ? 0 : worksiteProgress.best().coverage());
        }
        if (worksite != null) {
            data.put("requires_construction_access", worksite.constructionAccess());
            data.put("distance_to_worksite", Math.sqrt(player.position().distanceToSqr(worksite.feet())));
            data.put("remaining_targets", worksite.placements().stream().filter(p -> player.level().isLoaded(p.target().pos())
                    && !p.target().matches(player.level().getBlockState(p.target().pos()))).count());
        }
        return Map.copyOf(data);
    }

    private Map<String, Object> placementDiagnostics() {
        if (cell == null || gesture == null) return Map.of();
        return Map.of("item", BuiltInRegistries.ITEM.getKey(cell.target().item()).toString(),
                "waiting_for", aimWaitReason, "crosshair", aimHit,
                "angular_error_degrees", Math.round(aimError * 1000) / 1000.0,
                "requested_sneak", gesture.sneak(), "observed_sneak", player.isShiftKeyDown(),
                "rejected_gestures", placementAttempts.rejectedCount(cell.target()),
                // 让现场回执能区分尝试新位置与原地换几个像素，不用看角色反复跳跃猜是否真的换站位。
                "stance_retry", Map.of("failures", placementAttempts.stanceFailureCount(cell.target(), gesture.stance()),
                        "limit", placementAttempts.maxFailuresPerStance(), "rejected_stances", placementAttempts.rejectedStanceCount(cell.target())));
    }

    private Map<String, Object> navigationDiagnostics() {
        var data = new LinkedHashMap<>(stanceNavigation.evidence());
        data.put("worksite_movement",worksiteMovement.evidence());
        if (cell != null) for (int i = 0; i < r.targets.size(); i++)
            if (r.targets.get(i).pos().equals(cell.target().pos())) { data.put("target_index", i); break; }
        return Map.copyOf(data);
    }

    @Override public void stop(LocalPlayer companion, StopReason why) {
        if (scaffoldAccess != null) { scaffoldAccess.stop(); BuildPlacementRegistry.unregister(player, scaffoldAccess.provider()); }
        if (scaffoldDescent != null) scaffoldDescent.stop();
        if (placementAccess != null) {
            if (why == StopReason.PREEMPTED) {
                placementAccess.pause();
                if (useReceipt == null && phase != Phase.EDGE_RETURN) phase = Phase.PLACE_NAV;
            } else placementAccess.stop();
        }
        foodPreparation.stop(player);
        spoilSupply.cancel(player);
        if (ultimine != null) { ultimine.close(); ultimine = null; ultimineArmed = false; }
        excavationTools.stop(player);
        // 暂停时先停当前挖掘、撤掉导航的施工协助并松键；任务进度仍保留，恢复后可以重新登记协助。
        if (digger.current() != null) digger.cancel();
        if (doorRepair != null) doorRepair.pause();
        drainScaffolds(); unregisterProvider(); super.stop(companion, why); InputDriver.halt(player);
        // 暂停／取消后不再续发旧任务的 Shift；恢复时由新控制租约重新核验身体与锚点。
    }
    @Override protected void cleanup() {
        if (scaffoldAccess != null) { scaffoldAccess.stop(); BuildPlacementRegistry.unregister(player, scaffoldAccess.provider()); }
        if (scaffoldDescent != null) scaffoldDescent.stop();
        if (placementAccess != null) placementAccess.stop();
        foodPreparation.stop(player);
        spoilSupply.cancel(player);
        if (ultimine != null) { ultimine.close(); ultimine = null; ultimineArmed = false; }
        excavationTools.stop(player);
        // 结束时释放预览、挖掘和菜单；中断保留创造材料与已建方块，正常完成先经过材料清理阶段。
        BuildPreviewGate.release(r);
        if (doorRepair != null) { doorRepair.stop(); doorRepair = null; }
        if (digger.current() != null) digger.cancel();
        drainScaffolds(); unregisterProvider(); InputDriver.halt(player); selection.reset();
        aimConvergence.reset();
        creativeMaterials.stop(player); super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        // 报告已匹配多少、放过和清过多少、还缺材料以及未清理支撑；只有最终格子和通行均通过才附整体验证。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requested", r.targets.size());
        data.put("completed", r.completed());
        data.put("placed", r.placed());
        // 永久建材缺口和临时支撑缺口分别保留，后者必须带可执行的选料结论才能自动补给。
        if (!temporarySupportDemand.isEmpty()) data.put("temporary_support_demand", temporarySupportDemand);
        data.put("cleared", r.broken());
        data.put("food_preparation", foodPreparation.progress(player));
        if (!foodPreparation.receipts().isEmpty()) data.put("completed_food_receipts", foodPreparation.receipts());
        if ("build_terrain_conflict".equals(failureCode)) data.put("mechanical_retry_allowed", false);
        if (BuildClearanceSurvey.FAILURE.equals(failureCode)) {
            // 顶层回报携带坐标和选址偏移，供料父任务及 Attention 都能直接转给 LLM；禁止原地机械重试。
            var report = new LinkedHashMap<>(clearanceSurvey.report());
            if (!clearanceTrigger.isEmpty()) report.put("trigger", clearanceTrigger);
            data.put("clearance_report", report); data.put("mechanical_retry_allowed", false);
        }
        if (!excavationTools.receipts().isEmpty()) data.put("excavation_tool_receipts", excavationTools.receipts());
        if (!spoilReceipts.isEmpty()) data.put("excavation_spoil_receipts", List.copyOf(spoilReceipts));
        data.put("confirmed_ultimine_batches", ultimineBatches);
        data.put("ultimine_held_break_ticks", ultimineHeldTicks);
        if (!lastUltimineHold.isEmpty()) data.put("last_confirmed_ultimine_hold", lastUltimineHold);
        if (!ultimineEvidence.isEmpty()) data.put("ultimine", ultimineEvidence);
        data.put("stopped_phase", phase.name().toLowerCase(Locale.ROOT));
        if (regions != null) data.put("construction_region", Map.of("id", constructionRegion, "count", regions.count()));
        data.put("construction_access", stanceNavigation.stage());
        data.put("construction_navigation", navigationDiagnostics());
        // 失败回执也保留对齐或贴边时的实际位置证据，供后续修复判断真实半格差异。
        if (placementAccess != null) data.put("placement_access", placementAccess.evidence());
        data.put("temporary_supports_remaining", r.scaffoldLedger().snapshot().size());
        if (scaffoldCleanup != null) data.put("scaffold_cleanup", scaffoldCleanup.evidence());
        data.put("scaffold_cleanup_passes", scaffoldCleanupPasses);
        data.put("scaffold_access_attempts", scaffoldAccessAttempts);
        data.put("cleanup_new_supports", cleanupNewSupports);
        if (scaffoldAccess != null) data.put("scaffold_access", scaffoldAccess.evidence());
        else if (!lastScaffoldAccess.isEmpty()) data.put("scaffold_access", lastScaffoldAccess);
        if (scaffoldDescent != null) data.put("scaffold_descent", scaffoldDescent.evidence());
        data.put("scaffold_cleanup_confirmed_removals", scaffoldConfirmedRemovals);
        data.put("scaffold_cleanup_deferred_count", deferredScaffolds.size());
        if (!lastDeferredScaffold.isEmpty()) data.put("last_deferred_scaffold", lastDeferredScaffold);
        var diagnostics = new ArrayList<>(targetDiagnostics);
        if (failurePos != null) {
            var failure = new LinkedHashMap<>(BuildFailureEvidence.describe(failureCode, failurePos,
                    r.targets, player.level()::isLoaded, player.level()::getBlockState));
            if (temporaryTargets.containsKey(failurePos.asLong())) {
                failure.put("temporary_support", true);
                for (int i = queueAt + 1; i < queue.size(); i++) if (!isTemporary(queue.get(i))) {
                    failure.put("support_for", BuildFailureEvidence.describe("support_for", queue.get(i).target().pos(),
                            r.targets, player.level()::isLoaded, player.level()::getBlockState));
                    break;
                }
            }
            diagnostics.addFirst(failure);
        }
        if (!diagnostics.isEmpty()) data.put("build_diagnostics", List.copyOf(diagnostics.subList(0, Math.min(16, diagnostics.size()))));
        if (phase == Phase.AIM && !lastAimObservation.isEmpty()) data.put("placement", lastAimObservation);
        if (!lastPlacementRejection.isEmpty()) data.put("last_placement_rejection", lastPlacementRejection);
        if (lastUseConfirmation != null && failureCode != null) data.put("placement_confirmation",
                lastUseConfirmation.diagnostics(player.level()::isLoaded, player.level()::getBlockState));
        data.put("site_min", siteMin == null ? "-" : siteMin.toShortString());
        data.put("site_max", siteMax == null ? "-" : siteMax.toShortString());

        if (!required.isEmpty()) data.put("required_materials", itemCounts(required));
        Map<Item, Integer> outstanding = preflightDone ? currentShortfall() : missing;
        if (!outstanding.isEmpty()) data.put(r.consumeMaterials || !player.getAbilities().instabuild
                ? "missing_materials" : "unstocked_creative_materials", itemCounts(outstanding));
        if (!unsupported.isEmpty()) data.put("unsupported_cells", List.copyOf(unsupported));
        if (!blocked.isEmpty()) data.put("blocked_cells", List.copyOf(blocked));
        if (failureCode != null) data.put("failure_code", failureCode);
        if (failurePos != null) data.put("failure_position", position(failurePos));
        if (uncertain) {
            // 这里使用施工自己的不确定字段；上层 RecoveryAdvisor 当前读取的是另外两种字段名。
            data.put("world_change_uncertain", true);
            data.put("safe_to_retry_without_observation", false);
        }

        List<Map<String, Object>> remainingScaffolds = scaffolds.stream()
                .filter(pos -> !player.level().isLoaded(pos)
                        || !player.level().getBlockState(pos).isAir())
                .map(this::position).toList();
        if (!remainingScaffolds.isEmpty()) data.put("remaining_scaffolds", remainingScaffolds);

        if (!creativeMaterials.retained().isEmpty()) data.put("retained_creative_materials", creativeMaterials.retained());

        if (traversabilityResult != null) {
            data.put("traversability_verification", traversabilityResult.evidence());
        }

        if (r.supplyAccessOnly()) {
            // 出坑只满足补料的前置条件；即使现场恰好已经全对，也不由这张内部回执验收整栋建筑。
            data.put("supply_access_only", true);
            data.put("supply_access_ready", supplyAccessReady && failureCode == null);
            data.put("goal_satisfied", false);
            if (excavationExit != null) data.put("supply_exit", position(excavationExit));
            return data;
        }

        // 再次读取现场，全部目标相符时给出场地中心；它表示已核对的建筑位置，不表示角色站在中心。
        if (!r.targets.isEmpty() && countMatching() == r.targets.size()
                && siteMin != null && siteMax != null) {
            BlockPos center = new BlockPos(
                    Math.floorDiv(siteMin.getX() + siteMax.getX(), 2),
                    siteMin.getY(),
                    Math.floorDiv(siteMin.getZ() + siteMax.getZ(), 2));
            Map<String, Object> verified = position(center);
            verified.put("kind", "verified_site_center");
            data.put("verified_position", verified);
            if (!r.semanticFacts().isEmpty()
                    && (r.traversabilityContract() == null
                            || traversabilityResult != null && traversabilityResult.valid())) {
                data.put("aggregate_verification", Map.of(
                        "status", "verified",
                        "basis", r.traversabilityContract() == null
                                ? "every contract-bearing target cell was re-read after construction"
                                : "every target cell plus all planner-required traversal endpoints were re-read",
                        "facts", r.semanticFacts()));
            }
        }
        return data;
    }

    private Map<String, Integer> itemCounts(Map<Item, Integer> counts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        counts.forEach((item, count) -> out.put(
                BuiltInRegistries.ITEM.getKey(item).toString(), count));
        return out;
    }

    // 对仍未完成的原始施工目标重新汇总并扣背包库存；临时支撑和部分完成的数量没有在这里单独折算。
    private Map<Item, Integer> currentShortfall() {
        // 根据当前仍未完成的格子重新算材料，再减去实际背包数量，不直接沿用开工前的旧缺料表。
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (CellPlan plan : plans) {
            BuildTaskRecord.Target target = plan.target();
            if (BuildCellRules.isAirTarget(target) || constructionMatches(target, plan.generated())) continue;
            if (target.costsMaterial())
                need.merge(target.item(), Math.max(1, target.materialCount()), Integer::sum);
        }
        Map<Item, Integer> out = new LinkedHashMap<>();
        need.forEach((item, count) -> {
            int have = inventory.mainInventoryCount(item);
            if (have < count) out.put(item, count - have);
        });
        return out;
    }

    private Map<String, Object> position(BlockPos pos) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", pos.getX()); out.put("y", pos.getY()); out.put("z", pos.getZ());
        out.put("dimension", player.level().dimension().location().toString());
        return out;
    }

    @Override
    protected String successMessage() {
        if (r.supplyAccessOnly()) return "reached the exterior ground for material supply; temporary supports remain tracked for construction";
        return "built and re-verified " + r.completed() + "/" + r.targets.size()
                + " block(s) through first-person actions; placed " + r.placed()
                + ", cleared " + r.broken() + " (" + note
                + (r.traversabilityContract() == null ? "" : "; required routes re-verified") + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out while building through first-person actions; verified "
                + r.completed() + "/" + r.targets.size() + " (" + note + ")";
    }

    @Override
    protected String cancelledMessage() {
        return "build interrupted after " + r.completed() + "/" + r.targets.size()
                + " block(s) had been verified";
    }
}
