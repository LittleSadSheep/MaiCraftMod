// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/** 发起真实玩家操作，并提供后续查询；提交一次动作与确认它做成了是两个阶段。 */
public interface NativeActionPort {
    NativeActionReceipt startBreaking(LocalPlayerContext context, BlockHitResult hit, int timeoutTicks);

    NativeActionReceipt cancelBreaking(LocalPlayerContext context, NativeActionReceipt receipt);

    /**
     * Request cancellation of a break whose task owner is about to disappear.  The actor boundary
     * performs the physical stop no later than its next tick, before another task may mutate the
     * body, so cancellation remains safe even when this tick's one mutation was already submitted.
     */
    NativeActionReceipt cancelBreakingForTaskBoundary(
            LocalPlayerContext context,
            NativeActionReceipt receipt,
            String boundaryReason);

    NativeActionReceipt continueBreaking(LocalPlayerContext context, NativeActionReceipt receipt);

    NativeActionReceipt useBlock(
            LocalPlayerContext context,
            InteractionHand hand,
            BlockHitResult hit,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt useItem(
            LocalPlayerContext context,
            InteractionHand hand,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt releaseUsingItem(LocalPlayerContext context, NativeActionReceipt receipt);

    /** 通过切换主手槽取消蓄力，不发送会让弓发射的 RELEASE_USE_ITEM。 */
    NativeActionReceipt cancelMainHandUse(LocalPlayerContext context, NativeActionReceipt receipt);

    NativeActionReceipt selectHotbar(LocalPlayerContext context, int slot, int timeoutTicks);

    /** 原生 Q 投掷只接受出手前完整主手快照；落点与实际扣减由调用方提供的只读回执一起核验。 */
    default NativeActionReceipt dropSelected(LocalPlayerContext context, ItemStack expectedSelected, boolean fullStack,
                                             NativeConfirmation confirmation, int timeoutTicks) {
        throw new UnsupportedOperationException("native selected-stack dropping is unavailable");
    }

    NativeActionReceipt creativeSetSlot(
            LocalPlayerContext context, int inventorySlot, ItemStack expected, int timeoutTicks);

    NativeActionReceipt submitProtocol(
            LocalPlayerContext context,
            String operation,
            Runnable submission,
            NativeConfirmation confirmation,
            int timeoutTicks);

    /** 不开菜单的模组世界控制，同样受玩家控制权和一次操作额度约束。 */
    NativeActionReceipt submitControlProtocol(
            LocalPlayerContext context,
            String operation,
            Runnable submission,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt attack(

            LocalPlayerContext context,
            Entity target,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt interact(
            LocalPlayerContext context,
            Entity target,
            InteractionHand hand,
            NativeConfirmation confirmation,
            int timeoutTicks);

    /**
     * Retire a submitted one-shot effect when its owning task ends before confirmation.
     *
     * <p>This does not pretend that the effect was rolled back: the returned receipt is marked
     * uncertain when its postcondition has not settled yet.  It only releases the serialized
     * actor slot so a discarded task-local receipt cannot block the next task.  Continuous native
     * actions ({@link NativeActionReceipt.Kind#BREAK_BLOCK BREAK_BLOCK} and
     * {@link NativeActionReceipt.Kind#USE_ITEM USE_ITEM}) must be physically stopped through their
     * dedicated APIs instead.
     */
    NativeActionReceipt retireOneShotForTaskBoundary(
            LocalPlayerContext context,
            NativeActionReceipt receipt,
            String boundaryReason);

    NativeActionReceipt poll(LocalPlayerContext context, NativeActionReceipt receipt);
}
