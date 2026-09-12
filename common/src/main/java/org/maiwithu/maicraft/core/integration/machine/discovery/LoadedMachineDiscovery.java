// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.discovery;

import java.util.Iterator;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Native adapter reads existing client chunk indexes; it never requests chunks, movement, menus or server mutations. */
public final class LoadedMachineDiscovery {
    private final MachineDiscoveryScanner scanner;
    public LoadedMachineDiscovery() { this(new MachineDiscoveryScanner()); }
    LoadedMachineDiscovery(MachineDiscoveryScanner scanner) { this.scanner = java.util.Objects.requireNonNull(scanner); }
    public MachineDiscoveryScanner.Status status() { return scanner.status(); }
    public UUID requestRegion(BlockPos center, int radius) { requireThread(); return scanner.requestRegion(center, radius); }
    public void clear(MachineDiscoveryScanner.Sink sink) { requireThread(); scanner.clear(sink); }
    public void tick(LocalPlayer player, MachineDiscoveryScanner.Sink sink) {
        requireThread();
        if (player == null || player.clientLevel == null) { scanner.clear(sink); return; }
        scanner.tick(new ClientView(player), sink);
    }
    private static void requireThread() {
        if (Minecraft.getInstance() == null || !Minecraft.getInstance().isSameThread())
            throw new IllegalStateException("machine discovery requires the client thread");
    }

    private record ClientView(LocalPlayer player) implements MachineDiscoveryScanner.WorldView {
        private ClientLevel level() { return player.clientLevel; }
        public Object worldIdentity() { return level(); }
        public Object playerIdentity() { return player; }
        public UUID playerId() { return player.getUUID(); }
        public String dimension() { return level().dimension().location().toString(); }
        public long gameTick() { return level().getGameTime(); }
        public BlockPos playerPosition() { return player.blockPosition(); }
        private LevelChunk loaded(int x, int z) { return level().getChunkSource().getChunk(x, z, ChunkStatus.FULL, false); }
        public Iterator<BlockPos> loadedBlockEntities(int chunkX, int chunkZ) {
            LevelChunk chunk = loaded(chunkX, chunkZ);
            // Only positions survive this client tick. Native maps can clear or rehash before the next tick,
            // and fastutil iterators may throw NPE rather than ConcurrentModificationException afterward.
            return chunk == null ? null : chunk.getBlockEntities().keySet().stream()
                    .map(BlockPos::immutable).toList().iterator();
        }
        public MachineDiscoveryScanner.BlockSample readLoaded(BlockPos pos) {
            if (level().isOutsideBuildHeight(pos) || !level().getWorldBorder().isWithinBounds(pos)) return null;
            LevelChunk chunk = loaded(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) return null;
            var state = chunk.getBlockState(pos); var entity = chunk.getBlockEntities().get(pos);
            if (entity != null && (entity.isRemoved() || entity.getLevel() != level() || !state.hasBlockEntity())) entity = null;
            var type = entity == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
            Boolean container = null;
            if (entity != null) container = entity instanceof Container;
            else if (!state.hasBlockEntity()) container = false;
            return new MachineDiscoveryScanner.BlockSample(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    type == null ? null : type.toString(), container);
        }
    }
}
