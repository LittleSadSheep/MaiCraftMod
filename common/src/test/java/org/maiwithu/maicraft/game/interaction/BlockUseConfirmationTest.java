// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 方块操作的服务器确认门：客户端先显示的预测不能单独证明放置成功，
 * 必须等操作编号被服务器确认并完成本地校正，编号没变说明本地就拒绝了、没有发包。
 */
class BlockUseConfirmationTest {

    /** 记录当前与已确认的方块操作编号，替身不接触真实世界。 */
    private static final class FakeAcknowledgement implements BlockUseAcknowledgement {
        int current;
        int acknowledged;

        @Override public int maicraft$currentBlockSequence() { return current; }
        @Override public int maicraft$acknowledgedBlockSequence() { return acknowledged; }
    }

    @Test
    void aLocallyRefusedUseWithUnchangedEffectsNeedNotWaitForAPacket() {
        // 编号从 12 到 12 没动：本地就拒绝了，没有发包；“没生效”的现场观察可以直接作结论。
        FakeAcknowledgement noPacket = new FakeAcknowledgement();
        noPacket.current = 12;
        noPacket.acknowledged = 8;
        BlockUseConfirmation refused = new BlockUseConfirmation(
                c -> InteractionConfirmation.Verdict.NOT_APPLIED, noPacket);
        refused.submitted();
        assertEquals(InteractionConfirmation.Verdict.NOT_APPLIED, refused.observe(null));
    }

    @Test
    void anUnsequencedLocalChangeCannotMasqueradeAsAConfirmedPlacement() {
        // 没发出操作，现场却出现了成功变化：不能把这笔变化归给一个没发出的点击，报不一致。
        FakeAcknowledgement noPacket = new FakeAcknowledgement();
        noPacket.current = 12;
        noPacket.acknowledged = 8;
        BlockUseConfirmation unexplained = new BlockUseConfirmation(
                c -> InteractionConfirmation.Verdict.APPLIED, noPacket);
        unexplained.submitted();
        assertEquals(InteractionConfirmation.Verdict.DIVERGED, unexplained.observe(null));
    }

    @Test
    void matchingEffectsWaitForTheirOwnAcknowledgement() {
        FakeAcknowledgement sequences = new FakeAcknowledgement();
        sequences.current = 5;
        BlockUseConfirmation pending = new BlockUseConfirmation(
                c -> InteractionConfirmation.Verdict.APPLIED, sequences);
        // 原版调用在提交过程中把编号从 5 推到 6；这里先推进再提交，模拟同一次 useItemOn。
        sequences.current = 6;
        pending.submitted();
        // 预测包已发出，但服务器还没确认这个编号：成功观察也只能等。
        assertEquals(InteractionConfirmation.Verdict.PENDING, pending.observe(null));
        // 服务器确认并完成本地校正后，同样的成功观察立刻生效。
        sequences.acknowledged = 6;
        assertEquals(InteractionConfirmation.Verdict.APPLIED, pending.observe(null));
    }

    @Test
    void anUnsubmittedConfirmationKeepsWaiting() {
        FakeAcknowledgement sequences = new FakeAcknowledgement();
        BlockUseConfirmation unsubmitted = new BlockUseConfirmation(
                c -> InteractionConfirmation.Verdict.APPLIED, sequences);
        // 还没提交过就不看内部条件：放置的观察从真实提交之后才开始，提前查询只能等。
        assertEquals(InteractionConfirmation.Verdict.PENDING, unsubmitted.observe(null));
        assertFalse(unsubmitted.requiresBlockAcknowledgement());
        assertEquals(1, unsubmitted.stableTicksRequired(),
                "过门之后不再多等稳定刻：服务器编号本身就是一次确认");
    }
}
