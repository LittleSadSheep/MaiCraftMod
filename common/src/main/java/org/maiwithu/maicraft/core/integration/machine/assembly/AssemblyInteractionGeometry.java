// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** Finds existing crouching interaction stances with the same outline ray used before native use. */
final class AssemblyInteractionGeometry {
    private AssemblyInteractionGeometry() {}

    static BlockHitResult hit(LocalPlayer player, Vec3 eye, Vec3 aim) {
        Vec3 delta = aim.subtract(eye);
        if (delta.lengthSqr() < 1e-8) return null;
        double reach = Math.min(4.5, player.blockInteractionRange());
        BlockHitResult hit = player.level().clip(new ClipContext(eye, eye.add(delta.normalize().scale(reach)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    static BlockPos nearestStand(LocalPlayer player, BlockPos target, Set<Long> excluded, Function<Vec3, Vec3> aimFrom) {
        BlockPos best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -2; dy <= 1; dy++) {
            BlockPos feet = target.offset(dx, dy, dz);
            if (excluded.contains(feet.asLong()) || !standable(player, feet)) continue;
            double candidateDistance = Vec3.atBottomCenterOf(feet).distanceToSqr(player.position());
            if (candidateDistance >= distance) continue;
            Vec3 eye = Vec3.atBottomCenterOf(feet).add(0, player.getEyeHeight(Pose.CROUCHING), 0);
            if (aimFrom.apply(eye) != null) { best = feet; distance = candidateDistance; }
        }
        return best;
    }

    private static boolean standable(LocalPlayer player, BlockPos feet) {
        var level = player.level();
        if (NavigationSafetyContext.forbidsBody(feet) || NavigationSafetyContext.forbidsBody(feet.above())
                || !level.isLoaded(feet) || !level.isLoaded(feet.above()) || !level.isLoaded(feet.below())
                || !level.getWorldBorder().isWithinBounds(feet) || level.isOutsideBuildHeight(feet.above())) return false;
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()
                || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) return false;
        return level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
    }
}
