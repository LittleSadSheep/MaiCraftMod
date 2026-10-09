// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 读采集现场的生产实现：一格现在是什么、熟没熟、这一柱列顶到哪，都从真实世界读。
 *
 * <p>采集动手前以这里的现场为准：观察编号与记忆只是线索，格子被拆了、庄稼被收了都如实报告。
 */
public final class LiveSpotReads implements ReadsSpot {

    private final Supplier<PlayerContext> context;

    public LiveSpotReads(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<String> blockTypeAt(WorldPosition at) {
        Level level = level();
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        if (level == null || !level.isLoaded(pos)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        // 空气是"这里没有了"，不是一种方块。
        if (state.isAir()) {
            return Optional.empty();
        }
        return Optional.of(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    @Override
    public boolean isCrop(String blockType) {
        return block(blockType) instanceof CropBlock;
    }

    @Override
    public boolean matureCrop(WorldPosition at, String blockType) {
        Level level = level();
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        if (level == null || !level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        // 作物没熟、或根本不是作物，都是 false。
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    @Override
    public OptionalInt surfaceY(int x, int z) {
        Level level = level();
        if (level == null || !level.isLoaded(new BlockPos(x, level.getMinBuildHeight(), z))) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
    }

    private Level level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }

    private static Block block(String blockTypeId) {
        ResourceLocation id = ResourceLocation.parse(blockTypeId.toLowerCase(Locale.ROOT));
        return BuiltInRegistries.BLOCK.get(id);
    }
}
