// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/**
 * 任务输入：一件具体工作要做什么，不可变，不含任何进度。
 *
 * <p>各能力用 Java record 实现它，例如"去这张床睡觉"。运行中会变的东西只放在任务对象里，
 * 生命周期（是否开始、何时结束、结果如何）由 {@link TaskRun} 记录。
 * 这样同一份输入可以安全地存盘、重放和比较，不会被运行过程悄悄改掉。
 */
public interface TaskInput {

    /** 给日志、调试面板和结果用的一句话，例如"去家里的床睡觉"。 */
    String describe();
}
