// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** Choose a real exterior stance with a visible top face, before ignition or eye insertion. */
final class PortalApproach {
    private PortalApproach() {}

    static BlockPos find(LocalPlayer player, PortalPreparationSite site, BlockPos target, Vec3 aim, Set<BlockPos> excluded) {
        var world = player.clientLevel;
        var forbidden = Set.copyOf(site.forbiddenBody());
        BlockPos best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -2; y <= 2; y++) {
            BlockPos feet = target.offset(x, y, z);
            if (excluded.contains(feet) || forbidden.contains(feet) || forbidden.contains(feet.above())
                    || NavigationSafetyContext.forbidsBody(feet) || NavigationSafetyContext.forbidsBody(feet.above())) continue;
            var floor = PortalPreparationSite.read(world, feet.below());
            var lower = PortalPreparationSite.read(world, feet);
            var upper = PortalPreparationSite.read(world, feet.above());
            if (floor == null || lower == null || upper == null || !lower.isAir() || !upper.isAir()
                    || !floor.getFluidState().isEmpty() || floor.is(Blocks.MAGMA_BLOCK)
                    || floor.is(Blocks.CAMPFIRE) || floor.is(Blocks.SOUL_CAMPFIRE)
                    || !floor.isFaceSturdy(world, feet.below(), Direction.UP)) continue;
            Vec3 eye = Vec3.atBottomCenterOf(feet).add(0, player.getEyeHeight(), 0);
            if (eye.distanceTo(aim) > player.blockInteractionRange() - .2) continue;
            Vec3 end = aim.add(aim.subtract(eye).normalize().scale(.02));
            boolean loadedRay = true;
            for (var pos : BlockPos.betweenClosed(BlockPos.containing(eye), BlockPos.containing(end))) {
                if (!world.isLoaded(pos)) { loadedRay = false; break; }
            }
            if (!loadedRay) continue;
            var hit = world.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target) || hit.getDirection() != Direction.UP) continue;
            double distance = feet.distSqr(player.blockPosition());
            if (distance < nearest) { nearest = distance; best = feet.immutable(); }
        }
        return best;
    }
}
