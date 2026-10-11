// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * 线缆格里点哪儿，纯函数。线缆芯占方块正中 6/16 到 10/16；朝某一面连着时，从芯伸出一截到那一面的边上。
 * 要在某一面装部件，就点那一面的那截线缆（或没连着时点芯朝那一面的面）——AE2 按点到的那一截认面；
 * 要在旁边一格接一根新线缆，就点芯朝那一格的那一面，AE2 发现这一格装不下第二根线缆，就放到隔壁那一格。
 */
final class CableGeometry {

    /** 线缆芯的下沿与上沿（方块内坐标）。 */
    static final double CORE_MIN = 6.0 / 16;
    static final double CORE_MAX = 10.0 / 16;
    /** 往外放一点：点在边上也算点到这一截。 */
    private static final double MARGIN = 0.02;
    /** 只点一面时那一面的厚度。 */
    private static final double FACE_DEPTH = 0.01;

    private CableGeometry() {
    }

    /** 线缆格 side 那一面的那一截（从芯到方块边上）：装部件点这里，AE2 按点到的这一截认是这一面。 */
    static AABB arm(BlockPos at, Direction side) {
        double low = CORE_MIN - MARGIN;
        double high = CORE_MAX + MARGIN;
        double[] min = {low, low, low};
        double[] max = {high, high, high};
        int axis = side.getAxis().ordinal();
        if (side.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            min[axis] = CORE_MAX;
            max[axis] = 1.0;
        } else {
            min[axis] = 0.0;
            max[axis] = CORE_MIN;
        }
        return new AABB(min[0], min[1], min[2], max[0], max[1], max[2]).move(at);
    }

    /** 线缆芯朝 side 的那一面：在隔壁一格接新线缆时点这里，点到的面就是 side。 */
    static AABB coreFace(BlockPos at, Direction side) {
        double inset = 0.04;
        double[] min = {CORE_MIN + inset, CORE_MIN + inset, CORE_MIN + inset};
        double[] max = {CORE_MAX - inset, CORE_MAX - inset, CORE_MAX - inset};
        int axis = side.getAxis().ordinal();
        double face = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? CORE_MAX : CORE_MIN;
        min[axis] = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? face - FACE_DEPTH : face;
        max[axis] = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? face : face + FACE_DEPTH;
        return new AABB(min[0], min[1], min[2], max[0], max[1], max[2]).move(at);
    }
}
