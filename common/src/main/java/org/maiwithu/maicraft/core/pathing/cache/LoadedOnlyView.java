package org.maiwithu.maicraft.core.pathing.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 读取客户端当前已加载的区块，不为查询强行加载远处区块。当前施工和挖矿等操作仍在使用。
 * 未加载处返回空气、空流体或没有方块实体，因此需要位置真实存在的调用者还要单独核对加载状态。
 */
public final class LoadedOnlyView implements BlockGetter {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ClientLevel level;

    private LoadedOnlyView(ClientLevel level) {
        this.level = level;
    }

    /**
     * 包一层钳制视图。导航只允许读取当前客户端世界;其他 Level 实现直接拒绝,
     * 避免无意间走到会同步加载区块的 Level#getBlockState。
     */
    public static BlockGetter of(Level level) {
        if (!(level instanceof ClientLevel client)) {
            throw new IllegalArgumentException("path execution requires the active ClientLevel");
        }
        return new LoadedOnlyView(client);
    }

    /** (方块坐标)所在 chunk 此刻是否已加载在内存中。 */
    public boolean isLoaded(int x, int z) {
        return chunkAt(x, z) != null;
    }

    private LevelChunk chunkAt(int blockX, int blockZ) {
        return level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return AIR;
        }
        LevelChunk chunk = chunkAt(pos.getX(), pos.getZ());
        return chunk == null ? AIR : chunk.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return Fluids.EMPTY.defaultFluidState();
        }
        LevelChunk chunk = chunkAt(pos.getX(), pos.getZ());
        return chunk == null ? Fluids.EMPTY.defaultFluidState() : chunk.getFluidState(pos);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        LevelChunk chunk = chunkAt(pos.getX(), pos.getZ());
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return level.getMinBuildHeight();
    }
}
