// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.outcome.Outcome;
import org.maiwithu.maicraft.kernel.task.TaskRecord;

/**
 * 能力钩子：只有能力自己知道的特殊处理，从这里挂进目标推进，而不是去改内核（docs/design/02 第 5.4 节）。
 *
 * <p>钩子是例外通道。每个钩子实现都要在领域规格里说明，为什么行为模型解决不了这件事；
 * 绝大多数能力用 {@link #NONE}。
 */
public interface StepHooks {

    /** 不需要任何特殊处理。 */
    StepHooks NONE = new StepHooks() {};

    /** 子任务启动前：例如把"本步开始时已有几件"这样的步骤级记忆交给子任务。 */
    default void beforeChild(StepContext step, TaskRecord child) {}

    /** 子任务运行中：子任务发现了必须由调用方选择的情况时，返回要提的问题；没有时返回 null。 */
    default Decision duringChild(StepContext step, TaskRecord child) {
        return null;
    }

    /** 子任务结束：可以补充回执，或给出替代动作。 */
    default ChildOutcome afterChild(StepContext step, TaskRecord child, Outcome outcome) {
        return ChildOutcome.accept(outcome);
    }
}
