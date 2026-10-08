// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/**
 * 任务的生命周期状态，只能按 PENDING → RUNNING → FINISHED 前进。
 *
 * <p>做成了、部分做成、失败还是被取消，不是生命周期状态，而是写在结束时的统一回执里；
 * 被反射抢占也不是状态变化，任务仍是 RUNNING，只是这一刻没有轮到它。
 */
public enum TaskState {
    /** 已登记，还没开始动手。 */
    PENDING,
    /** 已开始；可能这一刻正被更紧急的需求抢占。 */
    RUNNING,
    /** 已结束，结果见回执；终态不可再改。 */
    FINISHED;

    public boolean isTerminal() {
        return this == FINISHED;
    }
}
