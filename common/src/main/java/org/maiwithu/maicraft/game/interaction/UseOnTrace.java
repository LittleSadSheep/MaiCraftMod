// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 一次右键方块提交现场的逐项事实：提交的点击面与位置、客户端原生预测结果、
 * 点击后客户端观察到的落格状态，以及方块操作编号是否前进（前进＝预测包已发出，
 * 服务端收到了这次点击；不前进＝客户端本地就拒绝了，根本没有发包）。
 * 只读观察与字符串拼装，不改变任何世界状态；随确认记录走，排查"点击被谁拒了"用。
 */
final class UseOnTrace {

    private UseOnTrace() {}

    static String describe(PlayerContext context, InteractionHand hand, BlockHitResult hit,
                           InteractionResult result, int sequenceBefore) {
        try {
            var level = context.level();
            var player = context.localPlayer();
            var clicked = hit.getBlockPos();
            var placementCell = clicked.relative(hit.getDirection());
            int sequenceAfter = level instanceof BlockUseAcknowledgement acknowledgement
                    ? acknowledgement.maicraft$currentBlockSequence() : Integer.MIN_VALUE;
            BiFunction<BlockPos, BlockState, String> describe =
                    (pos, state) -> pos.toShortString() + "=" + BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return "use_on{hand=" + hand
                    + ", held=" + BuiltInRegistries.ITEM.getKey(
                            player.getItemInHand(hand).getItem()) + "x" + player.getItemInHand(hand).getCount()
                    + ", clicked=" + describe.apply(clicked, level.getBlockState(clicked))
                    + ", face=" + hit.getDirection()
                    + ", point=(" + Math.round(hit.getLocation().x * 100) / 100.0
                    + "," + Math.round(hit.getLocation().y * 100) / 100.0
                    + "," + Math.round(hit.getLocation().z * 100) / 100.0 + ")"
                    + ", eye_dist=" + Math.round(player.getEyePosition().distanceTo(hit.getLocation()) * 100) / 100.0
                    + ", feet=" + player.blockPosition().toShortString()
                    + ", sneak=" + player.isShiftKeyDown()
                    + ", native_result=" + result
                    + ", placement_cell=" + describe.apply(placementCell, level.getBlockState(placementCell))
                    + ", prediction_packet=" + (sequenceBefore == Integer.MIN_VALUE ? "unknown"
                            : sequenceAfter > sequenceBefore ? "sent" : "none")
                    + "}";
        } catch (RuntimeException traceFailure) {
            return "use_on{trace_failed=" + traceFailure + "}";
        }
    }
}
