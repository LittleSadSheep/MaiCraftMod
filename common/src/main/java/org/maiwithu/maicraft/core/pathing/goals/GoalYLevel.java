package org.maiwithu.maicraft.core.pathing.goals;

import org.maiwithu.maicraft.core.pathing.moves.ActionCosts;

/**
 * 旧的高度目标：只比较 Y，上升和下降用不同参考耗时。当前只供同组旧目标使用。
 */
public class GoalYLevel implements Goal {

    public final int level;

    public GoalYLevel(int level) {
        this.level = level;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return y == level;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(level, y);
    }

    /**
     * 竖直移动的乐观单格成本:下降按坠落 2 格耗时的一半
     * (3.894/格),上升按起跳单格耗时(3.163/格,刻意不含跳跃罚金)。
     */
    public static double calculate(int goalY, int currentY) {
        if (currentY > goalY) {
            return ActionCosts.FALL_N_BLOCKS_COST[2] / 2 * (currentY - goalY);
        }
        if (currentY < goalY) {
            return ActionCosts.JUMP_ONE_BLOCK_COST * (goalY - currentY);
        }
        return 0;
    }

    @Override
    public String toString() {
        return String.format("GoalYLevel{y=%d}", level);
    }
}
