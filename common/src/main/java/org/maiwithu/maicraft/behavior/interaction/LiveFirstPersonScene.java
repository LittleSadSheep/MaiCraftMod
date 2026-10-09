// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.interaction.FirstPersonInteractionTargeting;
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
        // 原版准星规则：先找挡路方块，再看有没有更近的可点实体；方块与实体各用玩家自己的触及距离。
        return FirstPersonInteractionTargeting.crosshairTarget(player);
    }

    @Override public BlockHitResult visibleHit(BlockPos target) {
        // 先试形状中心，再试六个面内侧，射线命中的第一格必须就是目标、且在方块触及距离内。
        return FirstPersonInteractionTargeting.visibleBlockHit(player.level(), player, player.getEyePosition(),
                target, InteractionRange.blockReach(player));
    }

    @Override public BlockHitResult visiblePartHit(BlockPos target, AABB part) {
        // 只在部件框和方块形状重叠的那一块里试瞄准点：线缆上的面板，照整格瞄会点到线缆芯。
        return FirstPersonInteractionTargeting.visiblePartHit(player.level(), player, player.getEyePosition(),
                target, InteractionRange.blockReach(player), part);
    }

    @Override public BlockHitResult visibleItemHit(BlockPos target, InteractionHand hand) {
        Item item = player.getItemInHand(hand).getItem();
        double reach = InteractionRange.blockReach(player);
        if (FirstPersonInteractionTargeting.usesBucketRay(item)) {
            return FirstPersonInteractionTargeting.visibleBucketHit(
                    player.level(), player, player.getEyePosition(), target, reach, item);
        }
        return visibleHit(target);
    }

    @Override public boolean heldItemPointsAt(BlockPos target, InteractionHand hand) {
        Item item = player.getItemInHand(hand).getItem();
        if (!FirstPersonInteractionTargeting.usesBucketRay(item)) {
            return AimCheck.hitsBlock(sightRay(), target);
        }
        // 水桶不走准星的方块射线：沿当前视线按桶自己的流体规则射出去，再核对水会不会落进 target。
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(InteractionRange.blockReach(player)));
        BlockHitResult hit = FirstPersonInteractionTargeting.bucketRay(player.level(), player, eye, end, item);
        return FirstPersonInteractionTargeting.acceptsBucketHit(player.level(), target, item, hit);
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
