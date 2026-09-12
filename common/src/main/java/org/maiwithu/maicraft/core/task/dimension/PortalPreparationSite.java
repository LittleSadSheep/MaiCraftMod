// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;

/** Live site constraints shared by surveying, supply, construction and the final activation click. */
record PortalPreparationSite(NetherPortalFrame nether, EndPortalFrame end) {
    PortalPreparationSite {
        if ((nether == null) == (end == null)) throw new IllegalArgumentException("exactly one portal type is required");
    }

    BlockPos anchor() { return nether == null ? end.center() : nether.origin(); }
    List<BlockPos> frames() { return nether == null ? List.copyOf(end.frames().keySet()) : nether.frame(); }
    List<BlockPos> interior() { return nether == null ? end.interior() : nether.interior(); }
    List<BlockPos> footprint() {
        var cells = new ArrayList<>(frames()); cells.addAll(interior()); return List.copyOf(cells);
    }
    List<BlockPos> forbiddenBody() {
        if (nether != null) return interior();
        var cells = new ArrayList<BlockPos>();
        for (var pos : interior()) { cells.add(pos.below()); cells.add(pos); cells.add(pos.above()); }
        return List.copyOf(cells);
    }

    static BlockState read(ClientLevel world, BlockPos pos) {
        return pos.getY() >= world.getMinBuildHeight() && pos.getY() < world.getMaxBuildHeight()
                && world.isLoaded(pos) ? world.getBlockState(pos) : null;
    }
    boolean active(ClientLevel world) {
        return nether == null ? end.active(p -> read(world, p)) : nether.active(p -> read(world, p));
    }
    boolean ready(ClientLevel world) {
        return valid(world) && (nether == null || nether.ready(p -> read(world, p)));
    }
    boolean valid(ClientLevel world) {
        if (footprint().stream().anyMatch(p -> NavigationSafetyContext.protectsUse(p)
                || NavigationSafetyContext.protectsMutation(p))) return false;
        if (end != null) return end.intact(p -> read(world, p)) && end.clearInterior(p -> read(world, p));
        return frames().stream().allMatch(p -> {
            BlockState state = read(world, p);
            return state != null && (state.isAir() || state.is(Blocks.OBSIDIAN));
        }) && interior().stream().allMatch(p -> NetherPortalFrame.empty(read(world, p)));
    }

    /** A new raised frame needs solid ground and a clear approach on both sides; no clearing is inferred. */
    boolean newSite(ClientLevel world) {
        if (nether == null || !valid(world) || interior().stream().anyMatch(p -> !air(world, p))) return false;
        Direction normal = nether.axis() == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
        for (int x = -1; x <= nether.width(); x++) {
            for (int side = -1; side <= 1; side++) {
                BlockPos support = nether.cell(x, -2).relative(normal, side);
                BlockState ground = read(world, support);
                if (ground == null || !ground.getFluidState().isEmpty()
                        || !ground.isFaceSturdy(world, support, Direction.UP)
                        || ground.is(Blocks.MAGMA_BLOCK) || ground.is(Blocks.CAMPFIRE)
                        || ground.is(Blocks.SOUL_CAMPFIRE) || NavigationSafetyContext.forbidsBody(support.above())) return false;
                if (side != 0 && (!air(world, support.above()) || !air(world, support.above(2)))) return false;
            }
        }
        return true;
    }
    private static boolean air(ClientLevel world, BlockPos pos) {
        BlockState state = read(world, pos); return state != null && state.isAir();
    }

    List<BuildTaskRecord.Target> missingBlocks(ClientLevel world) {
        return nether == null ? List.of() : nether.missing(p -> read(world, p)).stream().map(p ->
                new BuildTaskRecord.Target(Blocks.OBSIDIAN, Items.OBSIDIAN, p, "Nether portal frame", null, null, null)).toList();
    }

    BuildTaskRecord construction(ClientLevel world, String callId, long deadline) {
        var record = new BuildTaskRecord(callId, deadline, missingBlocks(world), false);
        record.materialSupplyProtection(footprint());
        // Navigation preserves the whole frame; only the explicit build target may place missing obsidian.
        record.executionGuards(frames(), player -> player.level() == world && valid(world),
                (player, pos) -> player.level() == world && valid(world)
                        && (frames().contains(pos) ? air(world, pos) : !interior().contains(pos)),
                (player, pos) -> {});
        return record;
    }
}
