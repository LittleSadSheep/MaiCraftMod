package org.maiwithu.maicraft.core.pathing.goals;

/**
 * 旧的远离目标：估价取原目标的负数，越远越优，但到达判断始终为 false。
 * 当前没有生产或测试构造调用；现用任务目标由 NavGoal 描述。
 */
public class GoalInverted implements Goal {

    public final Goal origin;

    public GoalInverted(Goal origin) {
        this.origin = origin;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return -origin.heuristic(x, y, z);
    }

    @Override
    public String toString() {
        return String.format("GoalInverted{%s}", origin);
    }
}
