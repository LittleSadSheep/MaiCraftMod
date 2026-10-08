// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/**
 * 动作：任务在某个阶段里做的一件具体的事，可以逐刻推进、可以暂停、能自己收尾。
 * 例如"走到能看见床头的站位"、"瞄准床头右键并确认躺下"。动作由行为模型提供，任务在各个阶段里组合它们。
 */
public interface Action {

    /** 推进一刻。 */
    ActionStatus tick(TickContext context);

    /** 被生存需求打断：松开按键、停下动作，保留进度。 */
    default void pause() {}

    /** 不再使用：结清已提交的交互、释放占用的东西（每刻的交互机会、打开的容器界面、按住的键）。 */
    default void close() {}

    /** 此刻能不能被打断；正在等游戏确认交互结果、或者悬空时应返回 UNSAFE_TO_STOP。 */
    default Interruptibility interruptibility() {
        return Interruptibility.WORKING;
    }

    /** 给调试面板和日志的一句话，例如"正在走向床边（第 2 个站位）"。 */
    String describe();
}
