// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.outcome.Outcome;
import org.maiwithu.maicraft.kernel.task.TaskRecord;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 翻译结果：能力看了现场之后，为当前步骤决定的下一个动作（docs/design/02 第 5.3 节）。
 *
 * <p>v1 的"工具名 + JSON 参数"通道已经取消：能力直接给出任务单，不再经过工具与拦截。
 */
public sealed interface IntentAction {

    /** 信息还不全（例如分帧扫描没扫完），下一刻再判断；不能把"没扫完"当成"没有"。 */
    IntentAction PENDING = new Pending();

    record Pending() implements IntentAction {}

    /** 不需要动手，直接给出结果，例如开始时就已经满足、或只读的分析。 */
    record Report(Outcome outcome) implements IntentAction {
        public Report {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** 执行一张任务单；reobserveAfterSuccess 为 true 时，做完后回来重新判断本步骤，而不是直接算完成。 */
    record Run(TaskRecord record, boolean reobserveAfterSuccess) implements IntentAction {
        public Run {
            Objects.requireNonNull(record, "record");
        }

        public Run(TaskRecord record) {
            this(record, false);
        }
    }

    /** 按顺序执行几张任务单；每张到执行时才构造，保证期限与副作用按顺序发生。 */
    record Chain(List<Supplier<TaskRecord>> steps) implements IntentAction {
        public Chain {
            steps = List.copyOf(steps);
            if (steps.isEmpty()) throw new IllegalArgumentException("动作链不能为空");
        }
    }

    /** 暂停并向 LLM 提问；只允许三种原因，见 {@link Decision}。 */
    record Ask(Decision decision) implements IntentAction {
        public Ask {
            Objects.requireNonNull(decision, "decision");
        }
    }

    /** 记住一个地点。 */
    record Remember(String name, WorldPosition position) implements IntentAction {
        public Remember {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(position, "position");
        }
    }

    /** 等条件成立；在 notBeforeTick 之前不检查。 */
    record Wait(Condition condition, long notBeforeTick) implements IntentAction {
        public Wait {
            Objects.requireNonNull(condition, "condition");
        }
    }
}
