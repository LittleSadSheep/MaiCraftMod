// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.outcome.Outcome;

/**
 * 执行器：逐刻做事的对象。身体每刻只有一个使用者，由仲裁选出，被选中的执行器才会被推进一刻。
 *
 * <p>生命周期：{@code start}（一次性准备）→ 若干次 {@code tick}（中间可能被 {@code pause} 打断，
 * 重新被选中后直接接着 tick，不会再 start）→ {@code close}（无论怎样结束都会调用且只调用一次）。
 *
 * <p>新写的执行器一律继承 {@link PhasedExecutor}，不要直接实现本接口。
 */
public interface Task {

    /** 接单时的一次性准备；被抢占后恢复不会再调用。 */
    default void start(TickContext context) {}

    /** 推进一刻。 */
    TaskStatus tick(TickContext context);

    /** 被更紧急的需求抢占：停下按键和进行中的动作，保留进度。 */
    void pause();

    /**
     * 结束：正常走到结局、被替换、被取消或失去身体时都会调用一次。
     * 负责收尾（归还操作额度、关闭自己开的菜单、松开按键），并返回最终回执。
     */
    Outcome close(CloseReason reason);

    /** 此刻能不能被打断；默认是"正常干活"。 */
    default Interruptibility interruptibility(TickContext context) {
        return Interruptibility.BUSY;
    }

    /** 给调试面板和日志的一句话：此刻在做什么。 */
    String describe();
}
