// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.scan;

import net.minecraft.core.BlockPos;

/**
 * 由原点向外绕圈的方形螺旋步进器：每次 next 前进一步并给出候选落点
 * （原点加格偏移乘网格，高度由调用方传入当前身体高度）。
 * 每走满 segmentLength 步就转向，同一长度走两条边后边长加一，各方向覆盖密度均匀。
 * 只做几何推进，不读世界、不判断加载与去重——范围、前沿截断与已试去重由调用方负责。
 */
public final class SpiralWalker {
    private final BlockPos origin;
    private final int grid;
    private int offsetX;
    private int offsetZ;
    private int direction;
    private int segmentLength = 1;
    private int segmentProgress;
    private int segmentsAtLength;

    public SpiralWalker(BlockPos origin, int grid) {
        if (grid < 1) throw new IllegalArgumentException("spiral grid must be positive");
        this.origin = origin;
        this.grid = grid;
    }

    /** 前进一步并给出候选落点；高度取调用方当时的值，落点不固定在同一水平层。 */
    public BlockPos next(int y) {
        switch (direction) {
            case 0 -> offsetX++;
            case 1 -> offsetZ++;
            case 2 -> offsetX--;
            default -> offsetZ--;
        }
        segmentProgress++;
        if (segmentProgress >= segmentLength) {
            segmentProgress = 0;
            direction = (direction + 1) & 3;
            if (++segmentsAtLength >= 2) {
                segmentsAtLength = 0;
                segmentLength++;
            }
        }
        return new BlockPos(origin.getX() + offsetX * grid, y, origin.getZ() + offsetZ * grid);
    }

    /** 当前格偏移（格数，非方块坐标）；调用方做圈数或范围判断用。 */
    public int offsetX() { return offsetX; }

    /** 当前格偏移（格数，非方块坐标）；调用方做圈数或范围判断用。 */
    public int offsetZ() { return offsetZ; }
}
