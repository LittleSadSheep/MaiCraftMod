// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import java.util.List;
import java.util.Objects;

/**
 * 向 LLM 提的一个问题。只允许三种原因（docs/design/03 的 M8），类型上就写不出别的：
 * 需要超出授权的同意、存在后果不同的多个选择、目标本身有歧义。
 *
 * <p>其他情况一律由伙伴内部换办法，或者带着事实结束；永远不问"要不要重试"，
 * 也不问"要不要先去弄张床"这种自己能补齐的前置。
 *
 * @param reason   为什么必须问
 * @param question 问题本身，写清现场事实
 * @param options  可选的回答
 */
public record Decision(Reason reason, String question, List<Option> options) {

    /** 允许提问的三种原因。 */
    public enum Reason {
        /** 需要超出当前授权的同意，例如要拆玩家盖的墙才能过去。 */
        CONSENT,
        /** 有几种后果不同的做法，Mod 不该替人选，例如动哪个箱子里的贵重材料。 */
        CHOICE,
        /** 目标本身有歧义，例如有两个同名地标。 */
        AMBIGUOUS_TARGET
    }

    /**
     * 一个可选回答。
     *
     * @param id      回答时使用的编号
     * @param meaning 选它意味着什么
     */
    public record Option(String id, String meaning) {
        public Option {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(meaning, "meaning");
        }
    }

    public Decision {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(question, "question");
        options = List.copyOf(options);
        if (options.isEmpty()) throw new IllegalArgumentException("提问至少要给一个可选回答");
    }
}
