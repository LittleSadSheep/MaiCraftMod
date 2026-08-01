package org.maiwithu.maicraft.core.pathing.cache;

import org.maiwithu.maicraft.core.pathing.util.BlockEntityAware;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * A {@link BlockGetter} over a {@link LoadedChunks} snapshot — the search-side world view.
 * Loaded chunk → copied block-state palette; not captured → AIR. The mutable last-chunk
 * pointer is confined to one search worker; all terrain behind it is immutable.
 */
public final class CachedNavView implements BlockGetter, BlockEntityAware {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final LoadedChunks loaded;
    private LoadedChunks.ChunkSnapshot prev;

    public CachedNavView(LoadedChunks loaded) {
        this.loaded = loaded;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return read(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Was the chunk containing block-column ({@code blockX},{@code blockZ}) captured in the
     *  snapshot? {@code false} means every read there was the optimistic AIR miss — prices
     *  computed from it are guesses, not measurements. */
    public boolean isLoaded(int blockX, int blockZ) {
        return loaded.at(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ)) != null;
    }

    private BlockState read(int x, int y, int z) {
        if (y < loaded.minBuildHeight() || y >= loaded.minBuildHeight() + loaded.height()) {
            return AIR;
        }
        int chunkX = SectionPos.blockToSectionCoord(x);
        int chunkZ = SectionPos.blockToSectionCoord(z);
        LoadedChunks.ChunkSnapshot chunk = prev;
        if (chunk == null || chunk.chunkX() != chunkX || chunk.chunkZ() != chunkZ) {
            chunk = loaded.at(chunkX, chunkZ);
            if (chunk != null) {
                prev = chunk;
            }
        }
        if (chunk == null) {
            return AIR;
        }
        return chunk.blockState(x, y, z, loaded.minBuildHeight());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean hasBlockEntity(BlockPos pos) {
        return loaded.hasBlockEntity(pos);   // from the main-thread snapshot — safe off-thread
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        // Can't reconstruct a live block entity off-thread. The only search-path caller, the don't-grief
        // check, now goes through hasBlockEntity instead, so returning null here is safe.
        return null;
    }

    @Override
    public int getHeight() {
        return loaded.height();
    }

    @Override
    public int getMinBuildHeight() {
        return loaded.minBuildHeight();
    }
}
