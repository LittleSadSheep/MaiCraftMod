// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 照明现场的生产读取：从当刻的客户端世界读方块与支撑面。支撑面结实不结实按原版的放置支撑规则回答
 * （能瞄准那一面并以此为支撑放置才算），照明选灯位与采样都从这里看现场；测试换替身。
 */
public final class LiveLightingWorld implements LightingWorld {

    private final Supplier<PlayerContext> contexts;

    public LiveLightingWorld(Supplier<PlayerContext> contexts) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override public boolean loaded(BlockPos pos) {
        PlayerContext context = contexts.get();
        return context != null && context.level() != null && context.level().isLoaded(pos);
    }

    @Override public BlockState state(BlockPos pos) {
        PlayerContext context = contexts.get();
        if (context == null || context.level() == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return context.level().getBlockState(pos);
    }

    @Override public boolean sturdy(BlockPos pos, Direction face) {
        PlayerContext context = contexts.get();
        if (context == null || context.level() == null || !context.level().isLoaded(pos)) {
            return false;
        }
        return BlockPassability.canPlaceAgainst(context.level(), pos, face);
    }
}
