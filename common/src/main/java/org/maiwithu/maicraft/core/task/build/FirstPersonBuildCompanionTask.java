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
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
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
    private static final int CREATIVE_TIMEOUT = 30;

    private enum Phase { PREFLIGHT, SELECT, CLEAR_NAV, CLEAR, PLACE_NAV, SELECT_ITEM,
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
    private int verifyAt, scaffoldAt;
    private List<CellPlan> queue = new ArrayList<>();
    private CellPlan cell;
    private List<BlockPos> clearQueue = List.of();
    private BlockPos clearing;
    private List<BuildPlacementGeometry.Gesture> liveGestures = List.of();
    private BuildPlacementGeometry.Gesture gesture;
    private Vec3 placementWalkTarget;
    private NativeActionReceipt useReceipt, creativeReceipt;
    private VisibleMenuSession creativeMenu = new VisibleMenuSession();
    private BuildTraversabilityVerifier.Result traversabilityResult;
    private BuildTraversabilityVerifier.Verification traversabilityScan;
    private int creativeSlot = -1;
    private ItemStack creativeStack = ItemStack.EMPTY;
    private FirstPersonActionGate selection = new FirstPersonActionGate();
    private final ActualViewConvergenceGate aimConvergence = new ActualViewConvergenceGate();
    private final LinkedHashSet<Long> verifyFailed = new LinkedHashSet<>();
    private final List<ObservedCell> verifyFailureStates = new ArrayList<>();
    private List<BlockPos> scaffoldQueue = List.of();
    private BlockPos scaffold, siteMin, siteMax, failurePos;
    private String failureCode, note = "all native actions re-verified";

    FirstPersonBuildCompanionTask(LocalPlayer player, BuildTaskRecord record) {
        // 保存全部目标并按施工顺序排序，记住不能让导航破坏的格子，以及上一批留下的临时支撑。
        super(player, record);
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
        return BuildTickPipeline.advance(() -> phase, this::stepPhase,
                () -> phase != Phase.WAIT_USE && phase != Phase.CLEAR_CREATIVE
                        && ClientRuntime.requireContext(player).mutationAvailable());
    }

    private TaskState stepPhase() {
        return switch (phase) {
            case PREFLIGHT -> preflightTick(); case SELECT -> selectTick();
            case CLEAR_NAV -> clearNavTick(); case CLEAR -> clearTick();
            case PLACE_NAV -> placeNavTick(); case SELECT_ITEM -> selectItemTick();
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
                liveGestures = BuildPlacementGeometry.plan(player, cell.target(), targets);
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
                    r.confirmedMutation(player, clearing);
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
                r.confirmedMutation(player, clearing);
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
            liveGestures = BuildPlacementGeometry.plan(player, cell.target(), targets);
            phase = Phase.PLACE_NAV;
        }
        return TaskState.RUNNING;
    }

    private TaskState placeNavTick() {
        BuildPlacementGeometry.Gesture nearby = BuildPlacementGeometry.currentGesture(player, cell.target(), targets);
        if (nearby != null) {
            placementWalkTarget = nav != null && gesture != null ? Vec3.atBottomCenterOf(gesture.stance()) : null;
            stopNav(); gesture = nearby; phase = Phase.SELECT_ITEM;
            return TaskState.RUNNING;
        }
        // 一种放法不通就试下一种；站位必须精确到指定格，不能像长途旅行那样“附近就算到了”。
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
        while (gestureAt < liveGestures.size()
                && !supportExists(liveGestures.get(gestureAt))) gestureAt++;
        if (gestureAt >= liveGestures.size()) return deferOrFail();
        gesture = liveGestures.get(gestureAt);
        if (nav == null) {
            BlockPos stance = gesture.stance();
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(stance), 1.0,
                    () -> PlayerNav.playerFeet(player).equals(stance), this);
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> { stopNav(); phase = Phase.SELECT_ITEM; yield TaskState.RUNNING; }
            case FAILED -> { stopNav(); gestureAt++; yield TaskState.RUNNING; }
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
        // 生存模式从实际背包拿材料；创造模式缺材料时借一个空快捷栏格临时放入，之后还要清掉。
        if (creativeReceipt != null) {
            LocalPlayerContext ctx = ClientRuntime.requireContext(player);
            creativeReceipt = ctx.actions().poll(ctx, creativeReceipt);
            if (!creativeReceipt.terminal()) return TaskState.RUNNING;
            if (creativeReceipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                failAt(cell.target().pos(), "creative staging not confirmed: " + creativeReceipt.detail(),
                        FailureType.UNKNOWN, "creative_stage_failed",
                        creativeReceipt.status() == NativeActionReceipt.Status.UNCERTAIN);
                return TaskState.FAILED;
            }
            if (!creativeMenu.close(ctx)) return TaskState.RUNNING;
            creativeReceipt = null;
            creativeMenu = new VisibleMenuSession();
        }
        if (matches(cell.target(), cell.generated())) {
            if (!creativeMenu.close(ClientRuntime.requireContext(player))) return TaskState.RUNNING;
            creativeMenu = new VisibleMenuSession();
            finishPlaced();
            return TaskState.RUNNING;
        }
        int slot = inventory.findSlot(cell.target().item(), true);
        if (slot < 0 && player.getAbilities().instabuild) {
            if (creativeSlot < 0) creativeSlot = emptyHotbar();
            if (creativeSlot < 0) {
                failAt(cell.target().pos(), "creative placement needs an empty hotbar slot",
                        FailureType.NO_SPACE, "no_creative_staging_slot", false); return TaskState.FAILED;
            }
            if (creativeStack.isEmpty()) {
                LocalPlayerContext ctx = ClientRuntime.requireContext(player);
                if (!creativeMenu.inventoryReady(ctx)) return TaskState.RUNNING;
                creativeStack = new ItemStack(cell.target().item(), 1);
                creativeReceipt = ctx.actions().creativeSetSlot(
                        ctx, creativeSlot, creativeStack, CREATIVE_TIMEOUT);
                return TaskState.RUNNING;
            }
            slot = creativeSlot;
        }
        if (slot < 0) {
            missing.clear();
            missing.putAll(currentShortfall());
            failAt(cell.target().pos(), "required block item is not in synchronized inventory",
                    FailureType.NO_MATERIAL, "material_exhausted", false); return TaskState.FAILED;
        }
        FirstPersonActionGate.Status status = selection.select(player, slot);
        if (status == FirstPersonActionGate.Status.RUNNING) return TaskState.RUNNING;
        if (status == FirstPersonActionGate.Status.FAILED) {
            failAt(cell.target().pos(), "item selection failed: " + selection.failure(),
                    FailureType.UNKNOWN, "item_selection_failed", false); return TaskState.FAILED;
        }
        phase = Phase.AIM; aimConvergence.reset(); return TaskState.RUNNING;
    }

    private TaskState aimTick() {
        // 先真正转头到位，复查视线会点到哪，再预测会放成什么状态，并检查是否把自己或生物卡进方块。
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
        Vec3 eye = player.getEyePosition();
        placementPostureAndMotion();
        InputDriver.lookAt(player, gesture.point());
        if (player.isShiftKeyDown() != gesture.sneak()) return TaskState.RUNNING;
        HitResult crosshair = Interaction.nativeRaytrace(player, 4.5);
        if (!(crosshair instanceof BlockHitResult hit) || !placesAt(hit, cell.target().pos())) {
            return aimConvergence.ready(player, gesture.point().subtract(eye)) ? rejectGesture() : TaskState.RUNNING;
        }
        BlockState before = player.level().getBlockState(cell.target().pos());
        BlockState predicted = BuildPlacementGeometry.predict(
                player, cell.target(), hit, player.getYRot(), player.getXRot());
        if (!cell.target().itemPlace() && (predicted == null
                || (!cell.target().acceptsPlacedState(predicted)
                && !BuildPlacementGeometry.isProgress(cell.target(), before, predicted)))) {
            return aimConvergence.ready(player, gesture.point().subtract(eye)) ? rejectGesture() : TaskState.RUNNING;
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
        useReceipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, hit,
                confirmation(cell, frozen), USE_TIMEOUT);
        phase = Phase.WAIT_USE; return TaskState.RUNNING;
    }

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
        InputDriver.halt(player); stopNav(); selection.reset(); aimConvergence.reset();
        gesture = null;
        do {
            gestureAt++;
        } while (gestureAt < liveGestures.size()
                && liveGestures.get(gestureAt).stance().equals(occupiedFeet));
        phase = Phase.PLACE_NAV;
        return TaskState.RUNNING;
    }

    private TaskState rejectGesture() {
        InputDriver.halt(player); stopNav(); selection.reset();
        aimConvergence.reset(); gesture = null; gestureAt++; phase = Phase.PLACE_NAV;
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
        r.confirmedMutation(player, cell.target().pos());
        for (BuildPlacementGeometry.GeneratedCell effect : cell.generated()) r.confirmedMutation(player, effect.pos());
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
        BlockState live = player.level().getBlockState(cell.target().pos());
        if (useCount < BuildPlacementGeometry.maximumUses(cell.target())
                && BuildPlacementGeometry.isProgress(cell.target(), Blocks.AIR.defaultBlockState(), live)) {
            // 雪层、双层台阶等需要多次放置；看到已经朝目标前进时，继续补下一次，而不是把中间状态拆掉。
            renewBuildProgress();
            liveGestures = BuildPlacementGeometry.plan(player, cell.target(), targets);
            gestureAt = 0; gesture = null; selection.reset(); aimConvergence.reset();
            phase = Phase.PLACE_NAV;
            return TaskState.RUNNING;
        }
        failAt(cell.target().pos(), "click changed world but not to the requested verified state",
                FailureType.UNSUPPORTED, "placement_state_mismatch", true); return TaskState.FAILED;
    }

    private void finishPlaced() {
        if (isTemporary(cell) && useCount > 0)
            confirmedScaffold(cell.target().pos(), player.level().getBlockState(cell.target().pos()));
        // 记下这一格完成；如果借用了创造快捷栏，还要先归还。当前 placed 计数也会包含期间由外界放成的格子。
        r.placedOne(); renewBuildProgress(); markComplete(cell);
        if (creativeSlot >= 0 && !creativeStack.isEmpty()) {
            phase = Phase.CLEAR_CREATIVE;
        } else finishCell();
    }

    private TaskState clearCreativeTick() {
        // 清除为本次施工临时放入的创造物品，等清除确认并关好物品栏，再做下一格。
        LocalPlayerContext ctx = ClientRuntime.requireContext(player);
        if (creativeReceipt == null) {
            if (!creativeMenu.inventoryReady(ctx)) return TaskState.RUNNING;
            creativeReceipt = ctx.actions().creativeSetSlot(
                    ctx, creativeSlot, ItemStack.EMPTY, CREATIVE_TIMEOUT);
            return TaskState.RUNNING;
        }
        creativeReceipt = ctx.actions().poll(ctx, creativeReceipt);
        if (!creativeReceipt.terminal()) return TaskState.RUNNING;
        if (creativeReceipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            failAt(cell.target().pos(), "temporary creative item was not cleared: "
                            + creativeReceipt.detail(), FailureType.UNKNOWN, "creative_cleanup_failed",
                    creativeReceipt.status() == NativeActionReceipt.Status.UNCERTAIN);
            return TaskState.FAILED;
        }
        if (!creativeMenu.close(ctx)) return TaskState.RUNNING;
        creativeReceipt = null; creativeSlot = -1; creativeStack = ItemStack.EMPTY;
        creativeMenu = new VisibleMenuSession();
        finishCell(); return TaskState.RUNNING;
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

    private TaskState prepareTemporarySupports() {
        // Supports used only to obtain a click face must not be required for final survival.
        if (isTemporary(cell) || cell.target().block() instanceof net.minecraft.world.level.block.FallingBlock
                || !cell.target().desiredState().canSurvive(player.level(), cell.target().pos())) return null;
        List<BlockPos> chain = BuildTemporarySupportPlan.find(player.level(), player.level()::isLoaded,
                cell.target().pos(), pos -> scaffoldPermitted(pos, null));
        if (chain.isEmpty()) return null;
        Item material = null;
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
        stopNav(); clearing = null; clearQueue = List.of(); clearAt = 0;
        liveGestures = List.of(); gestureAt = 0; gesture = null;
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

    private NativeConfirmation confirmation(CellPlan plan, Map<Long, BlockState> before) {
        // 主格和另一半都达到要求才完全确认；支持叠加的中间状态也算本次点击有进展，其余变化按不一致处理。
        return ctx -> {
            if (!ctx.level().isLoaded(plan.target().pos())) return NativeConfirmation.Verdict.PENDING;
            if (matches(ctx, plan)) return NativeConfirmation.Verdict.APPLIED;
            BlockState old = before.get(plan.target().pos().asLong());
            BlockState live = ctx.level().getBlockState(plan.target().pos());
            if (BuildPlacementGeometry.isProgress(plan.target(), old, live))
                return NativeConfirmation.Verdict.APPLIED;
            boolean unchanged = live.equals(old);
            for (BuildPlacementGeometry.GeneratedCell effect : plan.generated()) {
                if (!ctx.level().isLoaded(effect.pos())) return NativeConfirmation.Verdict.PENDING;
                BlockState was = before.get(effect.pos().asLong());
                BlockState now = ctx.level().getBlockState(effect.pos());
                unchanged &= now.equals(was);
                if (!now.equals(was) && !BuildValidity.valid(now, effect.expected(), false))
                    return NativeConfirmation.Verdict.DIVERGED;
            }
            return unchanged ? NativeConfirmation.Verdict.PENDING : NativeConfirmation.Verdict.DIVERGED;
        };
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

    private static boolean matches(LocalPlayerContext ctx, CellPlan plan) {
        if (!ctx.level().isLoaded(plan.target().pos())
                || !plan.target().matches(ctx.level().getBlockState(plan.target().pos()))) return false;
        for (BuildPlacementGeometry.GeneratedCell effect : plan.generated())
            if (!ctx.level().isLoaded(effect.pos())
                    || !BuildValidity.valid(ctx.level().getBlockState(effect.pos()),
                    effect.expected(), false)) return false;
        return true;
    }

    private void markComplete(CellPlan plan) {
        if (isTemporary(plan)) return;
        // 只把此刻确实匹配的已声明目标计入完成数；后来外界改坏时，复查会把它从完成数里移出。
        int before = r.completed();
        if (player.level().isLoaded(plan.target().pos())
                && plan.target().matches(player.level().getBlockState(plan.target().pos())))
            completed.add(plan.target().pos().asLong());
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
    private int emptyHotbar() {
        for (int i = 0; i < Math.min(9, player.getInventory().getContainerSize()); i++)
            if (player.getInventory().getItem(i).isEmpty()) return i;
        return -1;
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
            r.scaffoldLedger().confirmed(placeAt, state); scaffolds.add(placeAt.immutable());
            renewBuildProgress();
        }
    }

    @Override public void confirmedScaffoldRemoval(BlockPos pos) { r.scaffoldLedger().cleared(pos); }

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
        return Map.of("task", name(), "phase", phase.name().toLowerCase(java.util.Locale.ROOT),
                "planned_cells", r.targets.size(), "verified_cells", r.completed(),
                "placed_blocks", r.placed(), "cleared_blocks", r.broken(),
                "temporary_supports_remaining", r.scaffoldLedger().snapshot().size());
    }

    @Override public void stop(LocalPlayer companion, StopReason why) {
        // 暂停时先停当前挖掘、撤掉导航的施工协助并松键；任务进度仍保留，恢复后可以重新登记协助。
        if (digger.current() != null) digger.cancel();
        drainScaffolds(); unregisterProvider(); super.stop(companion, why); InputDriver.halt(player);
    }
    @Override protected void cleanup() {
        // 永久结束时再释放预览、挖掘和菜单，并尽力清掉创造临时物品；已经实际建好的方块不会自动拆回去。
        BuildPreviewGate.release(r);
        if (digger.current() != null) digger.cancel();
        drainScaffolds(); unregisterProvider(); InputDriver.halt(player); selection.reset();
        aimConvergence.reset();
        retireCreativeReceipt();
        bestEffortCreativeCleanup(); retireCreativeReceipt();
        creativeMenu.cleanup(player); super.cleanup();
    }
    private void retireCreativeReceipt() {
        if (creativeReceipt == null || creativeReceipt.terminal()) return;
        try {
            LocalPlayerContext ctx = ClientRuntime.requireContext(player);
            ctx.actions().retireOneShotForTaskBoundary(ctx, creativeReceipt, "creative build task ended");
        } catch (RuntimeException unavailable) { /* Actor revocation owns old-body receipts. */ }
    }
    private void bestEffortCreativeCleanup() {
        if (creativeSlot < 0 || creativeStack.isEmpty() || !player.getAbilities().instabuild) return;
        ItemStack live = player.getInventory().getItem(creativeSlot);
        if (!ItemStack.isSameItemSameComponents(live, creativeStack)) return;
        try {
            LocalPlayerContext ctx = ClientRuntime.requireContext(player);
            if (MenuVisibility.inventoryVisible(ctx.minecraft(), player)
                    && (creativeReceipt == null || creativeReceipt.terminal()) && ctx.menus().ensureVisible(ctx))
                creativeReceipt = ctx.actions().creativeSetSlot(ctx, creativeSlot, ItemStack.EMPTY, CREATIVE_TIMEOUT);
        } catch (RuntimeException ignored) { }
    }

    @Override
    protected Map<String, Object> resultData() {
        // 报告已匹配多少、放过和清过多少、还缺材料以及未清理支撑；只有最终格子和通行均通过才附整体验证。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requested", r.targets.size());
        data.put("completed", r.completed());
        data.put("placed", r.placed());
        data.put("cleared", r.broken());
        data.put("site_min", siteMin == null ? "-" : siteMin.toShortString());
        data.put("site_max", siteMax == null ? "-" : siteMax.toShortString());

        if (!required.isEmpty()) data.put("required_materials", itemCounts(required));
        Map<Item, Integer> outstanding = preflightDone ? currentShortfall() : missing;
        if (!outstanding.isEmpty()) data.put("missing_materials", itemCounts(outstanding));
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

        if (creativeSlot >= 0 && !creativeStack.isEmpty()
                && ItemStack.isSameItemSameComponents(
                player.getInventory().getItem(creativeSlot), creativeStack)) {
            data.put("temporary_creative_slot_pending_cleanup", creativeSlot);
            data.put("temporary_creative_item",
                    BuiltInRegistries.ITEM.getKey(creativeStack.getItem()).toString());
        }

        if (traversabilityResult != null) {
            data.put("traversability_verification", traversabilityResult.evidence());
        }

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
