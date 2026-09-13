// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** Loaded ordinary warehouses only. Client block-entity inventories never prove stored quantities. */
public final class ContainerSupplySources {
    public static final int MAX_ATTEMPTS = 8, MAX_SCANNED = 4096;
    public record Candidate(BlockPos position, ResourceLocation blockId, List<BlockPos> footprint, int stockRank) {
        public Candidate { position = position.immutable(); footprint = List.copyOf(footprint); }
    }
    private static final Cache CACHE = new Cache();
    private ContainerSupplySources() {}
    public static List<Candidate> candidates(LocalPlayer player, BlockPos center, int radius,
            List<ResourceLocation> items, Set<BlockPos> visited, List<String> protectedLabels) {
        List<Candidate> found = new ArrayList<>(); Set<BlockPos> seen = new java.util.HashSet<>(); int examined = 0;
        int chunks = (radius + 15) / 16;
        outer: for (int x = -chunks; x <= chunks; x++) for (int z = -chunks; z <= chunks; z++) {
            var chunk = player.clientLevel.getChunkSource().getChunk((center.getX() >> 4) + x, (center.getZ() >> 4) + z,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            if (chunk == null) continue;
            for (BlockPos raw : List.copyOf(chunk.getBlockEntities().keySet())) {
                if (++examined > MAX_SCANNED) break outer;
                BlockPos at = raw.immutable();
                if (at.distSqr(center) > (long) radius * radius || seen.contains(at) || visited.contains(at) || !allowed(player, at, protectedLabels)) continue;
                List<BlockPos> footprint = footprint(player.level(), at); seen.addAll(footprint);
                if (footprint.stream().anyMatch(visited::contains)) continue;
                Map<BlockPos, Object> identities = identities(player.level(), footprint);
                var stock = CACHE.latest(player, player.level(), at, identities, player.level().getGameTime());
                int rank = stock == null ? 1 : items.stream().anyMatch(id -> stock.storedCount(id) > 0) ? 0 : 2;
                found.add(new Candidate(at, BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(at).getBlock()), footprint, rank));
            }
        }
        return found.stream().sorted(Comparator.comparingInt(Candidate::stockRank)
                .thenComparingDouble(value -> value.position().distSqr(center)).thenComparingLong(value -> value.position().asLong())).limit(MAX_ATTEMPTS).toList();
    }
    public static List<BlockPos> footprint(Level level, BlockPos at) {
        if (!level.isLoaded(at) || !ordinary(level.getBlockEntity(at))) return List.of();
        var state = level.getBlockState(at);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return List.of(at.immutable());
        BlockPos peer = at.relative(ChestBlock.getConnectedDirection(state));
        if (!level.isLoaded(peer) || !(level.getBlockEntity(peer) instanceof ChestBlockEntity)) return List.of();
        var other = level.getBlockState(peer);
        if (other.getBlock() != state.getBlock() || other.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                || !peer.relative(ChestBlock.getConnectedDirection(other)).equals(at)) return List.of();
        return List.of(at.immutable(), peer.immutable());
    }
    public static boolean allowed(LocalPlayer player, BlockPos at, List<String> protectedLabels) {
        List<BlockPos> footprint = footprint(player.level(), at); if (footprint.isEmpty()) return false;
        for (BlockPos cell : footprint) {
            if (NavigationSafetyContext.protectsUse(cell)) return false;
            var entity = player.level().getBlockEntity(cell);
            for (String label : protectedLabels) {
                var landmark = IntentRuntime.get().landmark(label); if (landmark == null || landmark.position() == null) return false;
                var position = landmark.position();
                if ((position.dimension() == null || position.dimension().equals(player.level().dimension().location().toString()))
                        && cell.distSqr(new BlockPos(position.x(), position.y(), position.z())) <= 12 * 12) return false;
                if (entity instanceof BaseContainerBlockEntity named && named.getCustomName() != null
                        && label.equalsIgnoreCase(named.getCustomName().getString())) return false;
            }
        }
        return true;
    }
    public static void rememberVisible(LocalPlayer player, BlockPos at, AbstractContainerMenu menu, List<Integer> slots) {
        if (!StockEvidence.isContainerSynchronized(player, menu) || !MenuVisibility.matches(ClientRuntime.requireContext(player).minecraft(), menu)) return;
        var footprint = footprint(player.level(), at); if (footprint.isEmpty()) return;
        Map<ResourceLocation, Long> counts = new LinkedHashMap<>();
        for (int slot : slots) { var stack = menu.getSlot(slot).getItem(); StockEvidence.add(counts, stack, stack.getCount()); }
        CACHE.record(player, player.level(), identities(player.level(), footprint), new StockEvidence.Snapshot(
                StockEvidence.Source.CONTAINER, counts, Set.of(), player.level().getGameTime()));
    }
    public static void reset() { CACHE.clear(); }
    private static boolean ordinary(BlockEntity entity) { return entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity || entity instanceof ShulkerBoxBlockEntity; }
    private static Map<BlockPos, Object> identities(Level level, List<BlockPos> positions) {
        Map<BlockPos, Object> result = new LinkedHashMap<>(); positions.forEach(pos -> result.put(pos, level.getBlockEntity(pos))); return result;
    }
    static final class Cache {
        private record Entry(Map<BlockPos, Object> identities, StockEvidence.Snapshot stock) {}
        private Object player, world;
        private final Map<BlockPos, Entry> entries = new LinkedHashMap<>();
        void record(Object owner, Object level, Map<BlockPos, Object> identities, StockEvidence.Snapshot stock) {
            bind(owner, level); Entry entry = new Entry(Map.copyOf(identities), stock);
            for (BlockPos at : identities.keySet()) { entries.remove(at); entries.put(at.immutable(), entry); }
            while (entries.size() > 64) entries.remove(entries.keySet().iterator().next());
        }
        StockEvidence.Snapshot latest(Object owner, Object level, BlockPos at, Map<BlockPos, Object> identities, long tick) {
            bind(owner, level); Entry entry = entries.get(at); if (entry == null) return null;
            if (tick < entry.stock.observedGameTick() || tick - entry.stock.observedGameTick() > StockEvidence.MAX_AGE_TICKS
                    || !entry.identities.keySet().equals(identities.keySet())
                    || entry.identities.entrySet().stream().anyMatch(value -> identities.get(value.getKey()) != value.getValue())) {
                entries.remove(at); return null;
            }
            return entry.stock;
        }
        private void bind(Object owner, Object level) { if (player != owner || world != level) { clear(); player = owner; world = level; } }
        void clear() { entries.clear(); player = world = null; }
    }
}
