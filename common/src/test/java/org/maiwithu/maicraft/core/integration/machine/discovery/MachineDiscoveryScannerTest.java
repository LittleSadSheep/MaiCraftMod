// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.discovery;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** Deterministic client-world seam; these tests never start navigation, open a menu or load a chunk. */
public final class MachineDiscoveryScannerTest {
    private static final MachineDiscoveryScanner.BlockSample SHAFT = sample("create:shaft", "create:shaft", false);
    public static void main(String[] args) throws Exception {
        rotatesWithoutRestartingTheChunkPrefix();
        loadedEvidenceAloneCanRemoveCandidates();
        explicitDetailIsBoundedAndContextScoped();
        changedNativeIteratorCannotSpinOrInventAbsence();
        timeAndTrackingBudgetsStayBounded();
        LoadedMachineDiscoveryTest.main(args);
        System.out.println("MachineDiscoveryScannerTest: loaded discovery, context, provenance and budgets passed");
    }

    private static void rotatesWithoutRestartingTheChunkPrefix() {
        var world = new World(); var sink = new Recording(); var scanner = new MachineDiscoveryScanner(() -> world.nanos);
        var positions = new ArrayList<BlockPos>();
        for (int i = 0; i < 60; i++) { var pos = new BlockPos(i % 16, 64, i / 16); positions.add(pos); world.cells.put(pos, SHAFT); }
        world.index.put(key(0, 0), positions);
        for (int tick = 0; tick < 3; tick++) { world.tick++; scanner.tick(world, sink); check(scanner.status().indexedThisTick() <= 24, "index budget"); }
        check(sink.seen.stream().filter(c -> c.source().equals("loaded_client_block_entity_index"))
                        .map(MachineDiscoveryCandidate::position).toList().equals(positions),
                "a stable native chunk index must resume its cursor instead of scanning its prefix each tick");
        check(sink.seen.getFirst().possibleRoles().equals(List.of("possible_rotational_transmission"))
                        && sink.seen.getFirst().roleBasis().contains("heuristic") && sink.seen.getFirst().blockEntityType().equals("create:shaft"),
                "native block-entity identity and heuristic purpose must remain separate");
        check(sink.sessions == 1 && scanner.status().trackedCandidates() == 60, "empty memory discovers actual candidate positions");
        for (int tick = 0; tick < 20; tick++) { world.tick++; scanner.tick(world, sink); }
        check(world.opened.contains(key(2, 2)) && world.opened.contains(key(-2, -2)), "nearby loaded chunk columns must rotate through the whole bounded window");
    }

    private static void loadedEvidenceAloneCanRemoveCandidates() {
        var world = new World(); var sink = new Recording(); var scanner = new MachineDiscoveryScanner(() -> world.nanos);
        BlockPos pos = new BlockPos(8, 64, 8); world.index.put(key(0, 0), List.of(pos));
        world.cells.put(pos, sample("othermod:chest", "othermod:chest", true)); scanner.tick(world, sink);
        check(sink.seen.getFirst().roleBasis().equals("native_container_interface"), "unknown mods can be identified through an actual Container interface");
        world.unloaded.add(pos); world.cells.put(pos, sample("minecraft:air", null, false));
        world.tick++; scanner.tick(world, sink); check(sink.removed.isEmpty(), "unloaded is unknown, never removed");
        world.unloaded.remove(pos); world.cells.put(pos, sample("othermod:chest", null, null));
        world.tick++; scanner.tick(world, sink); check(sink.removed.isEmpty(), "a delayed client block entity cannot erase an unchanged container block");
        world.cells.put(pos, sample("minecraft:stone", null, false));
        world.tick++; scanner.tick(world, sink); check(sink.removed.equals(List.of(pos)), "loaded replacement proves the old candidate is absent");
    }

    private static void explicitDetailIsBoundedAndContextScoped() {
        var world = new World(); var sink = new Recording(); var scanner = new MachineDiscoveryScanner(() -> world.nanos);
        scanner.tick(world, sink); BlockPos center = new BlockPos(8, 64, 8);
        world.cells.put(center, sample("create:andesite_casing", null, false)); world.unloaded.add(center.offset(2, 2, 2));
        scanner.requestRegion(center, 2); world.tick++; scanner.tick(world, sink);
        check(scanner.status().regionCellsThisTick() == 96 && scanner.status().region().visited() == 96, "explicit scans retain a bounded cell cursor");
        world.tick++; scanner.tick(world, sink);
        check(sink.regions.getLast().visited() == 125 && sink.regions.getLast().unloaded() == 1
                        && sink.regions.getLast().status().equals("partial_unloaded"), "region reports cannot turn missing terrain into empty space");
        check(sink.seen.stream().anyMatch(c -> c.blockId().equals("create:andesite_casing") && c.source().equals("explicit_loaded_region")),
                "explicit detail also observes relevant blocks without block entities");
        scanner.requestRegion(center, 8); world.tick++; scanner.tick(world, sink);
        world.world = new Object(); world.tick++; scanner.tick(world, sink);
        check(sink.regions.getLast().status().equals("cancelled_context_changed") && sink.sessions == 2
                        && scanner.status().trackedCandidates() == 0 && sink.removed.isEmpty(),
                "changing worlds clears session state without declaring the old world's machines destroyed");
        world.player = new Object(); world.tick++; scanner.tick(world, sink);
        check(sink.sessions == 3, "a replacement player body also resets discovery context");
        try { scanner.requestRegion(center, 9); throw new AssertionError("unbounded radius accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void changedNativeIteratorCannotSpinOrInventAbsence() {
        var world = new World(); world.changeIterator = true; var sink = new Recording();
        BlockPos neighbor = new BlockPos(-8, 64, -8); world.index.put(key(-1, -1), List.of(neighbor)); world.cells.put(neighbor, SHAFT);
        var scanner = new MachineDiscoveryScanner(() -> world.nanos); scanner.tick(world, sink);
        check(scanner.status().interruptedChunkPasses() == 1 && sink.seen.stream().anyMatch(c -> c.position().equals(neighbor)),
                "a changed native map moves to the next chunk rather than restarting a hot prefix forever");
        check(world.opened.size() <= 2 && sink.removed.isEmpty(), "iterator invalidation must remain bounded and cannot imply absence");
    }

    private static void timeAndTrackingBudgetsStayBounded() {
        var world = new World(); var sink = new Recording(); var positions = new ArrayList<BlockPos>();
        for (int i = 0; i < MachineDiscoveryScanner.MAX_TRACKED + 10; i++) {
            var pos = new BlockPos(i % 16, 64 + i / 256, i / 16 % 16); positions.add(pos); world.cells.put(pos, SHAFT);
        }
        world.index.put(key(0, 0), positions); world.readNanos = 1_100_000;
        var scanner = new MachineDiscoveryScanner(() -> world.nanos); scanner.tick(world, sink);
        check(scanner.status().indexedThisTick() == 2 && scanner.status().recheckedThisTick() == 0, "elapsed-time budget stops work independently of sample count");
        world.readNanos = 0;
        for (int tick = 0; tick < 350; tick++) { world.tick++; scanner.tick(world, sink); check(scanner.status().trackedCandidates() <= 8192, "tracking capacity"); }
        check(scanner.status().trackingEvictions() >= 10 && sink.removed.isEmpty(), "capacity eviction is not evidence of physical removal");
    }

    private static MachineDiscoveryScanner.BlockSample sample(String block, String type, Boolean container) {
        return new MachineDiscoveryScanner.BlockSample(block, type, container);
    }
    private static long key(int x, int z) { return (long) x << 32 ^ (z & 0xffffffffL); }
    private static final class World implements MachineDiscoveryScanner.WorldView {
        Object world = new Object(), player = new Object(); long tick = 1, nanos, readNanos; boolean changeIterator;
        final UUID id = UUID.randomUUID(); final Map<Long, List<BlockPos>> index = new HashMap<>();
        final Map<BlockPos, MachineDiscoveryScanner.BlockSample> cells = new HashMap<>();
        final Set<BlockPos> unloaded = new HashSet<>(); final List<Long> opened = new ArrayList<>();
        public Object worldIdentity() { return world; } public Object playerIdentity() { return player; }
        public UUID playerId() { return id; } public String dimension() { return "minecraft:overworld"; }
        public long gameTick() { return tick; } public BlockPos playerPosition() { return new BlockPos(8, 64, 8); }
        public Iterator<BlockPos> loadedBlockEntities(int x, int z) {
            opened.add(key(x, z));
            if (changeIterator && x == 0 && z == 0) return new Iterator<>() {
                public boolean hasNext() { throw new ConcurrentModificationException("native map changed"); }
                public BlockPos next() { throw new AssertionError(); }
            };
            return index.getOrDefault(key(x, z), List.of()).iterator();
        }
        public MachineDiscoveryScanner.BlockSample readLoaded(BlockPos pos) {
            nanos += readNanos;
            return unloaded.contains(pos) ? null : cells.getOrDefault(pos, sample("minecraft:air", null, false));
        }
    }
    static final class Recording implements MachineDiscoveryScanner.Sink {
        int sessions; final List<MachineDiscoveryCandidate> seen = new ArrayList<>();
        final List<BlockPos> removed = new ArrayList<>(); final List<MachineDiscoveryScanner.RegionReport> regions = new ArrayList<>();
        public void sessionChanged(MachineDiscoveryScanner.Session session) { sessions++; }
        public void observed(MachineDiscoveryCandidate candidate) { seen.add(candidate); }
        public void removed(String dimension, BlockPos pos, String block, long tick) { removed.add(pos); }
        public void regionFinished(MachineDiscoveryScanner.RegionReport report) { regions.add(report); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
