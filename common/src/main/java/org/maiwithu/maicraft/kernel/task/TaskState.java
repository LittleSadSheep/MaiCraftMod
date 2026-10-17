// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/**
 * 一次任务运行处在生命周期的哪一段，只能按 PENDING → RUNNING → FINISHED 前进。
 *
 * <p>做成了、部分做成、失败还是被取消，不是生命周期状态，而是写在结束时的 {@code TaskResult} 里；
 * 被生存需求打断也不是状态变化，任务仍是 RUNNING，只是这一刻没有轮到它。
 */
public enum TaskState {
    /** 已登记，还没开始动手。 */
    PENDING,
    /** 已开始；这一刻可能正被生存需求打断。 */
    RUNNING,
    /** 已结束，结果见 TaskResult；结束后不可再改。 */
    FINISHED;

    public boolean isTerminal() {
        return this == FINISHED;
    }
}
