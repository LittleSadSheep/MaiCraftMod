// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 测试用的交互提交替身：按脚本返回确认结果，记录每次提交的现场，不接触真实客户端。
 * 测试先提交动作，再设置下一次查询返回的终态，就能驱动"提交 → 逐刻确认"的编排走完。
 */
public final class ScriptedInteractionSender implements InteractionSender {

    /** 一次提交的现场：提交了什么、带的确认条件与期限。 */
    public record Submission(PendingInteraction.Kind kind, BlockHitResult hit,
                             InteractionConfirmation confirmation, int timeoutTicks) {}

    public final List<Submission> submissions = new ArrayList<>();
    /** 最近一次提交（含松手）的确认记录；测试据此设置脚本结果。 */
    public PendingInteraction last;
    /** 下一次 poll 返回的终态与原因；置 null 表示继续等。 */
    public PendingInteraction.Status nextStatus;
    public String nextDetail = "";
    /** 持续使用是否仍归提交方管；测试用它模拟归属变化。 */
    public boolean ownsUse = true;
    public boolean retireCalled;
    public int releaseCalls;

    @Override public PendingInteraction useBlock(PlayerContext context, InteractionHand hand, BlockHitResult hit,
                                                 InteractionConfirmation confirmation, int timeoutTicks) {
        PendingInteraction pending = new PendingInteraction(
                PendingInteraction.Kind.USE_BLOCK, context, timeoutTicks, 1,
                confirmation, hit.getBlockPos(), hit.getDirection());
        submissions.add(new Submission(pending.kind(), hit, confirmation, timeoutTicks));
        last = pending;
        return pending;
    }

    @Override public PendingInteraction useItem(PlayerContext context, InteractionHand hand,
                                                InteractionConfirmation confirmation, int timeoutTicks) {
        PendingInteraction pending = new PendingInteraction(
                PendingInteraction.Kind.USE_ITEM, context, timeoutTicks, 1, confirmation, null, null);
        submissions.add(new Submission(pending.kind(), null, confirmation, timeoutTicks));
        last = pending;
        return pending;
    }

    @Override public PendingInteraction releaseUsingItem(PlayerContext context, PendingInteraction pending) {
        releaseCalls++;
        PendingInteraction release = new PendingInteraction(
                PendingInteraction.Kind.RELEASE_ITEM, context, 10, 1,
                InteractionConfirmation.itemUseStopped(), null, null);
        last = release;
        return release;
    }

    @Override public PendingInteraction poll(PlayerContext context, PendingInteraction pending) {
        if (pending.terminal()) return pending;
        if (nextStatus != null && pending == last) {
            pending.finish(nextStatus, nextDetail);
            nextStatus = null;
        }
        return pending;
    }

    @Override public boolean ownsItemUse(PendingInteraction pending) {
        return ownsUse;
    }

    @Override public void deferBreakCancellationForTaskBoundary(PendingInteraction pending, String boundaryReason) {}

    @Override public void abandonOneShotForTaskBoundary(PendingInteraction pending, String boundaryReason) {
        if (!pending.terminal()) {
            pending.finish(PendingInteraction.Status.UNCERTAIN, boundaryReason);
        }
    }

    @Override public PendingInteraction retireOneShotForTaskBoundary(
            PlayerContext context, PendingInteraction pending, String boundaryReason) {
        retireCalled = true;
        if (!pending.terminal()) {
            pending.finish(PendingInteraction.Status.UNCERTAIN, boundaryReason);
        }
        return pending;
    }

    @Override public PendingInteraction startBreaking(PlayerContext context, BlockHitResult hit, int timeoutTicks) {
        throw new UnsupportedOperationException("替身没有实现这个入口");
    }

    @Override public PendingInteraction cancelBreaking(PlayerContext context, PendingInteraction pending) {
        throw new UnsupportedOperationException("替身没有实现这个入口");
    }

    @Override public PendingInteraction cancelBreakingForTaskBoundary(
            PlayerContext context, PendingInteraction pending, String boundaryReason) {
        throw new UnsupportedOperationException("替身没有实现这个入口");
    }

    @Override public PendingInteraction continueBreaking(PlayerContext context, PendingInteraction pending) {
        throw new UnsupportedOperationException("替身没有实现这个入口");
    }

    @Override public PendingInteraction cancelMainHandUse(PlayerContext context, PendingInteraction pending) {
        throw new UnsupportedOperationException("替身没有实现这个入口");
    }

    @Override public PendingInteraction selectHotbar(PlayerContext context, int slot, int timeoutTicks) {
        throw new UnsupportedOperationException("替身没有实现这个入口");
    }

    @Override public PendingInteraction creativeSetSlot(
            PlayerContext context, int inventorySlot, ItemStack expected, int timeoutTicks) {
        throw new UnsupportedOperationException("替身没有实现这个入口");
    }

    @Override public PendingInteraction submitProtocol(
            PlayerContext context, String operation, Runnable submission,
            InteractionConfirmation confirmation, int timeoutTicks) {
        throw new UnsupportedOperationException("替身没有实现这个入口");
    }

    @Override public PendingInteraction submitControlProtocol(
            PlayerContext context, String operation, Runnable submission,
            InteractionConfirmation confirmation, int timeoutTicks) {
        throw new UnsupportedOperationException("替身没有实现这个入口");
    }

    @Override public PendingInteraction attack(
            PlayerContext context, Entity target, InteractionConfirmation confirmation, int timeoutTicks) {
        throw new UnsupportedOperationException("替身没有实现这个入口");
    }

    @Override public PendingInteraction interact(
            PlayerContext context, Entity target, InteractionHand hand,
            InteractionConfirmation confirmation, int timeoutTicks) {
        throw new UnsupportedOperationException("替身没有实现这个入口");
    }
}
