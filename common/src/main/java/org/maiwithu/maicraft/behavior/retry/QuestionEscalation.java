// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.retry;

import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 提问升级：判断一个卡住的处境该不该向 LLM 提问，并把现场事实组织成问题。
 *
 * <p>只允许三种情况开口，其他一律内部重试或带着事实结束，永远不问"要不要重试"：
 * <ul>
 * <li>{@code NEED_APPROVAL}：要做的事超出了这次的许可。任务跑着跑着撞上许可墙时，
 *     把"卡在哪"的问题升级成请求同意的提问；LLM 没点头就维持 {@code NEED_APPROVAL} 的事实结束。</li>
 * <li>{@code CHOOSE_ONE}：有几种后果不同的做法，Mod 不该替 LLM 选，例如拆哪面墙、动哪个箱子里的贵重材料。</li>
 * <li>{@code UNCLEAR_TARGET}：目标对象本身说不清，例如有几个同名地标。</li>
 * </ul>
 *
 * <p>纯判断与纯构造：不发送提问，也不等回答；发出与等回答是内核目标推进的事。
 */
public final class QuestionEscalation {

    private QuestionEscalation() {}

    /**
     * 许可墙升级为提问：只有超出许可（{@code NEED_APPROVAL}）的失败才升级，
     * 其他种类一律不问，让调用方按重试策略继续走。返回的提问把卡在哪的事实原样写进问句，
     * 建议里怎样能继续就作为默认做法列为第一个选项。
     */
    public static Optional<Question> escalate(Problem refusal) {
        if (refusal.kind() != Problem.Kind.NEED_APPROVAL) {
            return Optional.empty();
        }
        return Optional.of(approval(refusal.message(), refusal.suggestion()));
    }

    /** 请求同意：要做的事（{@code request}）超出许可，附上它要碰到的现场事实。 */
    public static Question approval(String situation, String request) {
        return new Question(Question.Reason.NEED_APPROVAL,
                // 没有把握说清要开哪一项许可时，就只问同不同意，不编一句"要继续就得：null"。
                request == null || request.isBlank() ? situation + "。同意这么做吗？"
                        : situation + "。要继续就得：" + request + "，可以吗？",
                List.of(new Question.Option("yes", "同意这么做"),
                        new Question.Option("no", "不同意，任务按没获得同意结束")));
    }

    /** 请 LLM 选一种做法：几种做法后果不同，附上各选项分别意味着什么。 */
    public static Question chooseOne(String situation, List<Question.Option> options) {
        Objects.requireNonNull(options, "options");
        return new Question(Question.Reason.CHOOSE_ONE, situation, options);
    }

    /** 目标对象说不清：例如有几个同名地标，把候选列成选项让 LLM 指认。 */
    public static Question unclearTarget(String situation, List<Question.Option> options) {
        Objects.requireNonNull(options, "options");
        return new Question(Question.Reason.UNCLEAR_TARGET, situation, options);
    }
}
