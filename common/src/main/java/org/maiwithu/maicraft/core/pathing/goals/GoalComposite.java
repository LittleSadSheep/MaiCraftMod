package org.maiwithu.maicraft.core.pathing.goals;

import java.util.Arrays;

/**
 * 旧的多个备选目标：满足其中任意一个就算到达，估价取其中最小值。
 * 当前没有生产或测试构造调用；传入和取出的目标数组都会复制，空数组则永不到达。
 */
public class GoalComposite implements Goal {

    private final Goal[] goals;

    public GoalComposite(Goal... goals) {
        this.goals = goals.clone();
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        for (Goal goal : goals) {
            if (goal.isInGoal(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double min = Double.MAX_VALUE;
        for (Goal goal : goals) {
            min = Math.min(min, goal.heuristic(x, y, z));
        }
        return min;
    }

    public Goal[] goals() {
        return goals.clone();
    }

    @Override
    public String toString() {
        return "GoalComposite" + Arrays.toString(goals);
    }
}
