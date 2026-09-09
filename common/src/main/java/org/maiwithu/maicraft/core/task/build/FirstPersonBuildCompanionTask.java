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
import org.maiwithu.maicraft.core.pathing.bridge.ContextFactory;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;

import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
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

/** 实际施工：先逐格检查，再走到合适位置拿材料、瞄准、放置；最后复查成品并拆掉自己搭的临时支撑。 */
class FirstPersonBuildCompanionTask extends AbstractCompanionTask<BuildTaskRecord>
        implements BuildPlacementRegistry.Provider, PlayerNav.ContextProvider {
    /** 真正确认挖掉、放下或完成更多格子时，给施工再留两分钟；单纯原地空转不会因此无限续期。 */
    private static final long BUILD_PROGRESS_LEASE_TICKS = 2L * 60L * 20L;
    private static final int PREFLIGHT_BUDGET = 24;
    private static final int USE_TIMEOUT = 40;

    private enum Phase { PREFLIGHT, SELECT, CLEAR_NAV, CLEAR, PLACE_NAV, WORKSITE, SELECT_ITEM,
        AIM, WAIT_USE, CLEAR_CREATIVE, VERIFY, SCAFFOLD_SELECT, SCAFFOLD_NAV, SCAFFOLD_BREAK, ROUTE_VERIFY }
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
    private final java.util.function.BiFunction<LocalPlayer, Double, HitResult> placementRay;
    private final Map<Long, BuildTaskRecord.Target> targets = new LinkedHashMap<>();
    private final LongOpenHashSet protectedCells = new LongOpenHashSet();
    private final LongOpenHashSet scaffoldAirCells = new LongOpenHashSet();
    private final LongOpenHashSet forbiddenBodyCells = new LongOpenHashSet();
    /** Area cells inherited from earlier semantic steps; unlike blueprint sacred cells, mutable targets here are rejected. */
    private final LongOpenHashSet inheritedProtectedMutationCells = new LongOpenHashSet();
    private final LongOpenHashSet completed = new LongOpenHashSet();
    private final List<BuildTaskRecord.Target> preflightOrder = new ArrayList<>();
    private final List<CellPlan> plans = new ArrayList<>();
    private final Map<Long, BuildTaskRecord.Target> temporaryTargets = new LinkedHashMap<>();
    private final Map<Long, CellPlan> plansByPrimary = new LinkedHashMap<>();
    private final Map<Item, Integer> required = new LinkedHashMap<>();
    private final Map<Item, Integer> missing = new LinkedHashMap<>();
    private final List<Map<String, Object>> unsupported = new ArrayList<>();
    private final List<Map<String, Object>> blocked = new ArrayList<>();
    private final LinkedHashSet<BlockPos> scaffolds = new LinkedHashSet<>();
    private final Map<Long, Set<PlacementAttemptSignature>> exhaustedPlacementStates =
            new HashMap<>();
    private final Set<VerificationFailureSignature> exhaustedVerificationStates = new HashSet<>();

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
    private boolean worksiteSearched;
    private final Set<BlockPos> rejectedWorksites = new HashSet<>();
    private final PlacementAttemptLedger placementAttempts = new PlacementAttemptLedger();
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
    private final LinkedHashSet<Long> verifyFailed = new LinkedHashSet<>();
    private final List<ObservedCell> verifyFailureStates = new ArrayList<>();
    private List<BlockPos> scaffoldQueue = List.of();
    private BlockPos scaffold, siteMin, siteMax, failurePos;
    private String failureCode, note = "all native actions re-verified";

    FirstPersonBuildCompanionTask(LocalPlayer player, BuildTaskRecord record) {
        this(player, record, Interaction::nativeRaytrace);
    }

    FirstPersonBuildCompanionTask(LocalPlayer player, BuildTaskRecord record,
            java.util.function.BiFunction<LocalPlayer, Double, HitResult> placementRay) {
        // 保存全部目标并按施工顺序排序，记住不能让导航破坏的格子，以及上一批留下的临时支撑。
        super(player, record);
        this.placementRay = placementRay;
        rules = new BuildCellRules(player, record);
        inventory = new BuildInventory(player);
        digger = new BlockDigger(player);
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
                // A protected target still has to be entered while it is empty in an earlier
                // construction layer. Live collision prevents entering it after it is built.
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
        if (preview == org.maiwithu.maicraft.client.preview.PreviewSession.Decision.WAITING)
            return TaskState.RUNNING;
        if (preview == org.maiwithu.maicraft.client.preview.PreviewSession.Decision.CANCELLED)
            return TaskState.CANCELLED;
        if (preflightDone) registerProvider();
        drainScaffolds();
        // 一刻内可接着处理不需要等待的阶段；已发点击、正在改创造背包或本刻不能再操作时停下来。
        TaskState result = BuildTickPipeline.advance(() -> phase, this::stepPhase,
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
            case PREFLIGHT -> preflightTick(); case SELECT -> selectTick();
            case CLEAR_NAV -> clearNavTick(); case CLEAR -> clearTick();
            case PLACE_NAV -> placeNavTick(); case SELECT_ITEM -> selectItemTick();
            case WORKSITE -> worksiteTick();
            case AIM -> aimTick(); case WAIT_USE -> waitUseTick();
            case CLEAR_CREATIVE -> clearCreativeTick(); case VERIFY -> verifyTick();
            case SCAFFOLD_SELECT -> scaffoldSelectTick(); case SCAFFOLD_NAV -> scaffoldNavTick();
            case SCAFFOLD_BREAK -> scaffoldBreakTick();
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
        if (!target.matches(live)
                && inheritedProtectedMutationCells.contains(target.pos().asLong())) {
            addBlocked("inherited_semantic_area_protection", target.pos(),
                    "a previously observed protected semantic area occupies this cell");
        }
        if (!target.matches(live)) {
            if (rules.blockedByMode(target) && !ownedAirScaffold(target, live)) addBlocked("replacement_policy", target.pos(),
                    "existing block is protected by replacement policy");
            else if (rules.hopeless(target)) addBlocked("unbreakable_or_outside_world", target.pos(),
                    "cell is outside the world or has an unbreakable obstruction");
        }
        inspectGenerated(target, generated);
        boolean done = matches(target, generated);
        if (!BuildCellRules.isAirTarget(target) && !done) {
            if (!(target.item() instanceof BlockItem)) addUnsupported("no_native_block_item", target.pos(),
                    "requested state has no block item that can be placed by hand",
                    List.of("placeable_substitute", "leave_for_player", "cancel"));
            // A missing gesture in today's world is not an unsupported block state: earlier
            // work, a different order or removable click supports can open the required face.
            // The live native placement prediction still checks the exact authored state.
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
            if (!BuildValidity.valid(live, effect.expected(), false)
                    && inheritedProtectedMutationCells.contains(effect.pos().asLong())) {
                addBlocked("inherited_semantic_area_protection", effect.pos(),
                        "a generated placement would alter a previously observed protected semantic area");
                continue;
            }
            if (live.isAir() || BuildValidity.valid(live, effect.expected(), false)) continue;
            if (!r.replaceMode.allows(live, effect.expected()))
                addBlocked("generated_cell_occupied", effect.pos(), "protected secondary cell is occupied");
            else if (live.hasBlockEntity() && !r.replaceBlockEntities)
                addBlocked("generated_cell_block_entity", effect.pos(), "block entity occupies secondary cell");
            else if (live.getDestroySpeed(player.level(), effect.pos()) < 0.0F)
                addBlocked("generated_cell_unbreakable", effect.pos(), "unbreakable secondary cell");
        }
    }

    private TaskState finishPreflight() {
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
        // 预检按未完成目标的完整材料数核对背包，不扣除现场已有的半层等中间状态。
        if (r.consumeMaterials || !player.getAbilities().instabuild) required.forEach((item, count) -> {
            int have = inventory.mainInventoryCount(item); if (have < count) missing.put(item, count - have);
        });
        if (!missing.isEmpty() && !(r.allowPartial && anyAffordable())) {
            failureCode = "missing_materials";
            fail("construction did not start; missing " + BuildLedger.summarizeShortfall(missing),
                    FailureType.NO_MATERIAL); return TaskState.FAILED;
        }
        if (!missing.isEmpty()) note = "partial mode pauses when carried materials run out";
        preflightDone = true; queue = new ArrayList<>(plans); queueAt = 0;
        registerProvider(); phase = Phase.SELECT; return TaskState.RUNNING;
    }

    // 允许部分施工时，只要还有一格能用现有物品完成，或有一格可清空，就可以先开工。
    private boolean anyAffordable() {
        for (CellPlan plan : plans) {
            if (matches(plan.target(), plan.generated())) continue;
            if (BuildCellRules.isAirTarget(plan.target())
                    || inventory.mainInventoryCount(plan.target().item()) >= plan.target().materialCount()) return true;
        }
        return false;
    }

    private TaskState selectTick() {
        // 找下一格不符合蓝图的位置，先清掉冲突方块，再选择放置手法；整轮做完进入成品复查。
        resetCell();
        retainUsefulWorksite();
        // Finish reachable work from these feet before returning to the blueprint's global order.
        if (queueAt < queue.size() && !isTemporary(queue.get(queueAt))
                && selectNearbyPlacement(worksite == null ? null : worksite.feet()))
            return TaskState.RUNNING;
        while (queueAt < queue.size()) {
            cell = queue.get(queueAt);
            if (matches(cell.target(), cell.generated())) { markComplete(cell); queueAt++; continue; }
            if (isTemporary(cell) && (!player.level().isLoaded(cell.target().pos())
                    || !scaffoldPermitted(cell.target().pos(), null))) {
                failAt(cell.target().pos(), "temporary support cell changed or gained protection",
                        FailureType.TARGET_LOST, "temporary_support_changed", false); return TaskState.FAILED;
            }
            // Retain a previous material batch's confirmed support until every permanent cell
            // is finished. It stays incomplete in accounting and enters final scaffold cleanup.
            if (player.level().isLoaded(cell.target().pos()) && ownedAirScaffold(cell.target(),
                    player.level().getBlockState(cell.target().pos()))) { queueAt++; continue; }
            clearQueue = clearCells(cell); clearAt = 0;
            if (!clearQueue.isEmpty()) { clearing = clearQueue.get(0); phase = Phase.CLEAR_NAV; }
            else if (BuildCellRules.isAirTarget(cell.target())) finishCell();
            else {
                phase = Phase.PLACE_NAV;
            }
            return TaskState.RUNNING;
        }
        beginVerify(); return TaskState.RUNNING;
    }

    private List<BlockPos> clearCells(CellPlan plan) {
        // 当前把主格和自动生成格里“不为空且不匹配”的方块加入待清除列表，这里没有再按替换许可过滤。
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        if (player.level().isLoaded(plan.target().pos())) {
            BlockState live = player.level().getBlockState(plan.target().pos());
            if (!live.isAir() && !plan.target().matches(live)) out.add(plan.target().pos());
        }
        for (BuildPlacementGeometry.GeneratedCell generated : plan.generated()) {
            if (!player.level().isLoaded(generated.pos())) continue;
            BlockState live = player.level().getBlockState(generated.pos());
            if (!live.isAir() && !BuildValidity.valid(live, generated.expected(), false)) out.add(generated.pos());
        }
        return List.copyOf(out);
    }

    private TaskState clearNavTick() {
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
        if (nav == null) {
            BlockPos at = clearing.immutable();
            nav = PlayerNav.toGoal(player, () -> NavGoal.mineStance(at), 1.0, () -> inReach(at), this);
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> { stopNav(); phase = Phase.CLEAR; yield TaskState.RUNNING; }
            case FAILED -> {
                FailureType type = nav.failType(); String reason = nav.failReason(); stopNav();
                failAt(clearing, "no breaking stance: " + reason, type, "clear_stance_failed", false);
                yield TaskState.FAILED;
            }
        };
    }

    private TaskState clearTick() {
        // 对准目标挖掘并等待确认；当前没有在这里重新调用 blockedByMode 检查预检后出现的普通方块。
        if (!player.level().isLoaded(clearing)) {
            failAt(clearing, "break target unloaded", FailureType.TARGET_LOST,
                    "clear_target_lost", false); return TaskState.FAILED;
        }
        if (player.level().getBlockState(clearing).isAir()) {
            if (!r.hasExecutionGuards()) return nextClear();
            // A guarded machine edit accepts disappearance only through its own pending break receipt.
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
        if (!r.mutationGuardMatches(player, clearing)) {
            failAt(clearing, "observed target changed before breaking", FailureType.TARGET_LOST,
                    "build_target_changed", false); return TaskState.FAILED;
        }
        return switch (digger.digTargetStep(clearing)) {
            case PROGRESSING -> TaskState.RUNNING;
            case BROKE_TARGET -> {
                confirmedBlockChange(clearing);
                r.brokeOne(); renewBuildProgress(); yield nextClear();
            }
            case BROKE_OCCLUDER -> {
                failAt(clearing, "target-only breaker changed another block", FailureType.INTERNAL,
                        "unexpected_break_target", true); yield TaskState.FAILED;
            }
            case NO_SHOT -> {
                failAt(clearing, "no verified crosshair ray reaches obstruction", FailureType.OCCLUDED,
                        "clear_occluded", false); yield TaskState.FAILED;
            }
        };
    }

    private TaskState nextClear() {
        // 清完一格继续下一格；冲突全部清完后，要么空气目标已完成，要么进入放置阶段。
        if (clearing != null && player.level().isLoaded(clearing)
                && player.level().getBlockState(clearing).isAir()) r.scaffoldLedger().cleared(clearing);
        clearAt++; stopNav();
        if (clearAt < clearQueue.size()) { clearing = clearQueue.get(clearAt); phase = Phase.CLEAR_NAV; }
        else if (BuildCellRules.isAirTarget(cell.target())) finishCell();
        else {
            liveGestures = List.of(); gestureSearch = null; gestureProgress = null;
            phase = Phase.PLACE_NAV;
        }
        return TaskState.RUNNING;
    }

    private TaskState placeNavTick() {
        // Finish a confirmed intermediate slab/layer before moving to another target.
        if (useCount > 0) { worksite = null; worksiteSearch = null; worksiteSearched = true; }
        Vec3 approaching = worksite != null ? worksite.feet()
                : nav != null && gesture != null ? Vec3.atBottomCenterOf(gesture.stance()) : null;
        if (useCount == 0 && !isTemporary(cell) && selectNearbyPlacement(approaching)) return TaskState.RUNNING;
        BuildPlacementGeometry.Gesture nearby = BuildPlacementGeometry.currentGesture(player, cell.target(), targets,
                candidate -> placementAttempts.allows(cell.target(), candidate));
        if (nearby != null) {
            placementWalkTarget = approaching;
            stopNav(); gesture = nearby; gestureFromCurrent = true; phase = Phase.SELECT_ITEM;
            return TaskState.RUNNING;
        }
        // 一种放法不通就试下一种；站位必须精确到指定格，不能像长途旅行那样“附近就算到了”。
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
        if (useCount == 0 && !worksiteSearched && !isTemporary(cell)) {
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
            while (gestureAt < liveGestures.size()
                    && (!supportExists(liveGestures.get(gestureAt))
                    || !placementAttempts.allows(cell.target(), liveGestures.get(gestureAt)))) gestureAt++;
            if (gestureAt >= liveGestures.size()) return deferOrFail();
            gesture = liveGestures.get(gestureAt);
            gestureFromCurrent = false;
            BlockPos stance = gesture.stance();
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(stance), 1.0,
                    () -> PlayerNav.playerFeet(player).equals(stance), this);
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> { stopNav(); phase = Phase.SELECT_ITEM; yield TaskState.RUNNING; }
            case FAILED -> {
                placementAttempts.rejectStance(cell.target(), gesture.stance());
                stopNav(); gestureAt++; yield TaskState.RUNNING;
            }
        };
    }

    // 一处站位连放的候选必须不用先清障、有材料、尚未完成且最新修改守卫允许；临时垫块另走自己的队列。
    private boolean readyForWorksite(CellPlan candidate) {
        return !isTemporary(candidate) && !BuildCellRules.isAirTarget(candidate.target())
                && (!r.consumeMaterials || inventory.hasItems(candidate.target().item(), candidate.target().materialCount(), true))
                && player.level().isLoaded(candidate.target().pos())
                && !matches(candidate.target(), candidate.generated()) && clearCells(candidate).isEmpty()
                && r.mutationGuardMatches(player, candidate.target().pos());
    }

    // 先找当前伸手就够得着的待建格，最多验证十六格或约四毫秒；找到就移到队首，减少来回走路。
    private boolean selectNearbyPlacement(Vec3 approach) {
        int proofs = 0;
        long deadline = System.nanoTime() + 4_000_000;
        for (int i = queueAt; i < queue.size() && proofs < 16 && System.nanoTime() < deadline; i++) {
            CellPlan candidate = queue.get(i);
            if (candidate.target().pos().distToCenterSqr(player.getEyePosition()) > 36 || !readyForWorksite(candidate)) continue;
            proofs++;
            var available = BuildPlacementGeometry.currentGesture(player, candidate.target(), targets,
                    g -> placementAttempts.allows(candidate.target(), g));
            if (available == null) continue;
            java.util.Collections.swap(queue, queueAt, i); cell = candidate;
            placementWalkTarget = approach; stopNav();
            liveGestures = List.of(); gestureSearch = null; gestureProgress = null;
            gestureAt = 0; useCount = 0; selection.reset(); aimConvergence.reset();
            gesture = available; gestureFromCurrent = true; phase = Phase.SELECT_ITEM; return true;
        }
        return false;
    }

    // 做完一格后尝试继续利用原站位。现场、材料或剩余任务变了就重新检查，没有可做的格子才放弃该站位。
    private void retainUsefulWorksite() {
        if (worksite == null) return;
        long deadline = System.nanoTime() + 4_000_000;
        for (int at = 0; at < worksite.placements().size(); at++) {
            if (System.nanoTime() >= deadline) {
                // Resume only the unchecked tail next time; a slice ending is not invalidation.
                worksite = new BuildWorksitePlanner.Worksite(worksite.stance(), worksite.feet(),
                        worksite.placements().subList(at, worksite.placements().size()), worksite.distanceSquared());
                return;
            }
            var placement = worksite.placements().get(at);
            var target = placement.target();
            CellPlan pending = plansByPrimary.get(target.pos().asLong());
            int index = pending == null ? -1 : queue.subList(queueAt, queue.size()).indexOf(pending);
            if (index >= 0 && readyForWorksite(pending)
                    && BuildPlacementGeometry.liveGestureFrom(player, target, targets, worksite.feet(),
                    g -> placementAttempts.allows(target, g)) != null) {
                java.util.Collections.swap(queue, queueAt, queueAt + index);
                return;
            }
        }
        // Retaining a destination needs fresh evidence that it still benefits unfinished work.
        worksite = null; worksiteSearched = false; worksiteProgress = null;
    }

    // 把当前不用清障的目标交给多格站位搜索，逐刻累计检查；全部查完后才采用最佳站位。
    private TaskState worksiteTick() {
        if (worksiteSearch == null) {
            var pending = queue.subList(queueAt, queue.size()).stream().filter(this::readyForWorksite)
                    .map(CellPlan::target).toList();
            worksiteSearch = new BuildWorksitePlanner.Search(player, pending, targets,
                    pos -> !forbiddenBodyCells.contains(pos.asLong()) && !NavigationSafetyContext.forbidsBody(pos),
                    unionForbidden(NavigationSafetyContext.forbiddenBodyCells()), rejectedWorksites,
                    placementAttempts::allows);
        }
        var progress = worksiteSearch.advance(256);
        worksiteProgress = progress;
        note = "evaluating build worksite coverage: " + progress.candidateChecks() + " stances, "
                + (progress.best() == null ? 0 : progress.best().coverage()) + " placeable cells";
        if (!progress.complete()) return TaskState.RUNNING;
        worksite = progress.best(); worksiteSearch = null; worksiteSearched = true;
        if (worksite != null) {
            BuildTaskRecord.Target first = worksite.placements().getFirst().target();
            for (int i = queueAt; i < queue.size(); i++) if (queue.get(i).target() == first) {
                java.util.Collections.swap(queue, queueAt, i); cell = queue.get(queueAt); break;
            }
            gesture = worksite.placements().getFirst().gesture(); liveGestures = List.of(); gestureAt = 0;
        }
        // No direct corridor does not mean no route: ordinary navigation still tries around obstacles.
        phase = Phase.PLACE_NAV; return TaskState.RUNNING;
    }

    // 只沿现有地面走向选中的站位。走途中若能放置，placeNavTick 会先接手；到达后仍没有机会则排除该站位重找。
    private TaskState walkToWorksite() {
        if (nav == null) {
            BlockPos destination = worksite.stance();
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(destination), 1.0,
                    () -> PlayerNav.playerFeet(player).equals(destination), this).walkingOnly();
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED, FAILED -> {
                // Arrival without any live placement (checked above) is stale evidence, not an aim request.
                rejectedWorksites.add(worksite.stance()); stopNav(); worksite = null;
                worksiteSearched = false; phase = Phase.WORKSITE; yield TaskState.RUNNING;
            }
        };
    }

    private boolean supportExists(BuildPlacementGeometry.Gesture g) {
        if (!player.level().isLoaded(g.clicked())) return false;
        BlockState live = player.level().getBlockState(g.clicked());
        if (g.clicked().equals(cell.target().pos())) return !live.isAir();
        return !live.isAir() && !live.canBeReplaced();
    }

    private TaskState selectItemTick() {
        placementPostureAndMotion();
        if (matches(cell.target(), cell.generated())) {
            finishPlaced();
            return TaskState.RUNNING;
        }
        int slot;
        // Finish any native swap before reconciling ownership against its post-swap inventory.
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
        phase = Phase.AIM; aimConvergence.reset(); aimProgress.reset(); lastAimObservation = Map.of();
        return TaskState.RUNNING;
    }

    private TaskState aimTick() {
        // 先真正转头到位，复查视线会点到哪，再预测会放成什么状态，并检查是否把自己或生物卡进方块。
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
        if (!player.getMainHandItem().is(cell.target().item())) {
            selection.reset(); phase = Phase.SELECT_ITEM; return TaskState.RUNNING;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 desired = gesture.point().subtract(eye).normalize();
        aimError = Math.toDegrees(Math.acos(Math.clamp(player.getViewVector(1).normalize().dot(desired), -1, 1)));
        placementPostureAndMotion();
        InputDriver.lookAt(player, gesture.point());
        if (player.isShiftKeyDown() != gesture.sneak()) return waitForAim("waiting_for_posture", false);
        HitResult crosshair = placementRay.apply(player, 4.5);
        aimHit = crosshair.getType().name().toLowerCase(java.util.Locale.ROOT);
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
        aimWaitReason = "placement_submitted";
        useReceipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, hit,
                confirmation(cell, frozen, placedPrimary), USE_TIMEOUT);
        phase = Phase.WAIT_USE; return TaskState.RUNNING;
    }

    // 瞄准还在改善就继续等；视角已稳定且误差极小，或四十刻没有足够改善时，记录这次点击失败并换方案。
    private TaskState waitForAim(String reason, boolean settled) {
        aimWaitReason = reason;
        lastAimObservation = placementDiagnostics();
        // Near a slab's half-height boundary, a one-degree view tolerance is still a different click.
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
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
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
        if (isTemporary(cell) && useCount > 0)
            confirmedScaffold(cell.target().pos(), player.level().getBlockState(cell.target().pos()));
        // Keep creative materials available for later cells instead of clearing the slot per click.
        r.placedOne(); renewBuildProgress(); markComplete(cell);
        finishCell();
    }

    // 动作确认后通知外部观察守卫更新事实，再使附近的失败点击和搜索结果失效。
    private void confirmedBlockChange(BlockPos pos) {
        r.confirmedMutation(player, pos);
        placementEnvironmentChanged(pos);
    }

    // 附近方块变了，旧的“这里放不了”可能已经不成立；清掉六格内对应记录，允许用新支撑重试。
    private void placementEnvironmentChanged(BlockPos pos) {
        placementAttempts.changedNear(pos);
        exhaustedPlacementStates.keySet().removeIf(key -> BlockPos.of(key).distSqr(pos) <= 36);
        if (cell != null && cell.target().pos().distSqr(pos) <= 36) {
            gestureSearch = null; gestureProgress = null;
            liveGestures = List.of(); gestureAt = 0;
            // Keep any active walk bound to its current gesture; re-enumerate after it settles.
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
    private java.util.function.ToIntFunction<Item> creativeNeeds() {
        return new java.util.function.ToIntFunction<>() {
            private Map<Item, Integer> next;
            public int applyAsInt(Item item) {
                if (next == null) {
                    next = new HashMap<>(); int order = 0;
                    for (int i = queueAt; i < queue.size(); i++, order++) {
                        var pending = queue.get(i);
                        if (pending.target().costsMaterial() && !matches(pending.target(), pending.generated()))
                            next.putIfAbsent(pending.target().item(), order);
                    }
                    // A target changed after verification still needs its cached material.
                    for (var target : r.targets) if (target.costsMaterial() && (!player.level().isLoaded(target.pos())
                            || !target.matches(player.level().getBlockState(target.pos())))) next.putIfAbsent(target.item(), order++);
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
            TaskState recovery = prepareTemporarySupports();
            if (recovery != null) return recovery;
            failAt(cell.target().pos(),
                    "the complete finite stance set reached the same body and world state again without progress",
                    FailureType.NO_PATH, "placement_no_progress", false);
            return TaskState.FAILED;
        }

        List<CellPlan> pending = BuildTemporarySupportPlan.defer(queue, queueAt,
                candidate -> !matches(candidate.target(), candidate.generated()));
        if (pending.size() > 1) {
            queue = pending;
            queueAt = 0;
            note = "support-dependent cells were deferred until supports existed";
            phase = Phase.SELECT; return TaskState.RUNNING;
        }
        TaskState recovery = prepareTemporarySupports();
        if (recovery != null) return recovery;
        failAt(cell.target().pos(), "the complete finite first-person stance set was exhausted",
                FailureType.NO_PATH, "placement_stances_exhausted", false); return TaskState.FAILED;
    }

    // 临时点击支撑最终会拆掉，因此原目标必须本来就能存活，不能靠临时垫块永久托住沙子或悬空装饰。
    private TaskState prepareTemporarySupports() {
        // Supports used only to obtain a click face must not be required for final survival.
        if (isTemporary(cell) || cell.target().block() instanceof net.minecraft.world.level.block.FallingBlock
                || !cell.target().desiredState().canSurvive(player.level(), cell.target().pos())) return null;
        List<BlockPos> chain = BuildTemporarySupportPlan.find(player.level(), player.level()::isLoaded,
                cell.target().pos(), pos -> scaffoldPermitted(pos, null));
        if (chain.isEmpty()) return null;
        Item material = null;
        // 只从允许消耗的垫脚材料里选完整、不下落、无方块实体的方块；当前要求一种材料足够铺完整条链。
        for (Item candidate : org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials.of(player)) {
            if (!(candidate instanceof BlockItem blockItem)) continue;
            BlockState state = blockItem.getBlock().defaultBlockState();
            if (state.hasBlockEntity() || blockItem.getBlock() instanceof net.minecraft.world.level.block.FallingBlock
                    || !state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) continue;
            if (player.getAbilities().instabuild && !r.consumeMaterials
                    || inventory.mainInventoryCount(candidate) >= chain.size()) { material = candidate; break; }
        }
        if (material == null) {
            failAt(cell.target().pos(), "placement needs " + chain.size()
                            + " full temporary support blocks from scaffold_materials in inventory",
                    FailureType.NO_MATERIAL, "temporary_support_materials_missing", false);
            return TaskState.FAILED;
        }
        List<CellPlan> prepared = new ArrayList<>();
        for (BlockPos pos : chain) {
            var target = new BuildTaskRecord.Target(((BlockItem) material).getBlock(), material,
                    pos, "temporary click support", null, null, null).asItemPlace();
            temporaryTargets.put(pos.asLong(), target);
            prepared.add(new CellPlan(target, List.of()));
        }
        prepared.add(cell);
        for (int i = queueAt + 1; i < queue.size(); i++) prepared.add(queue.get(i));
        queue = prepared; queueAt = 0; phase = Phase.SELECT;
        note = "placing " + chain.size() + " removable supports before the deferred cell";
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
        exhaustedPlacementStates.remove(cell.target().pos().asLong());
        markComplete(cell); queueAt++; resetCell(); phase = Phase.SELECT;
    }
    private void resetCell() {
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
                if (!ownedAirScaffold(target, state)) {
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
            phase = Phase.SCAFFOLD_NAV; return TaskState.RUNNING;
        }
        r.completed(countMatching());
        if (r.completed() != r.targets.size()) { beginVerify(); return TaskState.RUNNING; }
        if (r.traversabilityContract() != null) {
            traversabilityScan = BuildTraversabilityVerifier.begin(
                    player.clientLevel, r.traversabilityContract());
            phase = Phase.ROUTE_VERIFY;
            return TaskState.RUNNING;
        }
        retainVerifiedSitePosition();
        return TaskState.SUCCESS;
    }

    private TaskState routeVerifyTick() {
        // 有进门／上楼通行要求的方案，还要检查这些地方实际连得通；方块都对并不一定代表房子能用。
        traversabilityResult = traversabilityScan.tick();
        if (traversabilityResult == null) {
            // This tick consumed new, finite verification work; yielding it must not spend the
            // build's remaining native-action budget on main-thread scheduling fairness.
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return TaskState.RUNNING;
        }
        if (!traversabilityResult.valid()) {
            failAt(traversabilityResult.position(), traversabilityResult.message(),
                    FailureType.NO_PATH, traversabilityResult.code(), false);
            return TaskState.FAILED;
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

    // 清理时使用普通导航去到可挖支撑的位置，施工专用的方块保护提供者已经注销；仍受外层导航保护约束。
    private TaskState scaffoldNavTick() {
        if (nav == null) {
            BlockPos at = scaffold.immutable();
            nav = PlayerNav.toGoal(player, () -> NavGoal.mineStance(at), 1.0,
                    () -> inReach(at), PlayerNav.ContextProvider.DEFAULT);
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> { stopNav(); phase = Phase.SCAFFOLD_BREAK; yield TaskState.RUNNING; }
            case FAILED -> {
                FailureType type = nav.failType(); String reason = nav.failReason(); stopNav();
                failAt(scaffold, "could not reach temporary scaffold: " + reason,
                        type, "scaffold_cleanup_path_failed", false); yield TaskState.FAILED;
            }
        };
    }

    private TaskState scaffoldBreakTick() {
        // 只拆自己曾确认放下、现在仍是原状态的临时支撑；被别人换过或新加保护的就停，不误拆新东西。
        BlockState live = player.level().getBlockState(scaffold);
        if (live.isAir()) {
            r.scaffoldLedger().cleared(scaffold);
            scaffoldAt++; phase = Phase.SCAFFOLD_SELECT; return TaskState.RUNNING;
        }
        if (inheritedProtectedMutationCells.contains(scaffold.asLong())
                || NavigationSafetyContext.protectsMutation(scaffold) || NavigationSafetyContext.forbidsBody(scaffold)
                || r.scaffoldLedger().contains(scaffold) && !r.scaffoldLedger().owns(scaffold, live)) {
            failAt(scaffold, "temporary scaffold changed or gained protection before cleanup",
                    FailureType.TARGET_LOST, "scaffold_cleanup_guard_changed", true);
            return TaskState.FAILED;
        }
        return switch (digger.digTargetStep(scaffold)) {
            case PROGRESSING -> TaskState.RUNNING;
            case BROKE_TARGET -> {
                r.scaffoldLedger().cleared(scaffold);
                r.brokeOne(); renewBuildProgress(); scaffoldAt++;
                phase = Phase.SCAFFOLD_SELECT; yield TaskState.RUNNING;
            }
            case BROKE_OCCLUDER -> {
                failAt(scaffold, "scaffold cleanup changed another block", FailureType.INTERNAL,
                        "scaffold_cleanup_wrong_block", true); yield TaskState.FAILED;
            }
            case NO_SHOT -> {
                failAt(scaffold, "scaffold remains but has no safe first-person ray",
                        FailureType.OCCLUDED, "scaffold_cleanup_occluded", false); yield TaskState.FAILED;
            }
        };
    }

    private NativeConfirmation confirmation(CellPlan plan, Map<Long, BlockState> before, BlockState predicted) {
        lastUseConfirmation = new BuildPlacementConfirmation(plan.target(), plan.generated(), before, predicted);
        return lastUseConfirmation;
    }

    private Map<Long, BlockState> freeze(CellPlan plan) {
        Map<Long, BlockState> out = new LinkedHashMap<>();
        out.put(plan.target().pos().asLong(), player.level().getBlockState(plan.target().pos()));
        for (BuildPlacementGeometry.GeneratedCell effect : plan.generated())
            out.put(effect.pos().asLong(), player.level().getBlockState(effect.pos()));
        return Map.copyOf(out);
    }

    private boolean matches(BuildTaskRecord.Target target,
                             List<BuildPlacementGeometry.GeneratedCell> generated) {
        if (temporaryTargets.get(target.pos().asLong()) == target && player.level().isLoaded(target.pos())) {
            BlockState live = player.level().getBlockState(target.pos());
            if (r.scaffoldLedger().owns(target.pos(), live) && !live.canBeReplaced()
                    && !live.getShape(player.level(), target.pos()).isEmpty()) return true;
        }
        // 不只看主格，自动生成的另一半也必须已加载且正确；缺观察不能当成正确。
        if (!player.level().isLoaded(target.pos())
                || !target.matches(player.level().getBlockState(target.pos()))) return false;
        for (BuildPlacementGeometry.GeneratedCell effect : generated)
            if (!player.level().isLoaded(effect.pos())
                    || !BuildValidity.valid(player.level().getBlockState(effect.pos()),
                    effect.expected(), false)) return false;
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code); if (pos != null) data.put("pos", pos.toShortString());
        data.put("detail", detail); data.put("decision_options", decisions); unsupported.add(data);
    }
    private void addBlocked(String code, BlockPos pos, String detail) {
        if (blocked.size() < 64) blocked.add(Map.of(
                "code", code, "pos", pos.toShortString(), "detail", detail));
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
        // The embedded native receipt, rather than an attempted click, grants cleanup ownership.
        BuildTaskRecord.Target target = targets.get(placeAt.asLong());
        if (target != null && !BuildCellRules.isAirTarget(target)) return;
        if (!state.isAir() && state.getFluidState().isEmpty()) {
            if (!r.scaffoldLedger().owns(placeAt, state)) placementEnvironmentChanged(placeAt);
            r.scaffoldLedger().confirmed(placeAt, state); scaffolds.add(placeAt.immutable());
            renewBuildProgress();
        }
    }

    @Override public void confirmedScaffoldRemoval(BlockPos pos) {
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

    private boolean scaffoldPermitted(BlockPos pos, LongSet additionalProtection) {
        if (!player.level().isLoaded(pos)) return false;
        boolean hard = inheritedProtectedMutationCells.contains(pos.asLong())
                || forbiddenBodyCells.contains(pos.asLong())
                || additionalProtection != null && additionalProtection.contains(pos.asLong())
                || NavigationSafetyContext.protectsMutation(pos) || NavigationSafetyContext.forbidsBody(pos);
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
                || target.matches(player.level().getBlockState(pos))) return null;
        return target.desiredState();
    }
    @Override public boolean acceptsPlacement(BlockPos pos, BlockState state) {
        BuildTaskRecord.Target target = targets.get(pos.asLong());
        return target != null && target.acceptsPlacedState(state);
    }
    @Override public CalculationContext forSearch(LocalPlayer p, LongSet sacred, LongSet denied,
                                                  LongSet forbidden) {
        return ContextFactory.forSearch(p, union(sacred), union(denied),
                unionForbidden(forbidden), permit(),
                (body, view, loaded, safe, s, d, f, terrain) -> new BuildCalculationContext(
                        body, view, loaded, safe, s, d, f, terrain, targets,
                        inventory.availableStates(true), r.replaceExisting));
    }
    @Override public CalculationContext forExecution(LocalPlayer p, LongSet sacred, LongSet denied,
                                                     LongSet forbidden) {
        return ContextFactory.forExecution(p, union(sacred), union(denied),
                unionForbidden(forbidden), permit(),
                (body, view, loaded, safe, s, d, f, terrain) -> new BuildCalculationContext(
                        body, view, loaded, safe, s, d, f, terrain, targets,
                        inventory.availableStates(true), r.replaceExisting));
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
        Map<String, Object> data = new LinkedHashMap<>(Map.of("task", name(), "phase", phase.name().toLowerCase(java.util.Locale.ROOT),
                "planned_cells", r.targets.size(), "verified_cells", r.completed(),
                "placed_blocks", r.placed(), "cleared_blocks", r.broken(),
                "temporary_supports_remaining", r.scaffoldLedger().snapshot().size()));
        if (phase == Phase.AIM) data.put("placement", placementDiagnostics());
        data.put("creative_materials", creativeMaterials.progress());
        if (gestureProgress != null) data.put("placement_search", Map.of("complete", gestureProgress.complete(),
                "stance_checks", gestureProgress.stanceChecks(), "face_checks", gestureProgress.probeCount(),
                "candidates", gestureProgress.gestureCount()));
        if (worksiteProgress != null || worksite != null) data.put("worksite", worksiteProgress());
        if (!lastPlacementRejection.isEmpty()) data.put("last_placement_rejection", lastPlacementRejection);
        return Map.copyOf(data);
    }

    private Map<String, Object> worksiteProgress() {
        Map<String, Object> data = new LinkedHashMap<>();
        if (worksiteProgress != null) {
            data.put("search_complete", worksiteProgress.complete());
            data.put("candidate_checks", worksiteProgress.candidateChecks());
            data.put("placement_checks", worksiteProgress.placementChecks());
            data.put("best_coverage", worksiteProgress.best() == null ? 0 : worksiteProgress.best().coverage());
        }
        if (worksite != null) {
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
                "rejected_gestures", placementAttempts.rejectedCount(cell.target()));
    }

    @Override public void stop(LocalPlayer companion, StopReason why) {
        // 暂停时先停当前挖掘、撤掉导航的施工协助并松键；任务进度仍保留，恢复后可以重新登记协助。
        if (digger.current() != null) digger.cancel();
        drainScaffolds(); unregisterProvider(); super.stop(companion, why); InputDriver.halt(player);
    }
    @Override protected void cleanup() {
        // 结束时释放预览、挖掘和菜单；中断保留创造材料与已建方块，正常完成先经过材料清理阶段。
        BuildPreviewGate.release(r);
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
        data.put("cleared", r.broken());
        data.put("stopped_phase", phase.name().toLowerCase(java.util.Locale.ROOT));
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
            if (BuildCellRules.isAirTarget(target) || matches(target, plan.generated())) continue;
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
