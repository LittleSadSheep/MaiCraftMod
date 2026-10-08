// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import java.util.List;
import java.util.Objects;

/**
 * 向 LLM 提的一个问题。只允许三种原因，类型上就写不出别的：
 * 要做的事超出许可、有几种后果不同的做法要选、目标对象本身说不清。
 *
 * <p>其他情况一律由角色自己换办法，或者带着事实结束；永远不问"要不要重试"，
 * 也不问"要不要先去弄张床"这种自己能补齐的事。
 *
 * @param reason  为什么必须问
 * @param text    问题本身，写清现场事实
 * @param options 可选的回答
 */
public record Question(Reason reason, String text, List<Option> options) {

    /** 允许提问的三种原因。 */
    public enum Reason {
        /** 要做的事超出了这次任务的许可，需要同意，例如要拆玩家盖的墙才能过去。 */
        NEED_APPROVAL,
        /** 有几种后果不同的做法，Mod 不该替 LLM 选，例如动哪个箱子里的贵重材料。 */
        CHOOSE_ONE,
        /** 目标对象本身说不清，例如有两个同名地标。 */
        UNCLEAR_TARGET
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

    public Question {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(text, "text");
        options = List.copyOf(options);
        if (options.isEmpty()) throw new IllegalArgumentException("提问至少要给一个可选回答");
    }
}
