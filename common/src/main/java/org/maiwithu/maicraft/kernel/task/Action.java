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

    /**
     * 停下之前先到安全的地方（飞着的就近落地、车上的刹停）：返回真表示开始收尾，之后照常推进它，
     * 收尾做完了 {@link #stoppedSafely} 为真；返回假表示现在就能停。默认现在就能停。之后再 {@link #pause} 就是照原样接着做。
     */
    default boolean stopSafely() {
        return false;
    }

    /** 收尾做完了，停在安全的地方等着；没在收尾为真。 */
    default boolean stoppedSafely() {
        return true;
    }

    /** 此刻能不能被打断；正在等游戏确认交互结果、或者悬空时应返回 UNSAFE_TO_STOP。 */
    default Interruptibility interruptibility() {
        return Interruptibility.WORKING;
    }

    /**
     * 此刻能不能被打断，带着这一刻的现场问；分阶段任务问手上的动作走这里。
     * 包着子任务的动作（砌框、放机器格、插灯）改写它，按子任务手上那一下说：等游戏确认放方块、悬空时停不得。
     * 默认同 {@link #interruptibility()}。
     */
    default Interruptibility interruptibility(TickContext context) {
        return interruptibility();
    }

    /** 给调试面板和日志的一句话，例如"正在走向床边（第 2 个站位）"。 */
    String describe();
}
