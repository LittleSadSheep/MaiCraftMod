// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 维度坐标换算：主世界与下界的水平坐标 1:8。
 *
 * <p>这是给到达确认用的预期：从主世界 (x, z) 过门，落点预期在下界 (x/8, z/8)，
 * 反方向乘 8；高度不参与换算，游戏在换算点附近找现成的门、找不到由原版补一扇，
 * 所以预期只是"大概这一片"，实测落点与预期的偏差照实报。
 */
public final class DimensionScale {

    /** 主世界与下界水平坐标的比例：下界一格等于主世界八格。 */
    private static final int OVERWORLD_TO_NETHER = 8;

    private DimensionScale() {}

    /**
     * 从 {@code fromDimension} 的 {@code at} 过门，到 {@code toDimension} 一侧的预期落点；
     * 这对维度没有换算关系（末地、模组维度）时为 null，结果里不写预期。
     */
    public static WorldPosition expectedSpot(String fromDimension, BlockPos at, String toDimension) {
        if (DimensionRoute.OVERWORLD.equals(fromDimension) && DimensionRoute.NETHER.equals(toDimension)) {
            return new WorldPosition(at.getX() / OVERWORLD_TO_NETHER, at.getY(), at.getZ() / OVERWORLD_TO_NETHER,
                    DimensionRoute.NETHER);
        }
        if (DimensionRoute.NETHER.equals(fromDimension) && DimensionRoute.OVERWORLD.equals(toDimension)) {
            return new WorldPosition(at.getX() * OVERWORLD_TO_NETHER, at.getY(), at.getZ() * OVERWORLD_TO_NETHER,
                    DimensionRoute.OVERWORLD);
        }
        return null;
    }

    /** 实测落点与换算预期的水平偏差（格）；没有预期时为 -1。 */
    public static int horizontalOffset(WorldPosition expected, BlockPos actual) {
        if (expected == null) {
            return -1;
        }
        int dx = expected.x() - actual.getX();
        int dz = expected.z() - actual.getZ();
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }
}
