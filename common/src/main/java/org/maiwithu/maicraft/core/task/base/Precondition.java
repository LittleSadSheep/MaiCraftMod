package org.maiwithu.maicraft.core.task.base;

import org.maiwithu.maicraft.core.FailureType;

/**
 * 任务开始前的一项检查。返回 null 表示允许继续，返回 Failure 则给出失败原因和类别。
 * 它只表达判断结果，不会自动补材料、寻路或重试。
 */
public interface Precondition {

    /**
     * 执行前置检查。
     *
     * @return 条件满足时返回 {@code null}（任务可以开始）；否则返回应作为任务终态报告的 {@link Failure}。
     */
    Failure check();

    /** 前置条件不满足时返回的判定结果。 */
    record Failure(String message, FailureType type) {}
}
