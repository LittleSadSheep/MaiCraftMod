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

/** Receipt-driven construction performed only through the local first-person body. */
class FirstPersonBuildCompanionTask extends AbstractCompanionTask<BuildTaskRecord>
        implements BuildPlacementRegistry.Provider, PlayerNav.ContextProvider {
    /** Verified mutations renew this lease; elapsed total time never kills a progressing build. */
    private static final long BUILD_PROGRESS_LEASE_TICKS = 2L * 60L * 20L;
    private static final int PREFLIGHT_BUDGET = 24;
    private static final int USE_TIMEOUT = 40;
    private static final int CREATIVE_TIMEOUT = 30;

    private enum Phase { PREFLIGHT, SELECT, CLEAR_NAV, CLEAR, PLACE_NAV, SELECT_ITEM,
        AIM, WAIT_USE, CLEAR_CREATIVE, VERIFY, SCAFFOLD_SELECT, SCAFFOLD_NAV, SCAFFOLD_BREAK }
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
    private final LongOpenHashSet forbiddenBodyCells = new LongOpenHashSet();
    /** Area cells inherited from earlier semantic steps; unlike blueprint sacred cells, mutable targets here are rejected. */
    private final LongOpenHashSet inheritedProtectedMutationCells = new LongOpenHashSet();
    private final LongOpenHashSet completed = new LongOpenHashSet();
    private final List<BuildTaskRecord.Target> preflightOrder = new ArrayList<>();
    private final List<CellPlan> plans = new ArrayList<>();
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
    private NativeActionReceipt useReceipt, creativeReceipt;
    private BuildTraversabilityVerifier.Result traversabilityResult;
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
        super(player, record);
        rules = new BuildCellRules(player, record);
        inventory = new BuildInventory(player);
        digger = new BlockDigger(player);
        for (BuildTaskRecord.Target target : record.targets) {
            targets.put(target.pos().asLong(), target);
            protectedCells.add(target.pos().asLong());
            preflightOrder.add(target);
        }
        inheritedProtectedMutationCells.addAll(
                NavigationSafetyContext.protectedMutationCells());
        protectedCells.addAll(inheritedProtectedMutationCells);
        forbiddenBodyCells.addAll(NavigationSafetyContext.forbiddenBodyCells());
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
            if (cell != null) {
                protectedCells.add(cell.asLong());
                forbiddenBodyCells.add(cell.asLong());
            }
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
        if (!target.matches(live)
                && inheritedProtectedMutationCells.contains(target.pos().asLong())) {
            addBlocked("inherited_semantic_area_protection", target.pos(),
                    "a previously observed protected semantic area occupies this cell");
        }
        if (!target.matches(live)) {
            if (rules.blockedByMode(target)) addBlocked("replacement_policy", target.pos(),
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
            else {
                if (!BuildPlacementGeometry.hasAnyGesture(player, target, targets))
                    addUnsupported("no_exact_first_person_gesture", target.pos(),
                        "no bounded stance and click guarantees the authored state",
                        List.of("plain_full_block_substitute", "relax_state", "other_site", "cancel"));
            }
        }
        CellPlan plan = new CellPlan(target, generated);
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
                liveGestures = BuildPlacementGeometry.plan(player, cell.target(), targets);
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
            case BROKE_TARGET -> { r.brokeOne(); renewBuildProgress(); yield nextClear(); }
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
            liveGestures = BuildPlacementGeometry.plan(player, cell.target(), targets);
            phase = Phase.PLACE_NAV;
        }
        return TaskState.RUNNING;
    }

    private TaskState placeNavTick() {
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
        while (gestureAt < liveGestures.size()
                && !supportExists(liveGestures.get(gestureAt))) gestureAt++;
        if (gestureAt >= liveGestures.size()) return deferOrFail();
        gesture = liveGestures.get(gestureAt);
        if (nav == null) {
            BlockPos stance = gesture.stance();
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(stance), 1.0,
                    () -> PathExecutor.playerFeet(player).equals(stance), this);
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
        phase = Phase.AIM; aimConvergence.reset(); return TaskState.RUNNING;
    }

    private TaskState aimTick() {
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
        Vec3 eye = player.getEyePosition();
        InputDriver.halt(player); InputDriver.sneak(player, true); InputDriver.lookAt(player, gesture.point());
        if (!aimConvergence.ready(player, gesture.point().subtract(eye))) return TaskState.RUNNING;
        HitResult crosshair = Interaction.nativeRaytrace(player, 4.5);
        if (!(crosshair instanceof BlockHitResult hit) || !placesAt(hit, cell.target().pos()))
            return rejectGesture();
        BlockState before = player.level().getBlockState(cell.target().pos());
        BlockState predicted = BuildPlacementGeometry.predict(
                player, cell.target(), hit, player.getYRot(), player.getXRot());
        if (!cell.target().itemPlace() && (predicted == null
                || (!cell.target().acceptsPlacedState(predicted)
                && !BuildPlacementGeometry.isProgress(cell.target(), before, predicted)))) return rejectGesture();
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
        LocalPlayerContext ctx = ClientRuntime.requireContext(player);
        useReceipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, hit,
                confirmation(cell, frozen), USE_TIMEOUT);
        phase = Phase.WAIT_USE; return TaskState.RUNNING;
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
        BlockPos occupiedFeet = PathExecutor.playerFeet(player);
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
        InputDriver.halt(player);
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
        if (matches(cell.target(), cell.generated())) { finishPlaced(); return TaskState.RUNNING; }
        BlockState live = player.level().getBlockState(cell.target().pos());
        if (useCount < BuildPlacementGeometry.maximumUses(cell.target())
                && BuildPlacementGeometry.isProgress(cell.target(), Blocks.AIR.defaultBlockState(), live)) {
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
        r.placedOne(); renewBuildProgress(); markComplete(cell);
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
        long key = cell.target().pos().asLong();
        PlacementAttemptSignature signature = placementAttemptSignature();
        if (!exhaustedPlacementStates.computeIfAbsent(key, ignored -> new HashSet<>()).add(signature)) {
            failAt(cell.target().pos(),
                    "the complete finite stance set reached the same body and world state again without progress",
                    FailureType.NO_PATH, "placement_no_progress", false);
            return TaskState.FAILED;
        }

        List<CellPlan> pending = pendingOtherCells(key);
        if (!pending.isEmpty()) {
            pending.add(cell);
            queue = pending;
            queueAt = 0;
            note = "support-dependent cells were deferred until supports existed";
            phase = Phase.SELECT; return TaskState.RUNNING;
        }
        failAt(cell.target().pos(), "the complete finite first-person stance set was exhausted",
                FailureType.NO_PATH, "placement_stances_exhausted", false); return TaskState.FAILED;
    }

    private List<CellPlan> pendingOtherCells(long currentKey) {
        List<CellPlan> pending = new ArrayList<>();
        for (CellPlan candidate : plans) {
            if (candidate.target().pos().asLong() != currentKey
                    && !matches(candidate.target(), candidate.generated())) pending.add(candidate);
        }
        return pending;
    }

    private PlacementAttemptSignature placementAttemptSignature() {
        BlockPos feet = PathExecutor.playerFeet(player);
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
        stopNav(); clearing = null; clearQueue = List.of(); clearAt = 0;
        liveGestures = List.of(); gestureAt = 0; gesture = null;
        useCount = 0; useReceipt = null; selection.reset(); aimConvergence.reset();
    }

    private void beginVerify() {
        stopNav(); verifyAt = 0; verifyFailed.clear(); verifyFailureStates.clear();
        phase = Phase.VERIFY;
    }

    private TaskState verifyTick() {
        int budget = PREFLIGHT_BUDGET;
        while (verifyAt < r.targets.size() && budget-- > 0) {
            BuildTaskRecord.Target target = r.targets.get(verifyAt);
            if (!player.level().isLoaded(target.pos())) return travelToLoad(target.pos(), false);
            BlockState state = player.level().getBlockState(target.pos());
            if (target.matches(state)) completed.add(target.pos().asLong());
            else {
                completed.remove(target.pos().asLong());
                verifyFailed.add(BuildPlacementGeometry.primaryOf(target).asLong());
                verifyFailureStates.add(new ObservedCell(target.pos().asLong(), state));
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
        scaffoldQueue = scaffolds.stream().filter(p -> !targets.containsKey(p.asLong()))
                .sorted(Comparator.comparingInt((BlockPos position) -> position.getY()).reversed()
                        .thenComparingDouble(p -> p.distSqr(player.blockPosition()))).toList();
        scaffoldAt = 0; phase = Phase.SCAFFOLD_SELECT; return TaskState.RUNNING;
    }

    private VerificationFailureSignature verificationFailureSignature() {
        return new VerificationFailureSignature(verifyFailureStates);
    }

    private TaskState scaffoldSelectTick() {
        while (scaffoldAt < scaffoldQueue.size()) {
            scaffold = scaffoldQueue.get(scaffoldAt);
            if (!player.level().isLoaded(scaffold)) {
                failAt(scaffold, "path scaffold unloaded before cleanup", FailureType.TARGET_LOST,
                        "scaffold_unloaded", false); return TaskState.FAILED;
            }
            if (player.level().getBlockState(scaffold).isAir()) { scaffoldAt++; continue; }
            phase = Phase.SCAFFOLD_NAV; return TaskState.RUNNING;
        }
        r.completed(countMatching());
        if (r.completed() != r.targets.size()) { beginVerify(); return TaskState.RUNNING; }
        if (r.traversabilityContract() != null) {
            traversabilityResult = BuildTraversabilityVerifier.verify(
                    player.clientLevel, r.traversabilityContract());
            if (!traversabilityResult.valid()) {
                failAt(traversabilityResult.position(), traversabilityResult.message(),
                        FailureType.NO_PATH, traversabilityResult.code(), false);
                return TaskState.FAILED;
            }
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
        if (player.level().getBlockState(scaffold).isAir()) {
            scaffoldAt++; phase = Phase.SCAFFOLD_SELECT; return TaskState.RUNNING;
        }
        return switch (digger.digTargetStep(scaffold)) {
            case PROGRESSING -> TaskState.RUNNING;
            case BROKE_TARGET -> {
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
        boolean added = false;
        for (BlockPos pos : BuildPlacementRegistry.drainScaffold(player))
            if (!targets.containsKey(pos.asLong())) added |= scaffolds.add(pos.immutable());
        if (added) renewBuildProgress();
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
    private LongSet union(LongSet other) {
        if (other == null || other.isEmpty()) return protectedCells;
        LongOpenHashSet out = new LongOpenHashSet(protectedCells); out.addAll(other); return out;
    }
    private LongSet unionForbidden(LongSet other) {
        if (other == null || other.isEmpty()) return forbiddenBodyCells;
        LongOpenHashSet out = new LongOpenHashSet(forbiddenBodyCells);
        out.addAll(other);
        return out;
    }

    @Override public void stop(LocalPlayer companion, StopReason why) {
        if (digger.current() != null) digger.cancel();
        drainScaffolds(); unregisterProvider(); super.stop(companion, why); InputDriver.halt(player);
    }
    @Override protected void cleanup() {
        if (digger.current() != null) digger.cancel();
        drainScaffolds(); unregisterProvider(); InputDriver.halt(player); selection.reset();
        aimConvergence.reset();
        bestEffortCreativeCleanup(); super.cleanup();
    }
    private void bestEffortCreativeCleanup() {
        if (creativeSlot < 0 || creativeStack.isEmpty() || !player.getAbilities().instabuild) return;
        ItemStack live = player.getInventory().getItem(creativeSlot);
        if (!ItemStack.isSameItemSameComponents(live, creativeStack)) return;
        try {
            LocalPlayerContext ctx = ClientRuntime.requireContext(player);
            if (creativeReceipt == null || creativeReceipt.terminal())
                ctx.actions().creativeSetSlot(ctx, creativeSlot, ItemStack.EMPTY, CREATIVE_TIMEOUT);
        } catch (RuntimeException ignored) { }
    }

    @Override
    protected Map<String, Object> resultData() {
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
            data.put("world_change_uncertain", true);
            data.put("safe_to_retry_without_observation", false);
        }

        List<Map<String, Object>> remainingScaffolds = scaffolds.stream()
                .filter(pos -> player.level().isLoaded(pos)
                        && !player.level().getBlockState(pos).isAir())
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
