// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.retry;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 提问升级：只有超出许可的失败会升级成提问；三种原因各有一条升级路径；其他情况一律不问。 */
class QuestionEscalationTest {

    @Test
    void 超出许可的失败升级为请求同意的提问() {
        Problem refusal = Problem.of(Problem.Kind.NEED_APPROVAL, "要拆玩家盖的墙才能过去", "拆掉挡路的这面墙");
        Optional<Question> question = QuestionEscalation.escalate(refusal);
        assertTrue(question.isPresent());
        assertEquals(Question.Reason.NEED_APPROVAL, question.get().reason());
        // 卡在哪的事实要原样出现在问句里，LLM 不看任务日志也知道在问什么。
        assertTrue(question.get().text().contains("要拆玩家盖的墙才能过去"));
        assertTrue(question.get().text().contains("拆掉挡路的这面墙"));
        // 至少要有一条"不同意"的退路，不能把提问做成只能点头。
        assertTrue(question.get().options().stream().anyMatch(o -> o.id().equals("no")));
    }

    @Test
    void 其他种类的失败一律不升级_交给重试或结束() {
        for (Problem.Kind kind : Problem.Kind.values()) {
            if (kind == Problem.Kind.NEED_APPROVAL) continue;
            assertEquals(Optional.empty(),
                    QuestionEscalation.escalate(Problem.of(kind, "测试：" + kind)),
                    kind + " 不应升级为提问");
        }
    }

    @Test
    void 选做法的提问保留现场事实与各选项的含义() {
        Question question = QuestionEscalation.chooseOne(
                "要动箱子里的贵重材料补燃料",
                List.of(new Question.Option("chest_a", "动家门前箱子的煤"),
                        new Question.Option("chest_b", "动矿井口箱子的煤")));
        assertEquals(Question.Reason.CHOOSE_ONE, question.reason());
        assertEquals("要动箱子里的贵重材料补燃料", question.text());
        assertEquals(2, question.options().size());
    }

    @Test
    void 目标说不清的提问把候选列成选项() {
        Question question = QuestionEscalation.unclearTarget(
                "附近有两个都叫「家」的地标",
                List.of(new Question.Option("lm_1", "河边的那处"),
                        new Question.Option("lm_2", "山脚下的那处")));
        assertEquals(Question.Reason.UNCLEAR_TARGET, question.reason());
        assertTrue(question.text().contains("两个都叫「家」的地标"));
        assertEquals(2, question.options().size());
    }
}
