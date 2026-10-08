// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 任务：逐刻控制角色做一件事的对象，每次运行新建一个。角色每刻只听一个任务的，被选中的任务才会被推进一刻。
 *
 * <p>生命周期：{@code start}（一次性准备）→ 若干次 {@code tick}（中间可能被生存需求打断而 {@code pause}，
 * 轮回来后直接接着 tick，不会再 start）→ {@code close}（无论怎样结束都会调用且只调用一次）。
 *
 * <p>新写的任务一律继承 {@link PhasedTask}，不要直接实现本接口。
 */
public interface Task {

    /** 开始前的一次性准备；被打断后恢复不会再调用。 */
    default void start(TickContext context) {}

    /** 推进一刻。 */
    TickResult tick(TickContext context);

    /** 被生存需求打断：松开按键、停下进行中的动作，保留进度。 */
    void pause();

    /**
     * 结束：正常走到结局、被替换、被取消或角色没了时都会调用一次。
     * 负责收尾（交还本刻的交互机会、关闭自己打开的容器界面、松开按键），并返回最终结果。
     */
    TaskResult close(CloseReason reason);

    /** 此刻能不能被打断；默认是"正常干活"。 */
    default Interruptibility interruptibility(TickContext context) {
        return Interruptibility.WORKING;
    }

    /** 给调试面板和日志的一句话：此刻在做什么。 */
    String describe();
}
