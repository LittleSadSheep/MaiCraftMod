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
