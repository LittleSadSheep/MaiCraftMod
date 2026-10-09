// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;

/**
 * 玩家行为层的交互动作入口：任务按这一刻要做的交互取一个动作，
 * 动作内部自己完成瞄准、核对命中、提交与逐刻确认。确认条件由调用方
 * （能力的手势）传入，这里不认识任何具体手势；站位与靠近由调用方负责，
 * 动作发现命中不了目标时用"到不了"的问题表达，由调用方换站位后重试。
 */
public final class Interactions {

    private final UseKeyProjection heldProjection;

    /**
     * @param heldProjection 按住使用键的投影；持续使用（进食、拉弓）靠它逐刻续期。
     *                       启动接线完成前可以传 null，此时持续使用仍能做，只是原版
     *       可能在确认期间看到松键，吃不完一块面包。
     */
    public Interactions(UseKeyProjection heldProjection) {
        this.heldProjection = heldProjection;
    }

    /** 右键一格方块（放置、开门、点火这类），生效与否按给定的确认条件核对。 */
    public AimAndInteract useBlock(BlockPos target, InteractionConfirmation confirmation) {
        return new AimAndInteract(new InteractionTarget.BlockTarget(target), confirmation,
                FirstPersonScene::of);
    }

    /**
     * 朝一格使用手里的物品：物品按自己的射线规则作用到那一格（水桶把水倒进去），生效与否按给定的确认条件核对。
     *
     * @param affected 物品要作用到的那一格，例如水该落进的那一格
     */
    public AimAndInteract useHeldItemToward(BlockPos affected, InteractionHand hand,
                                            InteractionConfirmation confirmation) {
        return new AimAndInteract(new InteractionTarget.BlockTarget(affected), AimAndInteract.Gesture.USE_HELD_ITEM,
                hand, confirmation, FirstPersonScene::of);
    }

    /** 右键一只实体（剪毛、挤奶、骑乘这类），准星必须真的落在它身上。 */
    public AimAndInteract useEntity(Entity target, InteractionConfirmation confirmation) {
        return new AimAndInteract(new InteractionTarget.EntityTarget(target), confirmation,
                FirstPersonScene::of);
    }

    /**
     * 按住使用手中物品：吃东西、拉弓这类持续手势。
     *
     * @param done          什么时候算做成了（由调用方按手势给确认条件）
     * @param maxHoldTicks  最多按住多少刻；零或负数表示等物品自行结束
     */
    public SustainedUse useHeldItem(InteractionHand hand, InteractionConfirmation done, int maxHoldTicks) {
        return new SustainedUse(hand, done, heldProjection, maxHoldTicks, FirstPersonScene::of);
    }
}
