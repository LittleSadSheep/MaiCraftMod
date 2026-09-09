package org.maiwithu.maicraft.core.pathing.goals;

import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import net.minecraft.core.BlockPos;

/**
 * 旧的球形到达范围：三维距离不超过 range 就算到达，估价仍计算到中心的成本。
 * 当前没有生产或测试构造调用，现用任务走 NavGoal 的目标描述。
 */
public class GoalNear implements Goal {

    public final int x;
    public final int y;
    public final int z;
    public final int rangeSq;
    private final double costHeuristic;

    public GoalNear(BlockPos pos, int range) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.rangeSq = range * range;
        this.costHeuristic = NavSettings.get().costHeuristic;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff <= rangeSq;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        // 不减 range:估到中心的全量成本(依旧是可行下界的乐观近似)
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return GoalBlock.calculate(xDiff, yDiff, zDiff, costHeuristic);
    }

    @Override
    public String toString() {
        return String.format("GoalNear{x=%d,y=%d,z=%d,rangeSq=%d}", x, y, z, rangeSq);
    }
}
