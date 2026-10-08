// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.interaction.Interaction;
import org.maiwithu.maicraft.game.world.InteractionRange;

/**
 * 真实游戏里的第一人称现场：全部读自当前本地玩家与世界，不做任何判断。
 * 射线沿用游戏接口层的原版准星规则，方块与实体的触及距离读玩家属性。
 */
public final class LiveFirstPersonScene implements FirstPersonScene {
    private final LocalPlayer player;

    public LiveFirstPersonScene(LocalPlayer player) {
        this.player = player;
    }

    @Override public Vec3 eyePosition() {
        return player.getEyePosition();
    }

    @Override public Vec3 viewVector() {
        return player.getViewVector(1.0f);
    }

    @Override public HitResult sightRay() {
        // 原版准星规则：先找挡路方块，再看有没有更近的可点实体；距离用玩家自己的交互属性。
        return Interaction.nativeRaytrace(player, InteractionRange.blockReach(player));
    }

    @Override public BlockState blockAt(BlockPos pos) {
        return player.level().isLoaded(pos) ? player.level().getBlockState(pos) : null;
    }

    @Override public boolean isLoaded(BlockPos pos) {
        return player.level().isLoaded(pos);
    }

    @Override public ItemStack heldItem(InteractionHand hand) {
        return player.getItemInHand(hand).copy();
    }
}
