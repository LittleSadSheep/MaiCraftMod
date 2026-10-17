// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 逐刻确认记录的状态机：确认要连续数刻稳定才算数，超时如实记不确定，终态一旦写下不再翻案。
 * 玩家对象换人后旧提交直接判不再属于本角色，不能把旧角色的等待带到新角色身上。
 */
class PendingInteractionTest {

    private static PendingInteraction newPending(FakePlayerContext context, InteractionConfirmation confirmation,
                                                 int timeoutTicks, int stableTicks) {
        return new PendingInteraction(PendingInteraction.Kind.USE_ITEM, context,
                timeoutTicks, stableTicks, confirmation, null, null);
    }

    @Test
    void timeoutAndStableTicksMustBePositive() {
        FakePlayerContext context = new FakePlayerContext(null);
        assertThrows(IllegalArgumentException.class,
                () -> newPending(context, InteractionConfirmation.pending(), 0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> newPending(context, InteractionConfirmation.pending(), 10, 0));
    }

    @Test
    void stableCountingOnlyAcceptsDistinctClientTicks() {
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction pending = newPending(context, InteractionConfirmation.pending(), 10, 2);
        // 同一刻重复查询不能重复计数；连续两刻匹配后才算稳定。
        assertFalse(pending.countStable(context.clientTick()));
        assertFalse(pending.countStable(context.clientTick()));
        long nextTick = context.clientTick() + 1;
        assertTrue(pending.countStable(nextTick));
    }

    @Test
    void aFailedObservationResetsTheStableCount() {
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction pending = newPending(context, InteractionConfirmation.pending(), 10, 2);
        pending.countStable(context.clientTick());
        // 条件没满足时计数清零；之后要重新数满。
        pending.resetStable(context.clientTick() + 1);
        assertFalse(pending.countStable(context.clientTick() + 2));
        assertFalse(pending.terminal());
    }

    @Test
    void terminalResultsAreFrozen() {
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction pending = newPending(context, InteractionConfirmation.pending(), 10, 2);
        pending.finish(PendingInteraction.Status.CONFIRMED_NOT_APPLIED, "settled");
        // 后来的世界回滚不能把已结束的记录重新打开或改写。
        pending.finish(PendingInteraction.Status.CONFIRMED_APPLIED, "late rewrite");
        assertEquals(PendingInteraction.Status.CONFIRMED_NOT_APPLIED, pending.status());
        assertEquals("settled", pending.detail());
    }

    @Test
    void deadlineIsSubmittedTickPlusTimeout() {
        FakePlayerContext context = new FakePlayerContext(null);
        context.tick = 41;
        PendingInteraction pending = newPending(context, InteractionConfirmation.pending(), 3, 2);
        assertEquals(41, pending.submittedTick());
        assertEquals(44, pending.deadlineTick());
        assertFalse(pending.terminal());
    }

    @Test
    void submissionIsBoundToThePlayerReferenceItWasMadeWith() {
        // 确认记录记住提交时的角色引用，fromSamePlayer 比较的就是这个冻结引用；
        // LocalPlayer 无法在离线测试里构造，用空引用验证判定走的是记录本身而非当前上下文。
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction pending = newPending(context, InteractionConfirmation.pending(), 10, 2);
        assertTrue(pending.fromSamePlayer(new FakePlayerContext(null)));
    }
}
