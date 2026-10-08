// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

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
}
