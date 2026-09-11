// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

/** A depot or thin pipe need not occupy the block center; aim at actual native outline geometry. */
public final class ProductionInteractionSight {
    private ProductionInteractionSight() {}

    public static boolean visible(Level level, Player player, BlockPos position) {
        return aimFrom(level, player, position, player.getEyePosition()) != null;
    }

    public static net.minecraft.world.phys.Vec3 aimFrom(Level level, Player player, BlockPos position,
                                                      net.minecraft.world.phys.Vec3 eyes) {
        if (!level.isLoaded(position)) return null;
        BlockPos eyeBlock = BlockPos.containing(eyes);
        int lowX = Math.min(eyeBlock.getX() >> 4, position.getX() >> 4);
        int highX = Math.max(eyeBlock.getX() >> 4, position.getX() >> 4);
        int lowZ = Math.min(eyeBlock.getZ() >> 4, position.getZ() >> 4);
        int highZ = Math.max(eyeBlock.getZ() >> 4, position.getZ() >> 4);
        if (highX - lowX > 2 || highZ - lowZ > 2) return null;
        for (int x = lowX; x <= highX; x++) for (int z = lowZ; z <= highZ; z++)
            if (!level.isLoaded(new BlockPos(x << 4, position.getY(), z << 4))) return null;
        var boxes = level.getBlockState(position).getShape(level, position, CollisionContext.of(player)).toAabbs();
        for (int i = 0; i < Math.min(32, boxes.size()); i++) {
            var point = boxes.get(i).move(position).getCenter();
            var hit = level.clip(new ClipContext(eyes, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position)) return point;
        }
        return null;
    }
}
