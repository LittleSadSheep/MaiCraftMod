// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** 几何预检绝不能替代执行器对真实原生放置结果的预测。 */
final class CreateMechanicalPlacementGeometry {
    private CreateMechanicalPlacementGeometry() {}

    /** 使用 Create 实际的轴向朝向 API 复现优选轴输入，不根据注册名称猜测。 */
    static boolean inheritsVerticalAxis(Level level, BlockPos target) {
        Direction.Axis preferred = null;
        for (Direction face : Direction.values()) {
            BlockPos adjacent = target.relative(face);
            if (!level.isLoaded(adjacent)) return false;
            if (!CreateKineticsBridge.hasShaftTowards(level, adjacent, level.getBlockState(adjacent), face.getOpposite())) continue;
            if (preferred != null && preferred != face.getAxis()) return false;
            preferred = face.getAxis();
        }
        return preferred == Direction.Axis.Y;
    }

    static boolean feasibleView(BlockPos stand, BlockPos target, boolean inheritsVertical) {
        Vec3 eye = Vec3.atBottomCenterOf(stand).add(0, 1.62, 0);
        Vec3 delta = Vec3.atCenterOf(target).subtract(eye);
        double pitch = Math.toDegrees(Math.atan2(Math.abs(delta.y), Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
        return delta.lengthSqr() <= 4.35 * 4.35 && (inheritsVertical || pitch >= 48);
    }

    static boolean needsTopFaceJump(Vec3 eye, Vec3 point, Direction face) {
        return face == Direction.UP && eye.y <= point.y + .05;
    }

    static boolean clearForJump(Level level, BlockPos feet) {
        for (int height = 1; height <= 3; height++) {
            BlockPos at = feet.above(height);
            if (!level.isLoaded(at) || level.isOutsideBuildHeight(at) || NavigationSafetyContext.forbidsBody(at)
                    || !level.getFluidState(at).isEmpty() || !level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return false;
        }
        return true;
    }
}
