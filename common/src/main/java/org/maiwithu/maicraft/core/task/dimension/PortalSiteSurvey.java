// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.scan.TargetIndex;

/** Bounded observation: intact frames first, compact repairs second, a nearby new site last. */
final class PortalSiteSurvey implements AutoCloseable {
    private final ClientLevel world;
    private final BlockPos origin;
    private final int radius;
    private final boolean end;
    private final List<Block> targets;
    private final Set<BlockPos> examined = new HashSet<>();
    private PortalPreparationSite repair;
    private int newSiteCursor;
    private boolean complete;

    PortalSiteSurvey(ClientLevel world, BlockPos origin, int radius, boolean end) {
        this.world = world; this.origin = origin.immutable(); this.radius = radius; this.end = end;
        targets = List.of(end ? Blocks.END_PORTAL_FRAME : Blocks.OBSIDIAN);
        TargetIndex.register(world, targets);
    }

    PortalPreparationSite tick(boolean mayBuild) {
        if (complete) return null;
        var observed = TargetIndex.query(world, origin, targets, 128, Math.max(1, (radius + 15) / 16), 8);
        int budget = 4;
        for (var seed : observed.hits()) {
            if (examined.contains(seed) || seed.distSqr(origin) > (double) radius * radius) continue;
            if (budget-- == 0) return null;
            examined.add(seed);
            if (end) {
                var ring = EndPortalFrame.observe(p -> PortalPreparationSite.read(world, p), seed);
                var site = ring == null ? null : new PortalPreparationSite(null, ring);
                if (site != null && site.valid(world)) return site;
                continue;
            }
            for (var axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
                for (var direction : Direction.values()) {
                    var frame = NetherPortalFrame.observe(p -> PortalPreparationSite.read(world, p), seed.relative(direction), axis);
                    var site = frame == null ? null : new PortalPreparationSite(frame, null);
                    if (site != null && site.valid(world)) return site;
                }
                if (mayBuild) considerRepair(seed, axis);
            }
        }
        if (!observed.complete()) return null;
        if (repair != null && repair.valid(world)) return repair;
        if (end || !mayBuild) { complete = true; return null; }
        // At most 32 small site checks per tick, within eight blocks horizontally and four vertically.
        for (int n = 0; n < 32 && newSiteCursor < 17 * 17 * 9 * 2; n++, newSiteCursor++) {
            int index = newSiteCursor / 2;
            int dy = index % 9 - 4; index /= 9;
            int dx = index % 17 - 8, dz = index / 17 - 8;
            var frame = new NetherPortalFrame(origin.offset(dx, dy + 1, dz),
                    newSiteCursor % 2 == 0 ? Direction.Axis.X : Direction.Axis.Z, 2, 3);
            var site = new PortalPreparationSite(frame, null);
            if (site.newSite(world)) { newSiteCursor++; return site; }
        }
        if (newSiteCursor >= 17 * 17 * 9 * 2) complete = true;
        return null;
    }

    private void considerRepair(BlockPos seed, Direction.Axis axis) {
        var template = new NetherPortalFrame(BlockPos.ZERO, axis, 2, 3);
        for (var offset : template.frame()) {
            var frame = new NetherPortalFrame(seed.subtract(offset), axis, 2, 3);
            var site = new PortalPreparationSite(frame, null);
            if (!site.valid(world)) continue;
            int missing = site.missingBlocks(world).size();
            if (missing > 0 && missing <= 4
                    && (repair == null || missing < repair.missingBlocks(world).size())) repair = site;
        }
    }
    boolean complete() { return complete; }
    @Override public void close() { TargetIndex.unregister(world, targets); }
}
