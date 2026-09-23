// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import net.minecraft.world.phys.Vec3;

/**
 * 在确实可通过的空中路线之间，稍微偏好侧面和头顶更宽敞的空间；靠墙增加代价，实际碰撞才视为不能走。
 */
final class JetpackClearancePolicy {
    private static final double SIDE_MARGIN = 0.5, TOP_MARGIN = 0.35;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private JetpackClearancePolicy() {}

    /** 身体通行空间和原生 UP 操作余量都是必需条件。额外五次扫掠带来的有限成本最多为 4.5。
     * 调用方可在搜索期间按坐标缓存此数值；此辅助类不会保留世界视图。
     */
    static double clearancePenalty(JetpackRoute.Space space, Vec3 point, JetpackNativeAdapter.Snapshot power) {
        if (!JetpackRoute.flightClear(space, point, point, power)) return Double.POSITIVE_INFINITY;
        double penalty = 0;
        for (int[] side : SIDES) {
            Vec3 offset = point.add(side[0] * SIDE_MARGIN, 0, side[1] * SIDE_MARGIN);
            if (!JetpackRoute.flightClear(space, point, offset, power)) penalty += 1;
        }
        if (!JetpackRoute.flightClear(space, point, point.add(0, TOP_MARGIN, 0), power)) penalty += 0.5;
        return penalty;
    }

    /** 使用相同固定探测预算，先验证完整身体和 UP 通道，再对其中心点评分。
     * 这是局部偏好，与路线边的距离或通行时间分开计算。
     */
    static double edgePenalty(JetpackRoute.Space space, Vec3 from, Vec3 to,
                              JetpackNativeAdapter.Snapshot power) {
        return JetpackRoute.flightClear(space, from, to, power)
                ? clearancePenalty(space, from.lerp(to, 0.5), power) : Double.POSITIVE_INFINITY;
    }
}
