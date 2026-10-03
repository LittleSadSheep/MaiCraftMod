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

/**
 * 为 AE2 部件安装和 Mekanism 配置寻找已有站位，并预先检查潜行时能不能点到目标。
 */
public final class AssemblyInteractionGeometry {
    private AssemblyInteractionGeometry() {}
    /** 候选事实随失败回执交付，区分“没有站脚处”和“有站脚处但桶射线被挡住”。 */
    public record StandSearch(BlockPos position, int standingCandidates, int visibleCandidates) {}

    static BlockHitResult hit(LocalPlayer player, Vec3 eye, Vec3 aim) {
        Vec3 delta = aim.subtract(eye);
        if (delta.lengthSqr() < 1e-8) return null;
        double reach = Math.min(4.5, player.blockInteractionRange());
        BlockHitResult hit = player.level().clip(new ClipContext(eye, eye.add(delta.normalize().scale(reach)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    // 普通部件点击沿用四格半触及距离，按最近的可用站位选择；已失败的位置跳过。
    static BlockPos nearestStand(LocalPlayer player, BlockPos target, Set<Long> excluded, Function<Vec3, Vec3> aimFrom) {
        return nearestStand(player, target, excluded, aimFrom, Pose.CROUCHING);
    }

    public static BlockPos nearestStand(LocalPlayer player, BlockPos target, Set<Long> excluded,
                                        Function<Vec3, Vec3> aimFrom, Pose pose) {
        return searchStand(player, target, excluded, aimFrom, pose, Math.min(4.5, player.blockInteractionRange())).position();
    }

    public static StandSearch searchStand(LocalPlayer player, BlockPos target, Set<Long> excluded,
                                         Function<Vec3, Vec3> aimFrom, Pose pose, double reach) {
        BlockPos best = null;
        double distance = Double.POSITIVE_INFINITY;
        if (!Double.isFinite(reach) || reach <= 0) return new StandSearch(null, 0, 0);
        int standing = 0, visible = 0, horizontal = (int) Math.ceil(reach + .5);
        double eyes = player.getEyeHeight(pose);
        // 从高岸向下取水时，脚位可以比源格高两三格；用眼高和真实触及距离推导范围，不能固定成水面上一格。
        int minimumY = -(int) Math.ceil(reach + eyes), maximumY = (int) Math.ceil(reach + 1 - eyes);
        for (int dx = -horizontal; dx <= horizontal; dx++) for (int dz = -horizontal; dz <= horizontal; dz++)
                for (int dy = minimumY; dy <= maximumY; dy++) {
            BlockPos feet = target.offset(dx, dy, dz);
            Vec3 eye = Vec3.atBottomCenterOf(feet).add(0, eyes, 0);
            // 先排除连目标外表面都够不到的格子；真正能否命中仍交给原生桶或部件射线核实。
            double x = Math.max(0, Math.abs(eye.x - target.getX() - .5) - .5);
            double y = Math.max(0, Math.abs(eye.y - target.getY() - .5) - .5);
            double z = Math.max(0, Math.abs(eye.z - target.getZ() - .5) - .5);
            if (x * x + y * y + z * z > (reach + .01) * (reach + .01)) continue;
            if (excluded.contains(feet.asLong()) || !standable(player, feet)) continue;
            standing++;
            double candidateDistance = Vec3.atBottomCenterOf(feet).distanceToSqr(player.position());
            if (candidateDistance >= distance) continue;
            if (aimFrom.apply(eye) != null) { visible++; best = feet; distance = candidateDistance; }
        }
        return new StandSearch(best, standing, visible);
    }

    // 当前只认整数高度的站位：脚和头两格必须完全没有碰撞、没有流体，脚下还要有结实的顶面。半砖上的半格站位不在候选中。
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
