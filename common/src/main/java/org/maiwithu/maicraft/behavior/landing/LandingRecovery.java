// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.world.BucketWater;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 落地站稳后收回自己放的：水用同一只空桶舀回来，方块挖掉。每刻再核一遍：东西还是放下时的样子、
 * 没被划进保护、拿走后人还站得住；有一样不满足、空桶不在手上、挖不动、或收回超过时限，就留在原地如实说。
 * 收回点的是这一格本身：镜头已经转向它，射线正对着才出手。
 */
final class LandingRecovery {

    /** 挖掉放下的方块最多等这么久（再加上按挖掘速度算的时间）。 */
    private static final int BREAK_MARGIN_TICKS = 40;

    private final LandingRescue rescue;

    LandingRecovery(LandingRescue rescue) {
        this.rescue = rescue;
    }

    void recover(PlayerContext context, LocalPlayer player) {
        LandingPlan plan = rescue.plan();
        String name = LandingRescue.name(plan.aid());
        if (rescue.recoveryExpired(context.clientTick())) {
            rescue.finish("落地没掉血；放下的" + name + "没能在时限内收回，留在 " + plan.cell().toShortString());
            return;
        }
        if (!rescue.safeAfterRemoval(context, player)) {
            rescue.finish("落地没掉血；拿走" + name + "后脚下站不稳，留在 " + plan.cell().toShortString());
            return;
        }
        BlockPos cell = plan.cell();
        BlockState current = context.level().getBlockState(cell);
        boolean protectedCell = rescue.protectedNow(cell);
        if (!LandingPlan.canRecover(true, rescue.placed(), current, protectedCell)) {
            rescue.finish("落地没掉血；放下的" + name + "变了或被划进保护，留在 " + cell.toShortString());
            return;
        }
        if (!context.canInteractThisTick()) return;
        if (plan.aid() == LandingAid.WATER) {
            scoopWater(context, player, cell, current, protectedCell);
        } else {
            breakAid(context, player, cell);
        }
    }

    // 舀水：只舀本次放下的那一格水源，用放水时那只手里的空桶；舀上来以手里变回水桶、那一格没了水为准。
    private void scoopWater(PlayerContext context, LocalPlayer player, BlockPos cell, BlockState current,
                            boolean protectedCell) {
        if (!BucketWater.canRecover(current, true, protectedCell)) {
            rescue.finish("落地没掉血；只收自己放的那一格水源，水留在 " + cell.toShortString());
            return;
        }
        InteractionHand hand = rescue.item().hand();
        if (!player.getItemInHand(hand).is(Items.BUCKET)) {
            rescue.finish("落地没掉血；空桶不在手上，水留在 " + cell.toShortString());
            return;
        }
        BlockHitResult hit = rescue.trace(context, player, true);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(cell)) return;
        InteractionConfirmation scooped = c -> c.localPlayer().getItemInHand(hand).is(Items.WATER_BUCKET)
                && c.level().getFluidState(cell).isEmpty()
                ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
        rescue.beginCleaning(context.interactionSender().useItem(context, hand, scooped, 12));
    }

    // 挖掉：按这一格的挖掘速度给期限，挖不动（基岩一类、速度为零）就留着。
    private void breakAid(PlayerContext context, LocalPlayer player, BlockPos cell) {
        BlockHitResult hit = rescue.trace(context, player, false);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(cell)) return;
        float progress = rescue.placed().getDestroyProgress(player, context.level(), cell);
        if (!(progress > 0) || !Float.isFinite(progress)) {
            rescue.finish("落地没掉血；放下的" + LandingRescue.name(rescue.plan().aid()) + "挖不动，留在 "
                    + cell.toShortString());
            return;
        }
        int timeout = (int) Math.clamp(Math.ceil(1D / progress) + BREAK_MARGIN_TICKS, 20, 1200);
        rescue.beginCleaning(context.interactionSender().startBreaking(context, hit, timeout));
    }
}
