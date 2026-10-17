// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.Objects;

/** 能力钩子在步骤的任务结束后怎么处理结果：照常接受（可以改写后再接受），或换成另一个决定。 */
public sealed interface AfterTask {

    static AfterTask accept(TaskResult result) {
        return new Accept(result);
    }

    static AfterTask replace(StepDecision decision) {
        return new Replace(decision);
    }

    /** 照常接受这个结果。 */
    record Accept(TaskResult result) implements AfterTask {
        public Accept {
            Objects.requireNonNull(result, "result");
        }
    }

    /** 不直接上报这个结果，改按另一个决定继续这一步。 */
    record Replace(StepDecision decision) implements AfterTask {
        public Replace {
            Objects.requireNonNull(decision, "decision");
        }
    }
}
