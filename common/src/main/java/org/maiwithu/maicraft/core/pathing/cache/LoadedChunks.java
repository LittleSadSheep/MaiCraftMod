package org.maiwithu.maicraft.core.pathing.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * 保存旧搜索用的一批冻结区块：方块状态、高度范围，以及有方块实体的位置。调用者拿不到可修改原集合的入口。
 */
public final class LoadedChunks {

    private final Long2ObjectMap<ChunkSnapshot> chunks;
    private final LongSet blockEntities;
    private final int minBuildHeight;
    private final int height;

    LoadedChunks(Long2ObjectMap<ChunkSnapshot> chunks, LongSet blockEntities,
                 int minBuildHeight, int height) {
        this.chunks = new Long2ObjectOpenHashMap<>(chunks);
        this.blockEntities = new LongOpenHashSet(blockEntities);
        this.minBuildHeight = minBuildHeight;
        this.height = height;
    }

    public ChunkSnapshot at(int chunkX, int chunkZ) {
        return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public boolean hasBlockEntity(BlockPos pos) {
        return blockEntities.contains(pos.asLong());
    }

    public int size() {
        return chunks.size();
    }

    public int minBuildHeight() {
        return minBuildHeight;
    }

    public int height() {
        return height;
    }

    /**
     * 逐段复制一个区块的方块状态表；后续搜索读这份表，不直接保留会随游戏变化的原区块内容。
     */
    public static final class ChunkSnapshot {

        private static final BlockState AIR = Blocks.AIR.defaultBlockState();

        private final int chunkX;
        private final int chunkZ;
        private final PalettedContainer<BlockState>[] states;

        @SuppressWarnings("unchecked")
        ChunkSnapshot(LevelChunk source) {
            this.chunkX = source.getPos().x;
            this.chunkZ = source.getPos().z;
            LevelChunkSection[] sections = source.getSections();
            this.states = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[sections.length];
            for (int index = 0; index < sections.length; index++) {
                this.states[index] = sections[index].getStates().copy();
            }
        }

        int chunkX() {
            return chunkX;
        }

        int chunkZ() {
            return chunkZ;
        }

        BlockState blockState(int x, int y, int z, int minBuildHeight) {
            int sectionIndex = SectionPos.blockToSectionCoord(y)
                    - SectionPos.blockToSectionCoord(minBuildHeight);
            if (sectionIndex < 0 || sectionIndex >= states.length) {
                return AIR;
            }
            return states[sectionIndex].get(x & 15, y & 15, z & 15);
        }
    }
}
