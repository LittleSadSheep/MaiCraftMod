package org.maiwithu.maicraft.core.pathing.goals;

import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import net.minecraft.core.BlockPos;

/**
 * 旧的两格高目标：x/z 必须相同，脚下 y 可等于目标 y 或低一格。
 * 当前没有生产或测试构造调用；下面估价也对低一格作相同补偿。
 */
public class GoalTwoBlocks implements Goal {

    public final int x;
    public final int y;
    public final int z;

    private final double costHeuristic;
    public GoalTwoBlocks(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ());
    }

    public GoalTwoBlocks(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.costHeuristic = NavSettings.get().costHeuristic;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && (y == this.y || y == this.y - 1) && z == this.z;
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
        return String.format("GoalTwoBlocks{x=%d,y=%d,z=%d}", x, y, z);
    }
}
