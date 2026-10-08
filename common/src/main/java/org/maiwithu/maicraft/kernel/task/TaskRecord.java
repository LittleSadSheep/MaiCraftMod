// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/**
 * 任务单：一件具体工作的不可变输入，只描述"要做什么"，不含任何进度。
 *
 * <p>各能力用 Java record 实现它，例如"去这张床睡觉"。执行中的可变状态只放在执行器里，
 * 生命周期（是否开始、何时结束、结果如何）由内核的 {@link TaskHandle} 记录。
 * 这样同一张任务单可以安全地被持久化、重放和比较，不会被执行过程悄悄改掉。
 */
public interface TaskRecord {

    /** 给日志、调试面板和回执用的一句话，例如"去家里的床睡觉"。 */
    String describe();
}
