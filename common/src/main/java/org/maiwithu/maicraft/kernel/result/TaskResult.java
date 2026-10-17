// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.result;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 任务结果：所有能力用同一个结构告诉 LLM 做得怎样。
 *
 * <p>目标达成没有（{@code status}）与交互做成了什么（{@code changes}、{@code unconfirmed}）分开记录：
 * 交互成功不能冒充目标达成，已确认的消耗和世界变化也不能因为目标没达成就记成"不知道"。
 *
 * @param status      目标达成情况
 * @param summary     一句话结论，可以直接复述给人听，例如"在家里的床上躺下了"
 * @param changes     已确认发生的变化
 * @param remaining   没完成的部分，例如"入睡"
 * @param problem     卡在哪；失败时必有，部分完成时可以有，完成与取消时为 null
 * @param unconfirmed 已提交但没能确认结果的交互；这些交互不能盲目重做
 * @param attempts    试过的办法摘要
 * @param details     能力特有的细节
 */
public record TaskResult(
        Status status,
        String summary,
        List<Change> changes,
        List<String> remaining,
        Problem problem,
        List<Change> unconfirmed,
        List<Attempt> attempts,
        ResultDetails details) {

    /** 目标达成情况。 */
    public enum Status {
        /** 目标达成。 */
        DONE,
        /** 做成了一部分，剩下的见 remaining，通常附带问题。 */
        PARTIAL,
        /** 没有达成，原因见 problem。 */
        FAILED,
        /** 被取消或被新任务替换；已经发生的变化仍如实记录。 */
        CANCELLED
    }

    public TaskResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(summary, "summary");
        changes = List.copyOf(changes);
        remaining = List.copyOf(remaining);
        unconfirmed = List.copyOf(unconfirmed);
        attempts = List.copyOf(attempts);
        details = details == null ? ResultDetails.NONE : details;
        if (status == Status.FAILED && problem == null) {
            throw new IllegalArgumentException("失败的结果必须写明问题");
        }
        if ((status == Status.DONE || status == Status.CANCELLED) && problem != null) {
            throw new IllegalArgumentException(status + " 的结果不应带问题：" + problem);
        }
    }

    /** 目标达成。 */
    public static TaskResult done(String summary) {
        return builder(Status.DONE, summary).build();
    }

    /** 没有达成，并写明问题。 */
    public static TaskResult failed(String summary, Problem problem) {
        return builder(Status.FAILED, summary).problem(problem).build();
    }

    /** 被取消或被替换。 */
    public static TaskResult cancelled(String summary) {
        return builder(Status.CANCELLED, summary).build();
    }

    public static Builder builder(Status status, String summary) {
        return new Builder(status, summary);
    }

    /** 在已有结果的基础上修改，例如收尾时补上运行中记下的变化。 */
    public Builder toBuilder() {
        Builder builder = new Builder(status, summary).problem(problem).details(details);
        builder.changes.addAll(changes);
        builder.remaining.addAll(remaining);
        builder.unconfirmed.addAll(unconfirmed);
        builder.attempts.addAll(attempts);
        return builder;
    }

    /**
     * 把在它之前结束的那些结果已经确认的事实接到前面：变化、没能确认的交互、试过的办法按先后排在本结果自己的之前；
     * 结论（状态、一句话、剩下的、问题、细节）仍是本结果的。
     *
     * <p>一个目标往往要跑好几个任务，最后由其中一个（或能力的决定）给出结论；前面任务已经发生的事
     * 不能因为它们不是最后一个就从结果里消失——挖了 40 个铁再被截停，LLM 必须知道这 40 个铁。
     */
    public TaskResult withFactsBefore(List<TaskResult> earlier) {
        if (earlier.isEmpty()) {
            return this;
        }
        Builder builder = new Builder(status, summary).problem(problem).details(details);
        for (TaskResult before : earlier) {
            builder.changes.addAll(before.changes);
            builder.unconfirmed.addAll(before.unconfirmed);
            builder.attempts.addAll(before.attempts);
        }
        builder.changes.addAll(changes);
        builder.remaining.addAll(remaining);
        builder.unconfirmed.addAll(unconfirmed);
        builder.attempts.addAll(attempts);
        return builder.build();
    }

    /** 结果构建器；任务在运行中逐条记下变化与尝试，结束时一次构建。 */
    public static final class Builder {
        private final Status status;
        private final String summary;
        private final List<Change> changes = new ArrayList<>();
        private final List<String> remaining = new ArrayList<>();
        private final List<Change> unconfirmed = new ArrayList<>();
        private final List<Attempt> attempts = new ArrayList<>();
        private Problem problem;
        private ResultDetails details = ResultDetails.NONE;

        private Builder(Status status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        public Builder change(Change change) {
            changes.add(change);
            return this;
        }

        public Builder changes(List<Change> list) {
            changes.addAll(list);
            return this;
        }

        public Builder remaining(String part) {
            remaining.add(part);
            return this;
        }

        public Builder remaining(List<String> parts) {
            remaining.addAll(parts);
            return this;
        }

        public Builder unconfirmed(Change change) {
            unconfirmed.add(change);
            return this;
        }

        public Builder unconfirmed(List<Change> list) {
            unconfirmed.addAll(list);
            return this;
        }

        public Builder attempt(Attempt attempt) {
            attempts.add(attempt);
            return this;
        }

        public Builder attempts(List<Attempt> list) {
            attempts.addAll(list);
            return this;
        }

        public Builder problem(Problem value) {
            problem = value;
            return this;
        }

        public Builder details(ResultDetails value) {
            details = value;
            return this;
        }

        public TaskResult build() {
            return new TaskResult(status, summary, changes, remaining, problem, unconfirmed, attempts, details);
        }
    }
}
