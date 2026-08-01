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
 * Fully detached, immutable terrain input for one A* generation.
 *
 * <p>Capture happens on the Minecraft client thread. Each loaded chunk's block-state palettes are
 * copied before publication; workers never retain or read a {@link LevelChunk}, {@code ClientLevel},
 * block entity, or player. Replacing the cache entry cannot affect an in-flight search because the
 * old snapshot owns all of its palette copies.
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
     * One copied chunk-column. Palette instances are private to this snapshot and never mutated
     * after construction.
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
