package org.maiwithu.maicraft.core.pathing.goals;

import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import net.minecraft.core.BlockPos;

/**
 * 旧的逃离目标：与所有起点拉开水平距离，指定高度时还必须到该层。当前没有主流程入口；NavGoal 内有独立的现用目标表达。
 */
public class GoalRunAway implements Goal {

    private final BlockPos[] from;
    private final int distanceSq;
    private final Integer maintainY;

    private final double costHeuristic;
    public GoalRunAway(double distance, BlockPos... from) {
        this(distance, null, from);
    }

    public GoalRunAway(double distance, Integer maintainY, BlockPos... from) {
        if (from.length == 0) {
            throw new IllegalArgumentException("逃离目标至少需要一个威胁点");
        }
        this.from = from.clone();
        // 这个旧实现把距离平方截成整数，带小数的距离不会完整保留；当前没有主流程调用它。
        this.distanceSq = (int) (distance * distance);
        this.maintainY = maintainY;
        this.costHeuristic = NavSettings.get().costHeuristic;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (maintainY != null && maintainY != y) {
            return false;
        }
        for (BlockPos p : from) {
            int diffX = x - p.getX();
            int diffZ = z - p.getZ();
            int distSq = diffX * diffX + diffZ * diffZ;
            if (distSq < distanceSq) {
                return false;
            }
        }
        return true;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double min = Double.MAX_VALUE;
        for (BlockPos p : from) {
            double h = GoalXZ.calculate(p.getX() - x, p.getZ() - z, costHeuristic);
            if (h < min) {
                min = h;
            }
        }
        min = -min;
        if (maintainY != null) {
            min = min * 0.6 + GoalYLevel.calculate(maintainY, y) * 1.5;
        }
        return min;
    }

    @Override
    public String toString() {
        return maintainY != null
                ? String.format("GoalRunAway{distSq=%d,y=%d}", distanceSq, maintainY)
                : String.format("GoalRunAway{distSq=%d}", distanceSq);
    }
}
