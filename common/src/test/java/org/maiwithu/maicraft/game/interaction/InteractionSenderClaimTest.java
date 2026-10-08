// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * 交互提交真正发包前都要占用本刻唯一的交互机会：本刻已经出过手、或人按 F8 接回了控制权，
 * 提交都被拒绝，不会悄悄再发一个点击。
 */
class InteractionSenderClaimTest {

    private static final BlockHitResult HIT =
            new BlockHitResult(Vec3.atCenterOf(new BlockPos(0, 64, 0)), Direction.UP, new BlockPos(0, 64, 0), false);

    @Test
    void submissionWithoutAnOpportunityIsRefusedBeforeTouchingTheGame() {
        DefaultInteractionSender sender = new DefaultInteractionSender(null);
        FakePlayerContext context = new FakePlayerContext(null);
        context.canInteract = false;

        assertThrows(IllegalStateException.class, () -> sender.useBlock(
                context, InteractionHand.MAIN_HAND, HIT, observed -> InteractionConfirmation.Verdict.APPLIED, 20));
        assertThrows(IllegalStateException.class, () -> sender.useItem(
                context, InteractionHand.MAIN_HAND, observed -> InteractionConfirmation.Verdict.APPLIED, 20));
        assertThrows(IllegalStateException.class, () -> sender.startBreaking(context, HIT, 20));
    }

    @Test
    void oneOpportunityPerTickAndItComesBackNextTick() {
        FakePlayerContext context = new FakePlayerContext(null);
        assertTrue(context.tryClaimInteraction());
        assertFalse(context.canInteractThisTick(), "同一刻用过就没有了");
        assertFalse(context.tryClaimInteraction());
        context.nextTick();
        assertTrue(context.tryClaimInteraction(), "下一刻自动恢复");
    }
}
