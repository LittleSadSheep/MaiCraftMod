// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一目标攻击的重提交幂等等待：前一刀还在等确认时不重复挥手也不拒绝，
 * 判定只认"同一目标、同一角色、还没过期限"的攻击等待；过期或换人按旧账回收，不许蒙混。
 */
class AttackResubmissionWaitTest {

    private static PendingInteraction attackPending(FakePlayerContext context, int targetId, int timeoutTicks) {
        PendingInteraction pending = new PendingInteraction(
                PendingInteraction.Kind.ATTACK_ENTITY, context, timeoutTicks, 2,
                InteractionConfirmation.pending(), null, null);
        pending.attachAttackTarget(targetId);
        return pending;
    }

    @Test
    void theSameTargetStillAwaitingCountsAsTheSameSwing() {
        FakePlayerContext context = new FakePlayerContext(null);
        assertTrue(DefaultInteractionSender.sameTargetAttackAwaiting(
                attackPending(context, 42, 20), context, 42));
    }

    @Test
    void aDifferentTargetMustNotInheritTheWait() {
        FakePlayerContext context = new FakePlayerContext(null);
        assertFalse(DefaultInteractionSender.sameTargetAttackAwaiting(
                attackPending(context, 42, 20), context, 43),
                "换目标的挥击是新的出手，不能把旧目标的等待蒙上去");
    }

    @Test
    void anExpiredWaitDoesNotCountAndGetsReclaimedInstead() {
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction pending = attackPending(context, 42, 20);
        context.tick += 21;
        assertFalse(DefaultInteractionSender.sameTargetAttackAwaiting(pending, context, 42),
                "过了确认窗的等待按僵尸回收，不按幂等等待放行");
    }

    @Test
    void aSettledOrMissingWaitDoesNotCount() {
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction settled = attackPending(context, 42, 20);
        settled.finish(PendingInteraction.Status.CONFIRMED_APPLIED, "hit");
        assertFalse(DefaultInteractionSender.sameTargetAttackAwaiting(settled, context, 42),
                "已结算的等待不再挡新的出手");
        assertFalse(DefaultInteractionSender.sameTargetAttackAwaiting(null, context, 42));
    }

    @Test
    void theWaitIsJudgedOnTheFrozenSubmissionRecordNotTheCaller() {
        // LocalPlayer 无法在离线测试里构造，换人分支由 fromSamePlayer 的冻结引用比较承担
        //（见 PendingInteractionTest）；这里只验证判定读的是等待记录本身。
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction pending = attackPending(context, 42, 20);
        assertTrue(DefaultInteractionSender.sameTargetAttackAwaiting(pending, new FakePlayerContext(null), 42));
    }
}
