// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.util;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/** Client-safe equivalents of heightmaps that are maintained only by the live server world. */
public final class ClientSurfaceHeight {

    private ClientSurfaceHeight() {}

    /**
     * Returns the first free cell above the highest motion-blocking, non-leaf block.
     *
     * <p>{@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES} is not synchronized in client chunk
     * packets. Querying it directly therefore reads the client's unpopulated map and can return
     * the minimum build height. The synchronized {@link Heightmap.Types#MOTION_BLOCKING} map is a
     * safe upper bound because its predicate is a superset; scanning down with the original
     * no-leaves predicate preserves the requested semantics without mutating chunk heightmaps.
     */
    public static int motionBlockingNoLeaves(ClientLevel level, int x, int z) {
        int minY = level.getMinBuildHeight();
        int upperY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = upperY - 1; y >= minY; y--) {
            cursor.set(x, y, z);
            if (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque()
                    .test(level.getBlockState(cursor))) {
                return y + 1;
            }
        }
        return minY;
    }
}
