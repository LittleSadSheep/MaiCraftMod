package org.maiwithu.maicraft.core.pathing.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-thread publisher for detached A* snapshots. Capture only consults
 * {@code ClientChunkCache#getChunkNow}; absent chunks stay absent and are never loaded or generated.
 * One snapshot may be shared by searches started in the same game tick and center chunk.
 */
public final class PathCaches {

    private PathCaches() {}

    /**
     * A rolling 9x9 chunk window. Longer routes return a partial segment and recapture after the
     * player advances; this keeps a search generation bounded and avoids copying the whole view
     * distance on the render-sensitive client thread.
     */
    private static final int RADIUS_CHUNKS = 4;

    private static final ConcurrentHashMap<ResourceKey<Level>, CacheEntry> SNAPSHOTS =
            new ConcurrentHashMap<>();

    public static LoadedChunks peek(Level level) {
        CacheEntry entry = SNAPSHOTS.get(level.dimension());
        return entry == null ? null : entry.snapshot();
    }

    /**
     * Capture or reuse a detached snapshot. Must run on the active Minecraft client thread.
     */
    public static LoadedChunks ensureSnapshot(ClientLevel level, BlockPos around) {
        requireActiveClientLevel(level);
        int centerChunkX = SectionPos.blockToSectionCoord(around.getX());
        int centerChunkZ = SectionPos.blockToSectionCoord(around.getZ());
        CacheEntry existing = SNAPSHOTS.get(level.dimension());
        if (existing != null
                && existing.owner() == level
                && existing.gameTime() == level.getGameTime()
                && existing.centerChunkX() == centerChunkX
                && existing.centerChunkZ() == centerChunkZ) {
            return existing.snapshot();
        }
        LoadedChunks built = snapshot(level, around);
        SNAPSHOTS.put(level.dimension(),
                new CacheEntry(level, level.getGameTime(), centerChunkX, centerChunkZ, built));
        return built;
    }

    public static void dropAll() {
        SNAPSHOTS.clear();
    }

    /**
     * Loader hook for the end of each client tick. It does not recapture terrain; searches capture
     * lazily. It only pulses diagnostics and drops entries from departed dimensions.
     */
    public static void clientTick(LocalPlayer player) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("path cache tick must run on the Minecraft client thread");
        }
        org.maiwithu.maicraft.core.pathing.util.NavProfiler.clientTickPulse();
        if (player == null || minecraft.level == null || player.level() != minecraft.level) {
            dropAll();
            return;
        }
        ResourceKey<Level> active = minecraft.level.dimension();
        SNAPSHOTS.entrySet().removeIf(entry -> !entry.getKey().equals(active)
                || entry.getValue().owner() != minecraft.level);
    }

    private static LoadedChunks snapshot(ClientLevel level, BlockPos feet) {
        Long2ObjectOpenHashMap<LoadedChunks.ChunkSnapshot> map = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet blockEntities = new LongOpenHashSet();
        int centerChunkX = SectionPos.blockToSectionCoord(feet.getX());
        int centerChunkZ = SectionPos.blockToSectionCoord(feet.getZ());
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                map.put(ChunkPos.asLong(chunkX, chunkZ), new LoadedChunks.ChunkSnapshot(chunk));
                for (BlockPos blockEntityPos : chunk.getBlockEntities().keySet()) {
                    if (blockEntityPos.getY() >= level.getMinBuildHeight()
                            && blockEntityPos.getY() < level.getMaxBuildHeight()) {
                        blockEntities.add(blockEntityPos.asLong());
                    }
                }
            }
        }
        return new LoadedChunks(map, blockEntities, level.getMinBuildHeight(), level.getHeight());
    }

    private static void requireActiveClientLevel(ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("path snapshots must be captured on the client thread");
        }
        if (minecraft.level != level) {
            throw new IllegalArgumentException("path snapshot level is not the active ClientLevel");
        }
    }

    private record CacheEntry(ClientLevel owner, long gameTime, int centerChunkX, int centerChunkZ,
                              LoadedChunks snapshot) {}
}
