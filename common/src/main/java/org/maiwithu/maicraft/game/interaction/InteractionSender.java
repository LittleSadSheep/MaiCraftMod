// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 提交真实玩家操作并逐刻确认：提交一次动作与确认它做成了是两个阶段。
 * 同一时刻只允许一项动作在等确认；确认前不能提交下一次。
 */
public interface InteractionSender {

    /**
     * 副手放置只借用本刻空闲的交互机会；确认独立等待，不占住主任务下一刻的挖掘或交互。
     * 本刻主任务已经出过手、或有旧动作没收尾时返回 {@code null}，由调用方改刻再试。
     */
    default PendingInteraction tryAuxiliaryBlockUse(
            PlayerContext context, BlockHitResult hit,
            InteractionConfirmation confirmation, int timeoutTicks) {
        return null;
    }

    PendingInteraction startBreaking(PlayerContext context, BlockHitResult hit, int timeoutTicks);

    PendingInteraction cancelBreaking(PlayerContext context, PendingInteraction pending);

    /**
     * 任务即将结束时登记停手请求；游戏接口层最迟在下一游戏刻实际停止挖掘，
     * 再允许后继任务操作角色，避免本刻已经用完交互机会时留下持续破坏动作。
     */
    PendingInteraction cancelBreakingForTaskBoundary(
            PlayerContext context,
            PendingInteraction pending,
            String boundaryReason);

    PendingInteraction continueBreaking(PlayerContext context, PendingInteraction pending);

    PendingInteraction useBlock(
            PlayerContext context,
            InteractionHand hand,
            BlockHitResult hit,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    PendingInteraction useItem(
            PlayerContext context,
            InteractionHand hand,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    PendingInteraction releaseUsingItem(PlayerContext context, PendingInteraction pending);

    /** 通过切换主手槽取消蓄力，不发送会让弓发射的 RELEASE_USE_ITEM。 */
    PendingInteraction cancelMainHandUse(PlayerContext context, PendingInteraction pending);

    PendingInteraction selectHotbar(PlayerContext context, int slot, int timeoutTicks);

    /** 原生 Q 投掷只接受出手前完整主手快照；落点与实际扣减由调用方提供的只读确认一起核验。 */
    default PendingInteraction dropSelected(
            PlayerContext context, ItemStack expectedSelected, boolean fullStack,
            InteractionConfirmation confirmation, int timeoutTicks) {
        throw new UnsupportedOperationException("native selected-stack dropping is unavailable");
    }

    PendingInteraction creativeSetSlot(
            PlayerContext context,
            int inventorySlot, ItemStack expected, int timeoutTicks);

    /** 经模组原生协议提交的菜单动作；菜单协议必须先有可见界面，并占用同一份逐刻确认等待。 */
    PendingInteraction submitProtocol(
            PlayerContext context,
            String operation,
            Runnable submission,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    /** 不开菜单的模组世界控制，同样受角色控制权和一次交互机会约束。 */
    PendingInteraction submitControlProtocol(
            PlayerContext context,
            String operation,
            Runnable submission,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    PendingInteraction attack(
            PlayerContext context,
            Entity target,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    PendingInteraction interact(
            PlayerContext context,
            Entity target,
            InteractionHand hand,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    /** 这次持续使用是否仍归提交方管理；只有它自己认下的使用才允许继续按住或松开。 */
    default boolean ownsItemUse(PendingInteraction pending) { return false; }

    /** 有经菜单模组协议提交的动作还没确认时为真；界面收尾要等它结清。 */
    default boolean hasPendingMenuTransaction() { return false; }

    /**
     * 单次操作尚未确认而所属任务已结束时，释放该任务占用的交互等待。
     * 结果未定的提交仍记为不确定，不把释放等待当成撤销已经发生的效果。
     * 持续挖掘和持续使用物品须调用各自的停手接口，不能在这里装作结束。
     */
    PendingInteraction retireOneShotForTaskBoundary(
            PlayerContext context,
            PendingInteraction pending,
            String boundaryReason);

    /** 逐刻结算：先核对上下文仍是本刻、动作仍是本角色的，再运行只读确认条件。 */
    PendingInteraction poll(PlayerContext context, PendingInteraction pending);
}
