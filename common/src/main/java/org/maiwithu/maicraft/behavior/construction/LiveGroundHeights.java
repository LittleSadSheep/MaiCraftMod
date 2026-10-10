// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import org.maiwithu.maicraft.game.player.PlayerContext;

/** 地表高度的生产实现：按客户端同步下来的高度图读那一列最上面挡得住身体的方块，设计原点落在它上面那一格。 */
public final class LiveGroundHeights implements AnchorResolver.GroundHeights {

    private final Supplier<PlayerContext> context;

    public LiveGroundHeights(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public Optional<Integer> surfaceY(int x, int z) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null || !level.hasChunkAt(new BlockPos(x, 0, z))) return Optional.empty();
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return top <= level.getMinBuildHeight() ? Optional.empty() : Optional.of(top);
    }
}
