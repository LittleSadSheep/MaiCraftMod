// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskInput;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 能力看了现场之后，对当前这一步的决定：下一件做什么。
 *
 * <p>能力直接给出任务输入，内核按它创建任务并运行，中间不再经过别的转发。
 */
public sealed interface StepDecision {

    /** 信息还不全（例如分几刻做的扫描还没扫完），下一刻再决定；不能把"没扫完"当成"没有"。 */
    StepDecision NOT_READY = new NotReady();

    record NotReady() implements StepDecision {}

    /** 不需要动手，直接给出结果，例如开始时就已经满足，或者只读的分析。 */
    record Finish(TaskResult result) implements StepDecision {
        public Finish {
            Objects.requireNonNull(result, "result");
        }
    }

    /** 运行一个任务；recheckAfterSuccess 为 true 时，任务成功后回来重新看这一步是否满足，而不是直接算完成。 */
    record Run(TaskInput input, boolean recheckAfterSuccess) implements StepDecision {
        public Run {
            Objects.requireNonNull(input, "input");
        }

        public Run(TaskInput input) {
            this(input, false);
        }
    }

    /** 按顺序运行几个任务；每个任务的输入到轮到它时才生成，保证时限和副作用按顺序发生。 */
    record RunInOrder(List<Supplier<TaskInput>> inputs) implements StepDecision {
        public RunInOrder {
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty()) throw new IllegalArgumentException("至少要有一个任务");
        }
    }

    /** 停下来向 LLM 提问；只允许三种原因，见 {@link Question}。 */
    record Ask(Question question) implements StepDecision {
        public Ask {
            Objects.requireNonNull(question, "question");
        }
    }

    /** 记住一个地点。 */
    record Remember(String name, WorldPosition position) implements StepDecision {
        public Remember {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(position, "position");
        }
    }

    /** 等条件成立；在 notBeforeTick 之前不检查。 */
    record Wait(WaitCondition condition, long notBeforeTick) implements StepDecision {
        public Wait {
            Objects.requireNonNull(condition, "condition");
        }
    }
}
