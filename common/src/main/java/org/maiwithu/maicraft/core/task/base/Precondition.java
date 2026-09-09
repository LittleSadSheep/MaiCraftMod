package org.maiwithu.maicraft.core.task.base;

import org.maiwithu.maicraft.core.FailureType;

/**
 * 任务开始前的一项检查。返回 null 表示允许继续，返回 Failure 则给出失败原因和类别。
 * 它只表达判断结果，不会自动补材料、寻路或重试。
 */
public interface Precondition {

    /**
     * Evaluate the gate.
     *
     * @return {@code null} when satisfied (the task may start); otherwise the
     *         {@link Failure} to report as the task's terminal result.
     */
    Failure check();

    /** A precondition's verdict when it is NOT satisfied. */
    record Failure(String message, FailureType type) {}
}
