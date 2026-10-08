// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.outcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 统一回执：所有能力用同一个结构告诉 LLM 结果（docs/design/03 的 M8）。
 *
 * <p>目标达成没有（{@code status}）与动作做成了什么（{@code achieved}、{@code uncertain}）分开记录：
 * 动作成功不能冒充目标达成，已确认的消耗和世界变化也不能因为目标没达成就记成"未知"。
 *
 * @param status    目标达成情况
 * @param summary   一句话结论，可直接用于直播解说，例如"在家里的床上躺下了"
 * @param achieved  已确认发生的效果
 * @param remaining 没完成的部分，例如"入睡"
 * @param blocker   卡点；失败时必有，部分完成时可以有，完成与取消时为 null
 * @param uncertain 已提交但没能确认结果的操作；这些操作不能盲目重试
 * @param attempts  尝试过的办法摘要
 * @param facts     能力特有的事实
 */
public record Outcome(
        Status status,
        String summary,
        List<Effect> achieved,
        List<String> remaining,
        Blocker blocker,
        List<Effect> uncertain,
        List<Attempt> attempts,
        Facts facts) {

    /** 目标达成情况。 */
    public enum Status {
        /** 目标达成。 */
        DONE,
        /** 做成了一部分，剩下的见 remaining，通常附带卡点。 */
        PARTIAL,
        /** 没有达成，卡点见 blocker。 */
        FAILED,
        /** 被取消或被新任务替换；已发生的效果仍如实记录。 */
        CANCELLED
    }

    public Outcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(summary, "summary");
        achieved = List.copyOf(achieved);
        remaining = List.copyOf(remaining);
        uncertain = List.copyOf(uncertain);
        attempts = List.copyOf(attempts);
        facts = facts == null ? Facts.NONE : facts;
        if (status == Status.FAILED && blocker == null) {
            throw new IllegalArgumentException("失败的回执必须写明卡点");
        }
        if ((status == Status.DONE || status == Status.CANCELLED) && blocker != null) {
            throw new IllegalArgumentException(status + " 的回执不应带卡点：" + blocker);
        }
    }

    /** 目标达成。 */
    public static Outcome done(String summary) {
        return builder(Status.DONE, summary).build();
    }

    /** 没有达成，并写明卡点。 */
    public static Outcome failed(String summary, Blocker blocker) {
        return builder(Status.FAILED, summary).blocker(blocker).build();
    }

    /** 被取消或被替换。 */
    public static Outcome cancelled(String summary) {
        return builder(Status.CANCELLED, summary).build();
    }

    public static Builder builder(Status status, String summary) {
        return new Builder(status, summary);
    }

    /** 在已有回执基础上修改的构建器，例如收尾时补充效果。 */
    public Builder toBuilder() {
        Builder builder = new Builder(status, summary).blocker(blocker).facts(facts);
        builder.achieved.addAll(achieved);
        builder.remaining.addAll(remaining);
        builder.uncertain.addAll(uncertain);
        builder.attempts.addAll(attempts);
        return builder;
    }

    /** 回执构建器；执行器在执行过程中逐条累积效果与尝试，结束时一次构建。 */
    public static final class Builder {
        private final Status status;
        private final String summary;
        private final List<Effect> achieved = new ArrayList<>();
        private final List<String> remaining = new ArrayList<>();
        private final List<Effect> uncertain = new ArrayList<>();
        private final List<Attempt> attempts = new ArrayList<>();
        private Blocker blocker;
        private Facts facts = Facts.NONE;

        private Builder(Status status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        public Builder achieved(Effect effect) {
            achieved.add(effect);
            return this;
        }

        public Builder achieved(List<Effect> effects) {
            achieved.addAll(effects);
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

        public Builder uncertain(Effect effect) {
            uncertain.add(effect);
            return this;
        }

        public Builder uncertain(List<Effect> effects) {
            uncertain.addAll(effects);
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

        public Builder blocker(Blocker value) {
            blocker = value;
            return this;
        }

        public Builder facts(Facts value) {
            facts = value;
            return this;
        }

        public Outcome build() {
            return new Outcome(status, summary, achieved, remaining, blocker, uncertain, attempts, facts);
        }
    }
}
