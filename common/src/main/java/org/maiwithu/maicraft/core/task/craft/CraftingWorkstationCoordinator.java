// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.craft;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CraftingTableBlock;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.scan.TargetIndex;

/**
 * Resolves the physical 3x3 crafting prerequisite without exposing body details to the LLM.
 *
 * <p>The coordinator never invents a workstation and never edits a cell directly. It selects one
 * of three bounded first-person routes: use a reachable table, approach an existing loaded table,
 * or place a carried table on a verified supported cell. When no table is carried, the planning
 * snapshot exposes the workstation item as a semantic prerequisite so the acquisition coordinator
 * can obtain and verify it before this physical coordinator resumes. The owning crafting task
 * executes the returned move/build child and feeds failures back through {@link #reject(Directive)}.</p>
 */
public final class CraftingWorkstationCoordinator {
    private static final double REACH = 4.5D;
    private static final int SEARCH_HORIZONTAL = 32;
    private static final int SEARCH_VERTICAL = 16;
    private static final int PLACEMENT_RADIUS = 5;

    public enum Action {
        READY,
        MOVE_TO_EXISTING,
        PLACE_CARRIED,
        UNAVAILABLE
    }

    public record Directive(
            Action action, BlockPos position, Block workstationBlock, String detail) {
        public Directive {
            position = position == null ? null : position.immutable();
        }

        public Directive(Action action, BlockPos position, String detail) {
            this(action, position, null, detail);
        }
    }

    public record PlanningSnapshot(
            CraftPlanCost.Surface surface,
            BlockPos station,
            List<ResourceLocation> prerequisiteItemIds,
            String detail) {
        public PlanningSnapshot {
            station = station == null ? null : station.immutable();
            prerequisiteItemIds = prerequisiteItemIds == null
                    ? List.of() : List.copyOf(prerequisiteItemIds);
        }

        public PlanningSnapshot(
                CraftPlanCost.Surface surface, BlockPos station, String detail) {
            this(surface, station, List.of(), detail);
        }
    }

    private final Set<Long> rejectedStations = new LinkedHashSet<>();
    private final Set<Long> rejectedSites = new LinkedHashSet<>();
    private ClientLevel indexedLevel;
    private List<Block> indexedTables = List.of();

    /** Keep the shared sparse block index warm for the lifetime of one physical craft task. */
    public void start(LocalPlayer player) {
        ClientLevel level = player.clientLevel;
        if (indexedLevel == level) return;
        close();
        indexedTables = craftingTableBlocks();
        TargetIndex.register(level, indexedTables);
        indexedLevel = level;
    }

    public void close() {
        if (indexedLevel == null) return;
        TargetIndex.unregister(indexedLevel, indexedTables);
        indexedLevel = null;
        indexedTables = List.of();
    }

    /** Read-only feasibility snapshot shared by recipe ranking. */
    public static PlanningSnapshot inspect(LocalPlayer player) {
        List<Block> tables = craftingTableBlocks();
        TargetIndex.register(player.clientLevel, tables);
        BlockPos station;
        try {
            station = nearestIndexedTable(player, Set.of(), tables);
        } finally {
            TargetIndex.unregister(player.clientLevel, tables);
        }
        if (station != null) {
            if (withinReach(player, station)) {
                return new PlanningSnapshot(
                        CraftPlanCost.Surface.READY, station,
                        "a loaded crafting table is within first-person reach");
            }
            return new PlanningSnapshot(
                    CraftPlanCost.Surface.PREPARABLE, station,
                    "an existing loaded crafting table can be approached");
        }
        if (carriedTable(player) != null) {
            BlockPos site = placementSite(player, Set.of());
            if (site != null) {
                return new PlanningSnapshot(
                        CraftPlanCost.Surface.PREPARABLE, null,
                        "a carried crafting table can be placed on a verified nearby support");
            }
            return new PlanningSnapshot(
                    CraftPlanCost.Surface.UNAVAILABLE, null,
                    "a crafting table is carried, but no verified nearby placement cell is available");
        }
        BlockPos site = placementSite(player, Set.of());
        if (site == null) {
            return new PlanningSnapshot(
                    CraftPlanCost.Surface.UNAVAILABLE, null,
                    "no table is available and no verified nearby placement cell could receive one");
        }
        return new PlanningSnapshot(
                CraftPlanCost.Surface.PREREQUISITE, null,
                craftingTableItemIds(),
                "no loaded or carried crafting table is available; semantic acquisition may "
                        + "materialize the required workstation item before this recipe resumes");
    }

    /** Resolve the next bounded physical step from fresh client facts. */
    public Directive next(LocalPlayer player, BlockPos preferredStation) {
        start(player);
        if (usableTable(player, preferredStation)
                && !rejectedStations.contains(preferredStation.asLong())) {
            Block preferred = player.level().getBlockState(preferredStation).getBlock();
            return withinReach(player, preferredStation)
                    ? new Directive(Action.READY, preferredStation, preferred,
                            "the selected crafting table is within reach")
                    : new Directive(Action.MOVE_TO_EXISTING, preferredStation, preferred,
                            "approach the selected loaded crafting table");
        }

        BlockPos existing = nearestIndexedTable(player, rejectedStations, indexedTables);
        if (existing != null) {
            Block existingBlock = player.level().getBlockState(existing).getBlock();
            return withinReach(player, existing)
                    ? new Directive(Action.READY, existing, existingBlock,
                            "a loaded crafting table is within reach")
                    : new Directive(Action.MOVE_TO_EXISTING, existing, existingBlock,
                            "approach the nearest loaded crafting table");
        }

        Block carried = carriedTable(player);
        if (carried == null) {
            return new Directive(Action.UNAVAILABLE, null,
                    "no loaded or carried crafting table is available; semantic acquisition must "
                            + "materialize one before physical placement");
        }
        BlockPos site = placementSite(player, rejectedSites);
        if (site == null) {
            return new Directive(Action.UNAVAILABLE, null,
                    "no verified nearby cell can receive the carried crafting table");
        }
        return new Directive(Action.PLACE_CARRIED, site, carried,
                "place the carried crafting table through first-person building");
    }

    /** Reject only the failed physical route; another table or placement site may still work. */
    public void reject(Directive directive) {
        if (directive == null) return;
        switch (directive.action()) {
            case READY, MOVE_TO_EXISTING -> {
                if (directive.position() != null) {
                    rejectedStations.add(directive.position().asLong());
                }
            }
            case PLACE_CARRIED -> {
                if (directive.position() != null) {
                    rejectedSites.add(directive.position().asLong());
                }
            }
            case UNAVAILABLE -> { }
        }
    }

    public static boolean withinReach(LocalPlayer player, BlockPos pos) {
        if (pos == null) return false;
        Vec3 eye = player.getEyePosition();
        double nearestX = Math.clamp(eye.x, pos.getX(), pos.getX() + 1.0D);
        double nearestY = Math.clamp(eye.y, pos.getY(), pos.getY() + 1.0D);
        double nearestZ = Math.clamp(eye.z, pos.getZ(), pos.getZ() + 1.0D);
        return eye.distanceToSqr(nearestX, nearestY, nearestZ) <= REACH * REACH;
    }

    public static boolean usableTable(LocalPlayer player, BlockPos pos) {
        return pos != null && player.level().isLoaded(pos)
                && player.level().getBlockState(pos).getBlock() instanceof CraftingTableBlock;
    }

    private static Block carriedTable(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int limit = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() instanceof CraftingTableBlock) {
                return blockItem.getBlock();
            }
        }
        return null;
    }

    private static BlockPos nearestIndexedTable(
            LocalPlayer player, Set<Long> rejected, List<Block> tables) {
        BlockPos origin = player.blockPosition();
        int wantedCandidates = rejected.size() + 1;
        TargetIndex.Result result;
        do {
            result = TargetIndex.query(
                    player.clientLevel, origin, tables,
                    wantedCandidates, 2, 384);
        } while (!result.complete());
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : result.hits()) {
            int dx = Math.abs(candidate.getX() - origin.getX());
            int dy = Math.abs(candidate.getY() - origin.getY());
            int dz = Math.abs(candidate.getZ() - origin.getZ());
            if (dx > SEARCH_HORIZONTAL || dy > SEARCH_VERTICAL || dz > SEARCH_HORIZONTAL
                    || rejected.contains(candidate.asLong())
                    || !usableTable(player, candidate)) {
                continue;
            }
            double distance = origin.distSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.immutable();
            }
        }
        return best;
    }

    private static List<Block> craftingTableBlocks() {
        List<Block> result = BuiltInRegistries.BLOCK.stream()
                .filter(block -> block instanceof CraftingTableBlock)
                .toList();
        return result.isEmpty() ? List.of(Blocks.CRAFTING_TABLE) : result;
    }

    private static List<ResourceLocation> craftingTableItemIds() {
        return craftingTableBlocks().stream()
                .map(Block::asItem)
                .filter(item -> item != Items.AIR)
                .filter(item -> BuiltInRegistries.ITEM.getKey(item) != null)
                .map(BuiltInRegistries.ITEM::getKey)
                .distinct()
                .toList();
    }

    private static BlockPos placementSite(LocalPlayer player, Set<Long> rejected) {
        BlockPos origin = player.blockPosition();
        for (int radius = 1; radius <= PLACEMENT_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos cell = origin.offset(dx, dy, dz);
                        if (!rejected.contains(cell.asLong()) && validSite(player, cell)) return cell;
                    }
                    int surfaceY = ClientSurfaceHeight.motionBlockingNoLeaves(
                            player.clientLevel,
                            origin.getX() + dx, origin.getZ() + dz);
                    BlockPos surface = new BlockPos(
                            origin.getX() + dx, surfaceY, origin.getZ() + dz);
                    if (!rejected.contains(surface.asLong()) && validSite(player, surface)) {
                        return surface;
                    }
                }
            }
        }
        return null;
    }

    private static boolean validSite(LocalPlayer player, BlockPos cell) {
        if (!player.level().isLoaded(cell)
                || !player.level().getBlockState(cell).canBeReplaced()
                || player.getBoundingBox().intersects(new AABB(cell))) {
            return false;
