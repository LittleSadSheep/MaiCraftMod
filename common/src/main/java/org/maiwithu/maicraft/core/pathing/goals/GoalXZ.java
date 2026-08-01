package org.maiwithu.maicraft.core.pathing.goals;

import org.maiwithu.maicraft.core.pathing.settings.NavSettings;

/** XZ 目标:任意高度,水平坐标相等即到达。 */
public class GoalXZ implements Goal {

    private static final double SQRT_2 = Math.sqrt(2);

    public final int x;
    public final int z;

    private final double costHeuristic;

    public GoalXZ(int x, int z) {
        this.x = x;
        this.z = z;
        this.costHeuristic = NavSettings.get().costHeuristic;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double xDiff = x - this.x;
        double zDiff = z - this.z;
        return calculate(xDiff, zDiff, costHeuristic);
    }

    /**
     * 八方向(octile)距离 × costHeuristic:对角段走 √2,剩余直行,
     * 再乘"全程可疾跑"的乐观单格成本(3.563)。
     */
    public static double calculate(double xDiff, double zDiff) {
        return calculate(xDiff, zDiff, NavSettings.get().costHeuristic);
    }

    public static double calculate(double xDiff, double zDiff, double costHeuristic) {
        double x = Math.abs(xDiff);
        double z = Math.abs(zDiff);
        double straight;
        double diagonal;
        if (x < z) {
            straight = z - x;
            diagonal = x;
        } else {
            straight = x - z;
            diagonal = z;
        }
        diagonal *= SQRT_2;
        return (diagonal + straight) * costHeuristic;
    }

    @Override
    public String toString() {
        return String.format("GoalXZ{x=%d,z=%d}", x, z);
    }
}
