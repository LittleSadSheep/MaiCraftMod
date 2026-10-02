// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.scan;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 探索只接受从角色眼睛能直接看见的现场目标；墙、关门和未加载地形都不能被当作透明。 */
public final class ObservationVisibility {
    private ObservationVisibility() {}

    public static boolean block(LocalPlayer player, BlockPos at) {
        if (player == null || at == null || !player.level().isLoaded(at)) return false;
        // 小箱子、台阶和薄门按实际外形取观察点；任一外露面可见即可发现，无须正盯着格心。
        var shape = player.level().getBlockState(at).getShape(player.level(), at);
        AABB box = shape.isEmpty() ? new AABB(at) : shape.bounds().move(at);
        Vec3 center = box.getCenter();
        if (ray(player, center, at)) return true;
        for (Direction face : Direction.values()) {
            Vec3 point = center.add(face.getStepX() * Math.max(0, box.getXsize() / 2 - .001),
                    face.getStepY() * Math.max(0, box.getYsize() / 2 - .001),
                    face.getStepZ() * Math.max(0, box.getZsize() / 2 - .001));
            if (ray(player, point, at)) return true;
        }
        return false;
    }

    public static boolean entity(LocalPlayer player, Entity target) {
        // 生物在墙后时不能先交付其身份再让角色追过去；头部或身体露出才算观察到。
        return target != null && !target.isRemoved()
                && (point(player, target.getEyePosition()) || point(player, target.getBoundingBox().getCenter()));
    }

    public static boolean point(LocalPlayer player, Vec3 point) {
        return player != null && point != null && ray(player, point, null);
    }

    private static boolean ray(LocalPlayer player, Vec3 end, BlockPos targetBlock) {
        Vec3 eye = player.getEyePosition();
        // 原生射线穿过的每一格都须已加载；不能越过未知区块看到另一侧目标。
        boolean loaded = BlockGetter.traverseBlocks(eye, end, player.level(),
                (level, cell) -> level.isLoaded(cell) ? null : Boolean.FALSE, level -> Boolean.TRUE);
        if (!loaded) return false;
        var hit = player.level().clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS
                || targetBlock != null && targetBlock.equals(hit.getBlockPos());
    }
}
