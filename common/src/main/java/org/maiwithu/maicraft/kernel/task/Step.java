// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/**
 * 子步骤：行为模型提供的一个可逐刻推进、可暂停、能自己收尾的动作，
 * 例如"走到能看见床头的工位"、"瞄准床头右键并确认入睡"。执行器在各个阶段里组合它们。
 */
public interface Step {

    /** 推进一刻。 */
    StepStatus tick(TickContext context);

    /** 被抢占：停下动作，保留进度。 */
    default void pause() {}

    /** 不再使用：结算回执、释放占用（操作额度、菜单、按键）。 */
    default void close() {}

    /** 此刻能不能被打断；处在原生确认窗口或悬空时应返回 DELICATE。 */
    default Interruptibility interruptibility() {
        return Interruptibility.BUSY;
    }

    /** 给调试面板和日志的一句话，例如"正在走向床边（第 2 个工位）"。 */
    String describe();
}
