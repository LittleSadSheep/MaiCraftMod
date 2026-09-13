// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;

/** Real view samples and existing dry stances, including ground below elevated shaft posts. */
final class ChainConveyorInteraction {
    private ChainConveyorInteraction() {}
    static Vec3 aim(LocalPlayer player, BlockPos target, Vec3 eye) {
        if (!player.level().isLoaded(target)) return null;
        var shape = player.level().getBlockState(target).getShape(player.level(), target, net.minecraft.world.phys.shapes.CollisionContext.of(player));
        for (var local : shape.toAabbs()) {
            var box = local.move(target); Vec3 center = box.getCenter();
            for (Direction side : Direction.values()) {
                double coordinate = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(side.getAxis()) - .001 : box.min(side.getAxis()) + .001;
                Vec3 point = switch (side.getAxis()) {
                    case X -> new Vec3(coordinate, center.y, center.z);
                    case Y -> new Vec3(center.x, coordinate, center.z);
                    case Z -> new Vec3(center.x, center.y, coordinate);
                };
                var hit = trace(player, eye, point.subtract(eye));
                if (hit != null && hit.getBlockPos().equals(target)) return point;
            }
        }
        return null;
    }
    static BlockHitResult trace(LocalPlayer player, Vec3 eye, Vec3 direction) {
        if (direction.lengthSqr() < 1e-8) return null;
        Vec3 end = eye.add(direction.normalize().scale(Math.min(4.5, player.blockInteractionRange())));
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(Math.min(eye.x, end.x), Math.min(eye.y, end.y), Math.min(eye.z, end.z)),
                BlockPos.containing(Math.max(eye.x, end.x), Math.max(eye.y, end.y), Math.max(eye.z, end.z))))
            if (!player.level().isLoaded(cell)) return null;
        var hit = player.level().clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }
    static BlockPos stance(LocalPlayer player, BlockPos target) {
        var candidates = new ArrayList<BlockPos>();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -4; y <= 1; y++) candidates.add(target.offset(x, y, z));
        candidates.sort(Comparator.comparingDouble(at -> Vec3.atBottomCenterOf(at).distanceToSqr(player.position())));
        for (BlockPos feet : candidates) {
            if (!player.level().isLoaded(feet) || !player.level().isLoaded(feet.above()) || !player.level().isLoaded(feet.below())
                    || NavigationSafetyContext.forbidsBody(feet) || NavigationSafetyContext.forbidsBody(feet.above())
                    || !BlockHelper.isDryStandable(player.level(), feet) || BlockHelper.isHazard(player.level(), feet.below())) continue;
            Vec3 eye = Vec3.atBottomCenterOf(feet).add(0, player.getEyeHeight(net.minecraft.world.entity.Pose.STANDING), 0);
            if (aim(player, target, eye) != null) return feet;
        }
        throw new IllegalArgumentException("chain_conveyor_no_visible_native_stance");
    }
}
