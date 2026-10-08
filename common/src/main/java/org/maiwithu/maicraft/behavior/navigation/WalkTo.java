// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;

/**
 * 走到：对外的寻路入口。站位与靠近、出行等模型只看它——交一条编译好的目标开始走，
 * 逐刻拿进展与到达确认，能中途停下。
 *
 * <p>泅渡与上坡是路径上自然发生的事，入口不拦也不替角色选路，只在走到情况里如实报告（是否泅渡）。
 * 到没到以身体为准：目标格判断通过且站实落地，宽松目标允许水里漂到位（见 {@link WalkArrival}）。
 * 入口只做"走到目标"这一件事，不去拿东西、不开箱子，复合的行程由出行任务自己组合。
 *
 * <p>线程约定：本接口的方法与走到运行的每刻推进都只能在客户端线程调用——
 * 寻路的世界读取要求客户端线程，逐刻驱动由后续集成把它接进客户端刻。
 */
public interface WalkTo {

    /**
     * 开始一次走到。同一时刻角色只走一条路：上一次运行还没安全停稳时，
     * 新运行要等它交出身体才上路，等待期间如实报告为正在算路，不冒充已在走路。
     *
     * @param target  编译好的导航目标（含途中不得破坏的保护格）
     * @param permit  这次走到被允许动多少地形
     */
    WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit);
}
