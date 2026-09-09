// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * 只读检查某一方块能否从现成站位放下，主要用于临时工作台选址；不在这里移动或放置。
 */
public final class FirstPersonPlacementProbe {

    private FirstPersonPlacementProbe() {}

    /**
     * 必须是方块物品，并且能找到已加载、当前就能站的点击方案。
     * 与大型建筑的预检不同，这里不为放一个临时工作台额外计划搭桥或垫高。
     */
    public static boolean hasExistingStance(
            LocalPlayer player, Block block, BlockPos target) {
        if (player == null || block == null || target == null) return false;
        Item item = block.asItem();
        if (!(item instanceof BlockItem)) return false;

        BuildTaskRecord.Target placement = new BuildTaskRecord.Target(
                block, item, target, block.getName().getString(),
                null, null, null).asItemPlace();
        Map<Long, BuildTaskRecord.Target> targets = Map.of(target.asLong(), placement);
        return BuildPlacementGeometry.hasAnyGesture(
                player, placement, targets, stance -> standable(player, stance));
    }

    // 这份站位判断要求脚和头所在整格没有碰撞或流体，脚下顶面能承重；它没有按半格脚高检查半砖站位。
    private static boolean standable(LocalPlayer player, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!player.level().isLoaded(feet)
                || !player.level().isLoaded(head)
                || !player.level().isLoaded(floor)
                || !player.level().getFluidState(feet).isEmpty()
                || !player.level().getFluidState(head).isEmpty()
                || !player.level().getBlockState(feet)
                        .getCollisionShape(player.level(), feet).isEmpty()
                || !player.level().getBlockState(head)
                        .getCollisionShape(player.level(), head).isEmpty()) {
            return false;
        }
        return player.level().getBlockState(floor)
                .isFaceSturdy(player.level(), floor, Direction.UP);
    }
}
