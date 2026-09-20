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
     * 挖掘任务即将结束时登记停手请求；身体边界最迟在下一游戏刻实际停止挖掘，
     * 再允许后继任务操作身体，避免本刻已经用完操作额度时留下持续破坏动作。
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
     * 单次操作尚未确认而所属任务已结束时，释放该任务占用的身体操作槽。
     * 后置状态未稳定的回执仍标为不确定，不把释放槽位当成撤销已经发生的效果。
     * 持续挖掘（{@link NativeActionReceipt.Kind#BREAK_BLOCK BREAK_BLOCK}）和持续使用物品
     * （{@link NativeActionReceipt.Kind#USE_ITEM USE_ITEM}）须调用各自的停手接口。
     */
    NativeActionReceipt retireOneShotForTaskBoundary(
            LocalPlayerContext context,
            NativeActionReceipt receipt,
            String boundaryReason);

    NativeActionReceipt poll(LocalPlayerContext context, NativeActionReceipt receipt);
}
