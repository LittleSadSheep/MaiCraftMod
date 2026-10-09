// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

/**
 * 组合确认的结算顺序：任一匹配即通过，还有等待项就继续等，全部结束才综合未生效或不一致；
 * 已拥有服务器发来的新实体证据时不再多等稳定刻。
 * 挖掉含水方块留下原液体的替换判定表依赖注册的原版方块，放到带离线引导的实机验收里核。
 */
class InteractionConfirmationTest {

    @Test
    void anyOfPassesOnTheFirstAppliedAndWaitsWhileAnythingIsPending() {
        InteractionConfirmation applied = c -> InteractionConfirmation.Verdict.APPLIED;
        InteractionConfirmation notApplied = c -> InteractionConfirmation.Verdict.NOT_APPLIED;
        InteractionConfirmation diverged = c -> InteractionConfirmation.Verdict.DIVERGED;
        assertEquals(InteractionConfirmation.Verdict.APPLIED,
                InteractionConfirmation.anyOf(notApplied, applied).observe(null));
        assertEquals(InteractionConfirmation.Verdict.PENDING,
                InteractionConfirmation.anyOf(notApplied, c -> InteractionConfirmation.Verdict.PENDING).observe(null));
        assertEquals(InteractionConfirmation.Verdict.NOT_APPLIED,
                InteractionConfirmation.anyOf(notApplied, notApplied).observe(null));
        assertEquals(InteractionConfirmation.Verdict.DIVERGED,
                InteractionConfirmation.anyOf(diverged, diverged).observe(null));
    }

    @Test
    void anyOfRejectsAnEmptyConditionList() {
        Assertions.assertThrows(IllegalArgumentException.class,
                InteractionConfirmation::anyOf);
    }

    @Test
    void serverObservedEntitySkipsTheDwell() {
        InteractionConfirmation evidence = c -> InteractionConfirmation.Verdict.APPLIED;
        assertEquals(2, evidence.stableTicksRequired(), "普通观察默认要连续两刻稳定");
        assertEquals(1, InteractionConfirmation.serverObservedEntity(evidence).stableTicksRequired(),
                "已拥有服务器发来的新实体证据时，不再多等一次稳定刻");
        assertTrue(InteractionConfirmation.pending().observe(null) == InteractionConfirmation.Verdict.PENDING);
    }

    @Test
    void strikeVerdictConfirmsOnAnyHitEvidence() {
        var verdict = InteractionConfirmation.Verdict.APPLIED;
        assertEquals(verdict, InteractionConfirmation.strikeVerdict(false, 20.0f, 20.0f, 0, 0, 1.0f),
                "目标死了算命中");
        assertEquals(verdict, InteractionConfirmation.strikeVerdict(true, 12.0f, 20.0f, 0, 0, 1.0f),
                "血量比出手前低算命中");
        assertEquals(verdict, InteractionConfirmation.strikeVerdict(true, 20.0f, 20.0f, 5, 0, 1.0f),
                "受击红闪算命中");
    }

    @Test
    void strikeVerdictConfirmsOnSwingWhenHitEvidenceIsMissing() {
        // 挥击已发生（攻击充能被这次出手清零，出手门槛是 0.95，恢复又远慢于确认窗）也算出手成立；
        // 充能还在出手线以上说明这一刀还没挥出去，继续等。
        assertEquals(InteractionConfirmation.Verdict.APPLIED,
                InteractionConfirmation.strikeVerdict(true, 20.0f, 20.0f, 0, 0, 0.8f));
        assertEquals(InteractionConfirmation.Verdict.PENDING,
                InteractionConfirmation.strikeVerdict(true, 20.0f, 20.0f, 0, 0, 1.0f));
    }

    @Test
    void strikeVerdictIgnoresAFlashThatWasAlreadyFading() {
        // 出手前目标就在红闪（刚被别人打过），之后还在倒数：不是这一刀的证据，充能也还满着，继续等。
        assertEquals(InteractionConfirmation.Verdict.PENDING,
                InteractionConfirmation.strikeVerdict(true, 20.0f, 20.0f, 5, 8, 1.0f));
        // 重新闪起来（比出手前长）才算这一刀打中。
        assertEquals(InteractionConfirmation.Verdict.APPLIED,
                InteractionConfirmation.strikeVerdict(true, 20.0f, 20.0f, 10, 8, 1.0f));
    }
}
