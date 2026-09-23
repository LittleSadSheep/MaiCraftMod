// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** 原生装配从真实轮廓采样可点击面；被墙遮住时换站位，不隔墙发送方块使用。 */
public final class AssemblyBlockAim {
    private AssemblyBlockAim() {}
    public static Vec3 point(LocalPlayer player, BlockPos target, Vec3 eye) {
        if (!player.level().isLoaded(target)) return null;
        var shape = player.level().getBlockState(target).getShape(player.level(), target, CollisionContext.of(player));
        for (var box : shape.toAabbs()) for (Direction face : Direction.values()) {
            var at = box.move(target); Vec3 center = at.getCenter();
            double edge = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? at.max(face.getAxis()) - .001 : at.min(face.getAxis()) + .001;
            Vec3 point = switch (face.getAxis()) {
                case X -> new Vec3(edge, center.y, center.z);
                case Y -> new Vec3(center.x, edge, center.z);
                case Z -> new Vec3(center.x, center.y, edge);
            };
            var hit = trace(player, eye, point.subtract(eye));
            if (hit != null && hit.getBlockPos().equals(target)) return point;
        }
        return null;
    }
    public static BlockHitResult trace(LocalPlayer player, Vec3 eye, Vec3 direction) {
        if (direction.lengthSqr() < 1e-8) return null;
        Vec3 end = eye.add(direction.normalize().scale(Math.min(4.5, player.blockInteractionRange())));
        // 未加载的射线路径不能被当作空气，避免跨未知区块向后方目标发出使用。
        for (BlockPos at : BlockPos.betweenClosed(BlockPos.containing(Math.min(eye.x, end.x), Math.min(eye.y, end.y), Math.min(eye.z, end.z)),
                BlockPos.containing(Math.max(eye.x, end.x), Math.max(eye.y, end.y), Math.max(eye.z, end.z))))
            if (!player.level().isLoaded(at)) return null;
        var hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }
}
