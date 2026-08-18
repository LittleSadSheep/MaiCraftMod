// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** Read-only proof that the ordinary build lane can place one block from a real stance. */
public final class FirstPersonPlacementProbe {

    private FirstPersonPlacementProbe() {}

    /**
     * Returns whether a block item has at least one loaded, already standable first-person
     * gesture for {@code target}.  This is stricter than the general construction preflight:
     * large builds may create an accounted scaffold, whereas temporary utility-block placement
     * must not silently turn a missing stance into a terrain-editing job.
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
