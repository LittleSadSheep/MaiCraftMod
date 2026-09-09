package org.maiwithu.maicraft.core.pathing.goals;

/**
 * 约定如何判断到达、如何给位置估价。多数旧具体目标已无主流程入口，GoalAvoidEntities 仍使用这个接口并被 NavGoal 复用。
 */
public interface Goal {

    /** (x,y,z) 是否已在目标内。 */
    boolean isInGoal(int x, int y, int z);

    /** 从 (x,y,z) 到目标的乐观剩余成本(tick)。 */
    double heuristic(int x, int y, int z);
}
