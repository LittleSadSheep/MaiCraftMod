// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 能力钩子：只有某个能力自己知道的特殊处理，从这里挂进目标推进，而不是去改内核。
 *
 * <p>钩子是例外通道。每个钩子实现都要在领域规格里说明，为什么行为模型解决不了这件事；
 * 绝大多数能力用 {@link #NONE}。
 */
public interface AbilityHooks {

    /** 不需要任何特殊处理。 */
    AbilityHooks NONE = new AbilityHooks() {};

    /** 步骤的任务启动前：例如把"这一步开始时背包里已有几件"交给任务。 */
    default void beforeTask(StepContext step, TaskInput input) {}

    /** 步骤的任务运行中：任务碰到了必须由 LLM 选择的情况时，返回要提的问题；没有时返回 null。 */
    default Question duringTask(StepContext step, TaskInput input) {
        return null;
    }

    /** 步骤的任务结束后：可以改写结果，或换成另一个决定。 */
    default AfterTask afterTask(StepContext step, TaskInput input, TaskResult result) {
        return AfterTask.accept(result);
    }
}
