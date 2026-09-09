package org.maiwithu.maicraft.core.pathing.goals;

import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import net.minecraft.core.BlockPos;

/**
 * 旧的接近方块目标：按横竖格数距离判断附近位置，人在目标下方时给一格身体高度补偿。
 * 例如低一格站立时，水平方向再差一格也可算到达。当前没有生产或测试构造调用。
 */
public class GoalGetToBlock implements Goal {

    public final int x;
    public final int y;
    public final int z;
    private final double costHeuristic;

    public GoalGetToBlock(BlockPos pos) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.costHeuristic = NavSettings.get().costHeuristic;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        if (yDiff < 0) {
            yDiff++;
        }
        return Math.abs(xDiff) + Math.abs(yDiff) + Math.abs(zDiff) <= 1;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        if (yDiff < 0) {
            yDiff++;
        }
        return GoalBlock.calculate(xDiff, yDiff, zDiff, costHeuristic);
    }

    public BlockPos getGoalPos() {
        return new BlockPos(x, y, z);
    }

    @Override
    public String toString() {
        return String.format("GoalGetToBlock{x=%d,y=%d,z=%d}", x, y, z);
    }
}
