// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;

/**
 * 剩余距离：没走到时，脚下位置离目的地还差多少——水平格数、高低差与八向方位。
 * 全项目统一的方位说法：北、东北、东、东南、南、西南、西、西北（北是 -Z，东是 +X）。
 */
public record RemainingDistance(double horizontalBlocks, int verticalBlocks, String octant) {

    private static final String[] OCTANTS = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};

    /** 从脚下位置量到目的地；同一格给零距离。 */
    public static RemainingDistance measure(BlockPos from, BlockPos destination) {
        double dx = destination.getX() - from.getX();
        double dz = destination.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal == 0) {
            return new RemainingDistance(0, destination.getY() - from.getY(), "原地");
        }
        // 北是 -Z、东是 +X：以 -Z 为零度向 +X 转，得到罗盘角再折成八向。
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        int index = (int) Math.floor(Math.floorMod((long) Math.round(angle / 45.0), 8));
        return new RemainingDistance(horizontal, destination.getY() - from.getY(), OCTANTS[index]);
    }

    /** 给结果与直播解说的一句话，例如"西北 12.3 格、高 3 格"。 */
    public String describe() {
        if (horizontalBlocks == 0 && verticalBlocks == 0) {
            return "已经站在目的地";
        }
        String vertical = verticalBlocks == 0 ? "同高"
                : verticalBlocks > 0 ? "高 " + verticalBlocks + " 格"
                : "低 " + (-verticalBlocks) + " 格";
        return octant + " " + String.format("%.1f", horizontalBlocks) + " 格、" + vertical;
    }
}
