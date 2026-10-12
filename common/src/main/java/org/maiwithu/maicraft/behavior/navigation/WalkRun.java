// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 走到运行：一次"走到目标"的运行。它同时是一个动作，任务在阶段里逐刻推进它；
 * 动作做完（{@code tick} 报完成）时身体已到达或已按目标规则结算，详情看 {@link #report()}。
 *
 * <p>泅渡、上坡、被水流带偏都发生在路上，运行只如实观察与报告，不假装路线一定按计划走。
 * 推进与停下都只能在客户端线程调用。中途停下分两步：先请求，
 * 角色仍在空中或跳跃中途时要等落到安全边界才真正停住，停稳前动作仍报告在做，
 * 免得把一次可以避免的坠落换成中途撒手。
 */
public interface WalkRun extends Action {

    /** 最近一刻的走到情况；还没有观察时给"正在算路"。 */
    WalkReport report();

    /**
     * 请求中途停下。返回本刻是否已经完全停住；返回 false 表示已接受请求，
     * 要等安全边界（落地、走完正在跨的一步），停稳后走到情况变为已停下。
     */
    boolean stop();

    /**
     * 走着换目标点：跟着会动的人或东西走时用。路线照走，不停下重来——原来的路线还在往新目标靠近就接着走、
     * 后面一段由前瞻续上；方向已经不对了才在能停的地方撤掉旧路线、从原地重算。到没到从这一刻起按新目标判。
     * 这一趟已经结束、或这种走法换不了目标时返回 false，调用方另开一趟。
     */
    default boolean retarget(GoalCompiler.Compiled target) {
        return false;
    }

    /**
     * 这一趟能不能疾跑。快追上人时关掉：疾跑的惯性会冲过头撞到人身上。跳远这种不疾跑跳不过去的一步照样疾跑。
     * 默认跑得动就跑。
     */
    default void allowSprint(boolean allowed) {
    }
}
