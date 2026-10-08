// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.retry;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Attempt;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 重试策略：按问题种类分流到再试、换办法或结束；同一种失败有上限；放弃时问题携带已试办法与建议。 */
class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(new Backoff(20, 200), 3);

    private static Problem failure(Problem.Kind kind) {
        return Problem.of(kind, "测试失败：" + kind);
    }

    @Test
    void 会随时间消失的失败用同一个办法再试并退避() {
        RetryDecision decision = policy.afterFailure(failure(Problem.Kind.WRONG_TIME), 1, 2);
        RetryDecision.RetrySame retry = assertInstanceOf(RetryDecision.RetrySame.class, decision);
        assertEquals(20L, retry.waitTicks());
    }

    @Test
    void 游戏拒绝也值得再试_退避随失败次数加倍() {
        RetryDecision decision = policy.afterFailure(
                Problem.of(Problem.Kind.REFUSED_BY_GAME, "服务器忙"), 2, 0);
        RetryDecision.RetrySame retry = assertInstanceOf(RetryDecision.RetrySame.class, decision);
        assertEquals(40L, retry.waitTicks());
    }

    @Test
    void 同一种失败到上限且有办法可换就换办法() {
        RetryDecision decision = policy.afterFailure(failure(Problem.Kind.WRONG_TIME), 3, 1);
        assertEquals(RetryDecision.TRY_NEXT, decision);
    }

    @Test
    void 同一种失败到上限且没办法可换就放弃() {
        RetryDecision decision = policy.afterFailure(failure(Problem.Kind.REFUSED_BY_GAME), 3, 0);
        RetryDecision.GiveUp giveUp = assertInstanceOf(RetryDecision.GiveUp.class, decision);
        assertEquals(Problem.Kind.STUCK, giveUp.problem().kind());
    }

    @Test
    void 到不了和目标没了换下一个办法() {
        assertEquals(RetryDecision.TRY_NEXT, policy.afterFailure(failure(Problem.Kind.UNREACHABLE), 1, 3));
        assertEquals(RetryDecision.TRY_NEXT, policy.afterFailure(failure(Problem.Kind.TARGET_GONE), 1, 3));
    }

    @Test
    void 换办法类的失败没有候选了就放弃() {
        RetryDecision decision = policy.afterFailure(failure(Problem.Kind.UNREACHABLE), 1, 0);
        RetryDecision.GiveUp giveUp = assertInstanceOf(RetryDecision.GiveUp.class, decision);
        assertEquals(Problem.Kind.STUCK, giveUp.problem().kind());
        assertTrue(giveUp.problem().message().contains("UNREACHABLE"));
    }

    @Test
    void 改变不了事实的失败不重试_问题原样带回() {
        Problem needItem = Problem.of(Problem.Kind.NEED_ITEM, "没有床，附近也没有羊", "带一张床来");
        RetryDecision decision = policy.afterFailure(needItem, 1, 5);
        RetryDecision.GiveUp giveUp = assertInstanceOf(RetryDecision.GiveUp.class, decision);
        // 原样：种类、事实与建议都不能在分流里丢掉或改写。
        assertEquals(needItem, giveUp.problem());
    }

    @Test
    void 超出许可的失败不在这里悄悄处理_按结束原样带回() {
        Problem approval = Problem.of(Problem.Kind.NEED_APPROVAL, "要拆玩家盖的墙才能过去", null);
        RetryDecision decision = policy.afterFailure(approval, 1, 2);
        RetryDecision.GiveUp giveUp = assertInstanceOf(RetryDecision.GiveUp.class, decision);
        assertEquals(approval, giveUp.problem());
    }

    @Test
    void 放弃的问题携带已试办法摘要与建议() {
        List<Attempt> attempts = List.of(
                new Attempt("从门口那一侧靠近", "被墙挡住，看不见床头"),
                new Attempt("从窗户靠近", "窗户太高够不着"));
        Problem problem = policy.giveUp(failure(Problem.Kind.UNREACHABLE), attempts, "把窗户下的方块拆掉再试");
        assertEquals(Problem.Kind.STUCK, problem.kind());
        assertTrue(problem.message().contains("从门口那一侧靠近（被墙挡住，看不见床头）"));
        assertTrue(problem.message().contains("从窗户靠近（窗户太高够不着）"));
        assertEquals("把窗户下的方块拆掉再试", problem.suggestion());
    }

    @Test
    void 没有把握的建议就留空_不编造() {
        Problem problem = policy.giveUp(failure(Problem.Kind.UNREACHABLE), List.of(), null);
        assertEquals(Problem.Kind.STUCK, problem.kind());
        assertEquals(null, problem.suggestion());
    }

    @Test
    void 重试上限必须为正() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(new Backoff(20, 200), 0));
    }
}
