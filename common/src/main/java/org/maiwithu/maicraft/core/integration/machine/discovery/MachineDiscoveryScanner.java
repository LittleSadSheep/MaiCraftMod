// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.discovery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;

/** Client-thread-owned, bounded discovery of candidates; never a complete factory or production-function inference. */
public final class MachineDiscoveryScanner {
    public static final int CHUNK_RADIUS = 2, INDEX_SAMPLES_PER_TICK = 24, CHUNK_STARTS_PER_TICK = 2;
    public static final int RECHECKS_PER_TICK = 8, REGION_CELLS_PER_TICK = 96, MAX_TRACKED = 8192, MAX_REGION_RADIUS = 8;
    public static final long NANOS_PER_TICK = 2_000_000;
    private static final List<int[]> CHUNKS = chunkOffsets();

    public record BlockSample(String blockId, String blockEntityType, Boolean nativeContainer) {
        public BlockSample { Objects.requireNonNull(blockId); }
    }
    public record Session(long epoch, String dimension, UUID playerId) {}
    public record RegionReport(UUID id, String dimension, BlockPos center, int radius, int visited,
                               int total, int unloaded, int candidates, String status) {}
    public record Status(Session session, int trackedCandidates, int nextChunk, int indexedThisTick,
                         int recheckedThisTick, int regionCellsThisTick, long interruptedChunkPasses,
                         long trackingEvictions, RegionReport region) {}

    /** Null reads/iterators mean unloaded or unavailable, never air. Implementations must never load chunks. */
    public interface WorldView {
        Object worldIdentity();
        Object playerIdentity();
        UUID playerId();
        String dimension();
        long gameTick();
        BlockPos playerPosition();
        /** Detached immutable positions: this iterator can be retained across ticks, unlike a live native index. */
        Iterator<BlockPos> loadedBlockEntities(int chunkX, int chunkZ);
        BlockSample readLoaded(BlockPos position);
    }
    public interface Sink {
        default void sessionChanged(Session session) {}
        void observed(MachineDiscoveryCandidate candidate);
        /** Called only when a loaded, changed block proves the former candidate is no longer present. */
        void removed(String dimension, BlockPos position, String observedBlockId, long gameTick);
        default void regionFinished(RegionReport report) {}
    }

    private final LongSupplier nanoTime;
    private final Map<BlockPos, MachineDiscoveryCandidate> known = new HashMap<>();
    private final ArrayDeque<BlockPos> revisit = new ArrayDeque<>();
    private final Set<BlockPos> queued = new HashSet<>();
    private Object worldIdentity, playerIdentity;
    private Session session;
    private long epoch, interrupted, evictions;
    private int chunkCursor, activeChunkX, activeChunkZ;
    private Iterator<BlockPos> activeEntries;
    private Region region;
    private RegionReport lastRegion;
    private volatile Status status = new Status(null, 0, 0, 0, 0, 0, 0, 0, null);

    public MachineDiscoveryScanner() { this(System::nanoTime); }
    MachineDiscoveryScanner(LongSupplier nanoTime) { this.nanoTime = Objects.requireNonNull(nanoTime); }
    public Status status() { return status; }

    /** Optional detail scan; it is still incremental and never navigates toward missing terrain. */
    public UUID requestRegion(BlockPos center, int radius) {
        if (session == null) throw new IllegalStateException("machine_discovery_session_missing");
        if (region != null) throw new IllegalStateException("machine_discovery_region_pending");
        if (radius < 0 || radius > MAX_REGION_RADIUS || Math.abs((long) center.getX()) > 30_000_000
                || Math.abs((long) center.getZ()) > 30_000_000 || Math.abs((long) center.getY()) > 2048)
            throw new IllegalArgumentException("machine_discovery_region_out_of_bounds");
        region = new Region(center.immutable(), radius); return region.id;
    }

    public void tick(WorldView view, Sink sink) {
        Objects.requireNonNull(view); Objects.requireNonNull(sink);
        if (session == null || worldIdentity != view.worldIdentity() || playerIdentity != view.playerIdentity()
                || !session.playerId().equals(view.playerId()) || !session.dimension().equals(view.dimension())) {
            clear(sink);
            worldIdentity = view.worldIdentity(); playerIdentity = view.playerIdentity();
            session = new Session(++epoch, view.dimension(), Objects.requireNonNull(view.playerId())); sink.sessionChanged(session);
        }
        long start = nanoTime.getAsLong(); int indexed = 0, rechecked = 0, detailed = 0, opened = 0;
        int cx = view.playerPosition().getX() >> 4, cz = view.playerPosition().getZ() >> 4;
        if (activeEntries != null && (Math.abs(activeChunkX - cx) > CHUNK_RADIUS || Math.abs(activeChunkZ - cz) > CHUNK_RADIUS)) activeEntries = null;
        while (indexed < INDEX_SAMPLES_PER_TICK && withinBudget(start)) {
            if (activeEntries == null) {
                if (opened++ >= CHUNK_STARTS_PER_TICK) break;
                int[] offset = CHUNKS.get(chunkCursor); chunkCursor = (chunkCursor + 1) % CHUNKS.size();
                activeChunkX = cx + offset[0]; activeChunkZ = cz + offset[1];
                activeEntries = view.loadedBlockEntities(activeChunkX, activeChunkZ);
                if (activeEntries == null) continue;
            }
            BlockPos pos;
            try {
                if (!activeEntries.hasNext()) { activeEntries = null; continue; }
                pos = activeEntries.next().immutable();
            } catch (ConcurrentModificationException changed) {
                // Move to the next chunk, then revisit this one on the next rotation. No absence is inferred.
                activeEntries = null; interrupted++; continue;
            }
            indexed++; inspect(view, sink, pos, "loaded_client_block_entity_index");
        }
        int revisitBudget = Math.min(RECHECKS_PER_TICK, revisit.size());
        while (rechecked < revisitBudget && withinBudget(start)) {
            BlockPos pos = revisit.removeFirst(); queued.remove(pos); rechecked++;
            var prior = known.get(pos);
            if (prior != null && prior.observedTick() != view.gameTick()
                    && Math.abs((pos.getX() >> 4) - cx) <= CHUNK_RADIUS && Math.abs((pos.getZ() >> 4) - cz) <= CHUNK_RADIUS)
                inspect(view, sink, pos, "loaded_client_revalidation");
            if (known.containsKey(pos)) enqueue(pos);
        }
        while (region != null && detailed < REGION_CELLS_PER_TICK && withinBudget(start)) {
            BlockPos pos = region.next(); detailed++;
            BlockSample sample = view.readLoaded(pos);
            if (sample == null) region.unloaded++;
            else if (inspect(view, sink, pos, sample, "explicit_loaded_region")) region.candidates++;
            if (region.visited == region.total) {
                lastRegion = region.report(session.dimension(), region.unloaded == 0 ? "complete" : "partial_unloaded");
                region = null; sink.regionFinished(lastRegion);
            }
        }
        status = new Status(session, known.size(), chunkCursor, indexed, rechecked, detailed, interrupted, evictions,
                region == null ? lastRegion : region.report(session.dimension(), "scanning"));
    }

    public void clear(Sink sink) {
        if (region != null && session != null) sink.regionFinished(region.report(session.dimension(), "cancelled_context_changed"));
        known.clear(); revisit.clear(); queued.clear(); activeEntries = null; region = null; lastRegion = null;
        worldIdentity = playerIdentity = null; session = null; chunkCursor = 0; interrupted = evictions = 0;
        status = new Status(null, 0, 0, 0, 0, 0, 0, 0, null);
    }

    private void inspect(WorldView view, Sink sink, BlockPos pos, String source) {
        BlockSample sample = view.readLoaded(pos);
        if (sample != null) inspect(view, sink, pos, sample, source);
    }
    private boolean inspect(WorldView view, Sink sink, BlockPos pos, BlockSample sample, String source) {
        var hint = MachineDiscoveryHints.classify(sample);
        if (hint == null) {
            var old = known.get(pos);
            // A missing client block entity can hide its Container interface; the unchanged block is not absent.
            if (old != null && !old.blockId().equals(sample.blockId())) {
                known.remove(pos); sink.removed(view.dimension(), pos, sample.blockId(), view.gameTick());
            }
            return false;
        }
        var candidate = new MachineDiscoveryCandidate(view.dimension(), pos, sample.blockId(), sample.blockEntityType(),
                sample.nativeContainer(), hint.family(), hint.roles(), hint.basis(), source, view.gameTick());
        known.put(pos, candidate); enqueue(pos); sink.observed(candidate); return true;
    }
    private void enqueue(BlockPos pos) {
        if (!queued.add(pos)) return;
        if (revisit.size() >= MAX_TRACKED) {
            BlockPos evicted = revisit.removeFirst(); queued.remove(evicted); known.remove(evicted); evictions++;
        }
        revisit.addLast(pos.immutable());
    }
    private boolean withinBudget(long start) { return nanoTime.getAsLong() - start < NANOS_PER_TICK; }
    private static List<int[]> chunkOffsets() {
        var result = new ArrayList<int[]>(); result.add(new int[]{0, 0});
        for (int radius = 1; radius <= CHUNK_RADIUS; radius++)
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
                if (Math.max(Math.abs(x), Math.abs(z)) == radius) result.add(new int[]{x, z});
        return List.copyOf(result);
    }

    private static final class Region {
        final UUID id = UUID.randomUUID(); final BlockPos center; final int radius, width, total;
        int visited, unloaded, candidates;
        Region(BlockPos center, int radius) { this.center = center; this.radius = radius; width = radius * 2 + 1; total = width * width * width; }
        BlockPos next() { int index = visited++; return center.offset(index % width - radius, index / (width * width) - radius, index / width % width - radius); }
        RegionReport report(String dimension, String status) { return new RegionReport(id, dimension, center, radius, visited, total, unloaded, candidates, status); }
    }
}
