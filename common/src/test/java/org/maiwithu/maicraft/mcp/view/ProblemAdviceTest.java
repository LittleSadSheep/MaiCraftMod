// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;

/** 问题没带建议时按种类给一句通用的下一步；带了建议照用。 */
class ProblemAdviceTest {
    @Test
    void 任务层写了建议就用它() {
        Problem problem = Problem.of(Problem.Kind.NEED_ITEM, "没有床", "带一张床来");
        assertEquals("带一张床来", ProblemAdvice.suggestionFor(problem));
    }

    @Test
    void 没写建议时每种问题都有一句下一步() {
        for (Problem.Kind kind : Problem.Kind.values()) {
            String advice = ProblemAdvice.suggestionFor(Problem.of(kind, "卡在哪"));
            assertFalse(advice.isBlank(), kind.name());
        }
        // 小模型最容易卡的两种：卡住了要换办法，不支持要说清不是 MCP 坏了。
        assertTrue(ProblemAdvice.suggestionFor(Problem.of(Problem.Kind.STUCK, "一分钟没进展")).contains("换"));
        assertTrue(ProblemAdvice.suggestionFor(Problem.of(Problem.Kind.UNSUPPORTED, "没装")).contains("不是 MCP 坏了"));
    }
}
