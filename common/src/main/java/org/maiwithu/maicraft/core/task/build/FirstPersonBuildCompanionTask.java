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
import org.maiwithu.maicraft.core.pathing.execute.PathExecutor;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Receipt-driven construction performed only through the local first-person body. */
class FirstPersonBuildCompanionTask extends AbstractCompanionTask<BuildTaskRecord>
        implements BuildPlacementRegistry.Provider, PlayerNav.ContextProvider {
    private static final int PREFLIGHT_BUDGET = 24;
    private static final int USE_TIMEOUT = 40;
    private static final int CREATIVE_TIMEOUT = 30;
    private static final int AIM_TICKS = 12;
    private static final int MAX_GESTURES = 12;
    private static final int MAX_DEFERS = 3;
    private static final int MAX_REPAIRS = 2;

    private enum Phase { PREFLIGHT, SELECT, CLEAR_NAV, CLEAR, PLACE_NAV, SELECT_ITEM,
        AIM, WAIT_USE, CLEAR_CREATIVE, VERIFY, SCAFFOLD_SELECT, SCAFFOLD_NAV, SCAFFOLD_BREAK }
    private record CellPlan(BuildTaskRecord.Target target,
                            List<BuildPlacementGeometry.Gesture> gestures,
                            List<BuildPlacementGeometry.GeneratedCell> generated) {
        CellPlan { gestures = List.copyOf(gestures); generated = List.copyOf(generated); }
    }

    private final BuildCellRules rules;
    private final BuildInventory inventory;
    private final BlockDigger digger;
    private final Map<Long, BuildTaskRecord.Target> targets = new LinkedHashMap<>();
    private final LongOpenHashSet protectedCells = new LongOpenHashSet();
    private final LongOpenHashSet completed = new LongOpenHashSet();
    private final List<BuildTaskRecord.Target> preflightOrder = new ArrayList<>();
    private final List<CellPlan> plans = new ArrayList<>();
    private final Map<Long, CellPlan> plansByPrimary = new LinkedHashMap<>();
    private final Map<Item, Integer> required = new LinkedHashMap<>();
    private final Map<Item, Integer> missing = new LinkedHashMap<>();
    private final List<Map<String, Object>> unsupported = new ArrayList<>();
    private final List<Map<String, Object>> blocked = new ArrayList<>();
    private final LinkedHashSet<BlockPos> scaffolds = new LinkedHashSet<>();
    private final Map<Long, Integer> defers = new HashMap<>();

    private Phase phase = Phase.PREFLIGHT;
    private boolean preflightDone, providerRegistered, uncertain;
    private int preflightAt, queueAt, clearAt, gestureAt, gestureTries, aimTicks, useCount;
    private int verifyAt, repairPass, scaffoldAt;
    private List<CellPlan> queue = new ArrayList<>();
    private CellPlan cell;
    private List<BlockPos> clearQueue = List.of();
    private BlockPos clearing;
    private List<BuildPlacementGeometry.Gesture> liveGestures = List.of();
    private BuildPlacementGeometry.Gesture gesture;
    private NativeActionReceipt useReceipt, creativeReceipt;
    private BuildTraversabilityVerifier.Result traversabilityResult;
    private int creativeSlot = -1;
    private ItemStack creativeStack = ItemStack.EMPTY;
    private FirstPersonActionGate selection = new FirstPersonActionGate();
    private final LinkedHashSet<Long> verifyFailed = new LinkedHashSet<>();
    private List<BlockPos> scaffoldQueue = List.of();
    private BlockPos scaffold, siteMin, siteMax, failurePos;
    private String failureCode, note = "all native actions re-verified";

    FirstPersonBuildCompanionTask(LocalPlayer player, BuildTaskRecord record) {
        super(player, record);
        rules = new BuildCellRules(player, record);
        inventory = new BuildInventory(player);
        digger = new BlockDigger(player);
        for (BuildTaskRecord.Target target : record.targets) {
            targets.put(target.pos().asLong(), target);
            protectedCells.add(target.pos().asLong());
            preflightOrder.add(target);
        }
        preflightOrder.sort(BuildOrder.BUILD_ORDER);
    }

    /**
     * Add loaded cells that construction navigation may observe but must never clear or replace.
     * Callers use this before the first child tick; the constraint is internal and never enters a
     * public semantic goal.
     */
    protected final void addProtectedNavigationCells(Iterable<BlockPos> cells) {
        if (preflightAt != 0 || preflightDone || providerRegistered || phase != Phase.PREFLIGHT) {
            throw new IllegalStateException(
                    "navigation protection must be supplied before construction starts");
        }
        if (cells == null) return;
        for (BlockPos cell : cells) {
            if (cell != null) protectedCells.add(cell.asLong());
        }
    }

    @Override protected void onStart() {
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
        if (preflightDone) registerProvider();
        drainScaffolds();
        return switch (phase) {
            case PREFLIGHT -> preflightTick(); case SELECT -> selectTick();
            case CLEAR_NAV -> clearNavTick(); case CLEAR -> clearTick();
            case PLACE_NAV -> placeNavTick(); case SELECT_ITEM -> selectItemTick();
            case AIM -> aimTick(); case WAIT_USE -> waitUseTick();
            case CLEAR_CREATIVE -> clearCreativeTick(); case VERIFY -> verifyTick();
            case SCAFFOLD_SELECT -> scaffoldSelectTick(); case SCAFFOLD_NAV -> scaffoldNavTick();
            case SCAFFOLD_BREAK -> scaffoldBreakTick();
        };
    }

    private TaskState preflightTick() {
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
        BlockState live = player.level().getBlockState(target.pos());
        List<BuildPlacementGeometry.GeneratedCell> generated = BuildPlacementGeometry.generatedBy(target);
        if (!target.matches(live)) {
            if (rules.blockedByMode(target)) addBlocked("replacement_policy", target.pos(),
                    "existing block is protected by replacement policy");
            else if (rules.hopeless(target)) addBlocked("unbreakable_or_outside_world", target.pos(),
                    "cell is outside the world or has an unbreakable obstruction");
        }
        inspectGenerated(target, generated);
        boolean done = matches(target, generated);
        List<BuildPlacementGeometry.Gesture> gestures = List.of();
        if (!BuildCellRules.isAirTarget(target) && !done) {
            if (!(target.item() instanceof BlockItem)) addUnsupported("no_native_block_item", target.pos(),
                    "requested state has no block item that can be placed by hand",
                    List.of("placeable_substitute", "leave_for_player", "cancel"));
            else {
                gestures = BuildPlacementGeometry.plan(player, target, targets);
                if (gestures.isEmpty()) addUnsupported("no_exact_first_person_gesture", target.pos(),
                        "no bounded stance and click guarantees the authored state",
                        List.of("plain_full_block_substitute", "relax_state", "other_site", "cancel"));
            }
        }
        CellPlan plan = new CellPlan(target, gestures, generated);
        plans.add(plan); plansByPrimary.put(target.pos().asLong(), plan);
        if (done) markComplete(plan);
        else if (!BuildCellRules.isAirTarget(target) && target.costsMaterial())
            required.merge(target.item(), target.materialCount(), Integer::sum);
    }

    private void inspectGenerated(BuildTaskRecord.Target primary,
                                  List<BuildPlacementGeometry.GeneratedCell> generated) {
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
        resetCell();
        while (queueAt < queue.size()) {
            cell = queue.get(queueAt);
            if (matches(cell.target(), cell.generated())) { markComplete(cell); queueAt++; continue; }
            clearQueue = clearCells(cell); clearAt = 0;
            if (!clearQueue.isEmpty()) { clearing = clearQueue.get(0); phase = Phase.CLEAR_NAV; }
            else if (BuildCellRules.isAirTarget(cell.target())) finishCell();
            else {
                liveGestures = cell.gestures().isEmpty()
                        ? BuildPlacementGeometry.plan(player, cell.target(), targets) : cell.gestures();
                phase = Phase.PLACE_NAV;
            }
            return TaskState.RUNNING;
        }
        beginVerify(); return TaskState.RUNNING;
    }

    private List<BlockPos> clearCells(CellPlan plan) {
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
        if (!player.level().isLoaded(clearing)) {
            failAt(clearing, "cell unloaded after preflight", FailureType.TARGET_LOST,
                    "cell_unloaded", false); return TaskState.FAILED;
        }
        BlockState live = player.level().getBlockState(clearing);
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
        if (!player.level().isLoaded(clearing)) {
            failAt(clearing, "break target unloaded", FailureType.TARGET_LOST,
                    "clear_target_lost", false); return TaskState.FAILED;
        }
        if (player.level().getBlockState(clearing).isAir()) return nextClear();
        return switch (digger.digTargetStep(clearing)) {
            case PROGRESSING -> TaskState.RUNNING;
            case BROKE_TARGET -> { r.brokeOne(); yield nextClear(); }
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
        clearAt++; stopNav();
        if (clearAt < clearQueue.size()) { clearing = clearQueue.get(clearAt); phase = Phase.CLEAR_NAV; }
        else if (BuildCellRules.isAirTarget(cell.target())) finishCell();
        else {
            liveGestures = cell.gestures().isEmpty()
                    ? BuildPlacementGeometry.plan(player, cell.target(), targets) : cell.gestures();
            phase = Phase.PLACE_NAV;
        }
        return TaskState.RUNNING;
    }

    private TaskState placeNavTick() {
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
        if (gestureAt >= liveGestures.size() || gestureTries >= MAX_GESTURES) return deferOrFail();
        gesture = liveGestures.get(gestureAt);
        if (!supportExists(gesture)) { gestureAt++; gestureTries++; return TaskState.RUNNING; }
        if (nav == null) {
            BlockPos stance = gesture.stance();
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(stance), 1.0,
                    () -> PathExecutor.playerFeet(player).equals(stance), this);
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> { stopNav(); phase = Phase.SELECT_ITEM; yield TaskState.RUNNING; }
            case FAILED -> { stopNav(); gestureAt++; gestureTries++; yield TaskState.RUNNING; }
        };
    }

    private boolean supportExists(BuildPlacementGeometry.Gesture g) {
        if (!player.level().isLoaded(g.clicked())) return false;
        BlockState live = player.level().getBlockState(g.clicked());
        if (g.clicked().equals(cell.target().pos())) return !live.isAir();
        return !live.isAir() && !live.canBeReplaced();
    }

    private TaskState selectItemTick() {
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
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
            creativeReceipt = null;
        }
        int slot = inventory.findSlot(cell.target().item(), true);
        if (slot < 0 && player.getAbilities().instabuild) {
            if (creativeSlot < 0) creativeSlot = emptyHotbar();
            if (creativeSlot < 0) {
                failAt(cell.target().pos(), "creative placement needs an empty hotbar slot",
                        FailureType.NO_SPACE, "no_creative_staging_slot", false); return TaskState.FAILED;
            }
            if (creativeStack.isEmpty()) {
                creativeStack = new ItemStack(cell.target().item(), 1);
                LocalPlayerContext ctx = ClientRuntime.requireContext(player);
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
        phase = Phase.AIM; aimTicks = 0; return TaskState.RUNNING;
    }

    private TaskState aimTick() {
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
        InputDriver.halt(player); InputDriver.sneak(player, true); InputDriver.lookAt(player, gesture.point());
        HitResult crosshair = Interaction.nativeRaytrace(player, 4.5);
        if (!(crosshair instanceof BlockHitResult hit) || !placesAt(hit, cell.target().pos())) {
            if (++aimTicks < AIM_TICKS) return TaskState.RUNNING;
            return rejectGesture();
        }
        BlockState before = player.level().getBlockState(cell.target().pos());
        BlockState predicted = BuildPlacementGeometry.predict(
                player, cell.target(), hit, player.getYRot(), player.getXRot());
        if (!cell.target().itemPlace() && (predicted == null
                || (!cell.target().acceptsPlacedState(predicted)
                && !BuildPlacementGeometry.isProgress(cell.target(), before, predicted)))) return rejectGesture();
        Map<Long, BlockState> frozen = freeze(cell);
        LocalPlayerContext ctx = ClientRuntime.requireContext(player);
        useReceipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, hit,
                confirmation(cell, frozen), USE_TIMEOUT);
        useCount++; phase = Phase.WAIT_USE; return TaskState.RUNNING;
    }

    private TaskState rejectGesture() {
        InputDriver.halt(player); stopNav(); selection.reset();
        gesture = null; gestureAt++; gestureTries++; aimTicks = 0; phase = Phase.PLACE_NAV;
        return TaskState.RUNNING;
    }

    private TaskState waitUseTick() {
        InputDriver.halt(player);
        LocalPlayerContext ctx = ClientRuntime.requireContext(player);
        useReceipt = ctx.actions().poll(ctx, useReceipt);
        if (!useReceipt.terminal()) return TaskState.RUNNING;
        NativeActionReceipt.Status status = useReceipt.status();
        String detail = useReceipt.detail(); useReceipt = null;
        if (status != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            failAt(cell.target().pos(), "native placement not safely confirmed: " + detail,
                    status == NativeActionReceipt.Status.CONFIRMED_NOT_APPLIED
                            ? FailureType.NO_SUPPORT : FailureType.UNKNOWN,
                    "placement_" + status.name().toLowerCase(),
                    status == NativeActionReceipt.Status.UNCERTAIN
                            || status == NativeActionReceipt.Status.DIVERGED);
            return TaskState.FAILED;
        }
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
        BlockState live = player.level().getBlockState(cell.target().pos());
        if (useCount < BuildPlacementGeometry.maximumUses(cell.target())
                && BuildPlacementGeometry.isProgress(cell.target(), Blocks.AIR.defaultBlockState(), live)) {
            liveGestures = BuildPlacementGeometry.plan(player, cell.target(), targets);
            gestureAt = 0; gestureTries = 0; gesture = null; selection.reset(); phase = Phase.PLACE_NAV;
            return TaskState.RUNNING;
        }
        failAt(cell.target().pos(), "click changed world but not to the requested verified state",
                FailureType.UNSUPPORTED, "placement_state_mismatch", true); return TaskState.FAILED;
    }

    private void finishPlaced() {
        r.placedOne(); markComplete(cell);
        if (creativeSlot >= 0 && !creativeStack.isEmpty()) {
            LocalPlayerContext ctx = ClientRuntime.requireContext(player);
            creativeReceipt = ctx.actions().creativeSetSlot(
                    ctx, creativeSlot, ItemStack.EMPTY, CREATIVE_TIMEOUT);
            phase = Phase.CLEAR_CREATIVE;
        } else finishCell();
    }

    private TaskState clearCreativeTick() {
        LocalPlayerContext ctx = ClientRuntime.requireContext(player);
        creativeReceipt = ctx.actions().poll(ctx, creativeReceipt);
        if (!creativeReceipt.terminal()) return TaskState.RUNNING;
        if (creativeReceipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            failAt(cell.target().pos(), "temporary creative item was not cleared: "
                            + creativeReceipt.detail(), FailureType.UNKNOWN, "creative_cleanup_failed",
                    creativeReceipt.status() == NativeActionReceipt.Status.UNCERTAIN);
            return TaskState.FAILED;
        }
        creativeReceipt = null; creativeSlot = -1; creativeStack = ItemStack.EMPTY;
        finishCell(); return TaskState.RUNNING;
    }

    private TaskState deferOrFail() {
        int n = defers.merge(cell.target().pos().asLong(), 1, Integer::sum);
        if (n <= MAX_DEFERS && queue.size() > 1) {
            CellPlan delayed = queue.remove(queueAt); queue.add(delayed);
            note = "support-dependent cells were deferred until supports existed";
            phase = Phase.SELECT; return TaskState.RUNNING;
        }
        failAt(cell.target().pos(), "all bounded first-person stances were exhausted",
                FailureType.NO_PATH, "placement_stances_exhausted", false); return TaskState.FAILED;
    }

    private void finishCell() { markComplete(cell); queueAt++; resetCell(); phase = Phase.SELECT; }
    private void resetCell() {
        stopNav(); clearing = null; clearQueue = List.of(); clearAt = 0;
        liveGestures = List.of(); gestureAt = 0; gestureTries = 0; gesture = null;
        aimTicks = 0; useCount = 0; useReceipt = null; selection.reset();
    }

    private void beginVerify() { stopNav(); verifyAt = 0; verifyFailed.clear(); phase = Phase.VERIFY; }

    private TaskState verifyTick() {
        int budget = PREFLIGHT_BUDGET;
        while (verifyAt < r.targets.size() && budget-- > 0) {
            BuildTaskRecord.Target target = r.targets.get(verifyAt);
            if (!player.level().isLoaded(target.pos())) return travelToLoad(target.pos(), false);
            if (target.matches(player.level().getBlockState(target.pos()))) completed.add(target.pos().asLong());
            else {
                completed.remove(target.pos().asLong());
                verifyFailed.add(BuildPlacementGeometry.primaryOf(target).asLong());
            }
            verifyAt++;
        }
        r.completed(completed.size());
        if (verifyAt < r.targets.size()) return TaskState.RUNNING;
        if (!verifyFailed.isEmpty()) {
            if (repairPass++ >= MAX_REPAIRS) {
                failAt(BlockPos.of(verifyFailed.iterator().next()), "final verification found mismatched cells",
                        FailureType.TARGET_LOST, "final_verification_failed", false);
                return TaskState.FAILED;
            }
            List<CellPlan> repairs = new ArrayList<>();
            for (long key : verifyFailed) {
                CellPlan plan = plansByPrimary.get(key);
                if (plan != null && !repairs.contains(plan)) repairs.add(plan);
            }
            if (repairs.size() != verifyFailed.size()) {
                failAt(BlockPos.of(verifyFailed.iterator().next()),
                        "a pre-existing cell changed and has no retained safe gesture",
                        FailureType.TARGET_LOST, "externally_changed_preexisting_cell", false);
                return TaskState.FAILED;
            }
            queue = repairs; queueAt = 0; phase = Phase.SELECT;
            note = "final verification repaired bounded outside changes";
            return TaskState.RUNNING;
        }
        drainScaffolds(); unregisterProvider();
        scaffoldQueue = scaffolds.stream().filter(p -> !targets.containsKey(p.asLong()))
                .sorted(Comparator.comparingInt((BlockPos position) -> position.getY()).reversed()
                        .thenComparingDouble(p -> p.distSqr(player.blockPosition()))).toList();
        scaffoldAt = 0; phase = Phase.SCAFFOLD_SELECT; return TaskState.RUNNING;
    }

    private TaskState scaffoldSelectTick() {
        while (scaffoldAt < scaffoldQueue.size()) {
