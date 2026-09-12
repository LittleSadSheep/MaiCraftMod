// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.discovery;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** The real native adapter runs against a chunk source that rejects loading absent chunks. */
public final class LoadedMachineDiscoveryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var entities = nativeEntities(h);
            BlockPos pos = new BlockPos(4, 1, 4); var state = Blocks.BARREL.defaultBlockState(); h.set(pos, state);
            var barrel = new BarrelBlockEntity(pos, state); barrel.setLevel(h.level); entities.put(pos, barrel);
            var discovery = new LoadedMachineDiscovery(); var sink = new MachineDiscoveryScannerTest.Recording();
            for (int tick = 0; tick < 20 && sink.seen.isEmpty(); tick++) { h.nextTick(); discovery.tick(h.player, sink); }
            check(sink.seen.stream().anyMatch(c -> c.position().equals(pos) && c.blockId().equals("minecraft:barrel")
                            && c.blockEntityType().equals("minecraft:barrel") && Boolean.TRUE.equals(c.nativeContainer())),
                    "the native loaded-chunk block-entity index must produce a real container candidate");
            h.set(pos, Blocks.AIR.defaultBlockState()); entities.remove(pos);
            for (int tick = 0; tick < 20 && sink.removed.isEmpty(); tick++) { h.nextTick(); discovery.tick(h.player, sink); }
            check(sink.removed.equals(java.util.List.of(pos)), "loaded native air must reconcile the old candidate");
            check(h.blockUses() == 0 && h.itemUses() == 0, "passive discovery must not operate the player or world");
            discovery.clear(sink); check(discovery.status().session() == null, "disconnect clears the scanner context");
        }
        clearedNativeIndexCannotCrashTheNextTick();
        growingNativeIndexCannotChangeAnActivePass();
        System.out.println("LoadedMachineDiscoveryTest: loaded native discovery and cross-tick fastutil clear/rehash passed");
    }

    private static void clearedNativeIndexCannotCrashTheNextTick() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = nativeEntities(h);
            for (int i = 0; i < 64; i++) addBarrel(h, entities, position(i));
            var discovery = new LoadedMachineDiscovery(new MachineDiscoveryScanner(() -> 0));
            var sink = new MachineDiscoveryScannerTest.Recording();
            discovery.tick(h.player, sink);
            check(discovery.status().indexedThisTick() == 24, "the native index must remain active across ticks");
            for (BlockPos pos : new ArrayList<>(entities.keySet())) h.set(pos, Blocks.AIR.defaultBlockState());
            entities.clear(); // A retained fastutil iterator now has stale remaining entries and a null wrapped list.
            BlockPos newMachine = new BlockPos(15, 8, 15); addBarrel(h, entities, newMachine);
            sink.seen.clear();
            for (int tick = 0; tick < 40; tick++) { h.nextTick(); discovery.tick(h.player, sink); }
            check(sink.seen.stream().allMatch(c -> c.position().equals(newMachine)),
                    "detached positions must be read against current blocks, never resurrect removed machines");
            check(sink.seen.stream().anyMatch(c -> c.position().equals(newMachine)) && discovery.status().trackedCandidates() == 1,
                    "new machines must be discovered on a subsequent chunk pass after the native map was cleared");
            check(h.blockUses() == 0 && h.itemUses() == 0, "recovery must not interact with the world");
        }
    }

    private static void growingNativeIndexCannotChangeAnActivePass() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = nativeEntities(h);
            for (int i = 0; i < 64; i++) addBarrel(h, entities, position(i));
            var discovery = new LoadedMachineDiscovery(new MachineDiscoveryScanner(() -> 0));
            var sink = new MachineDiscoveryScannerTest.Recording();
            discovery.tick(h.player, sink);
            check(discovery.status().indexedThisTick() == 24, "first tick consumes only its native sample budget");
            var remaining = new HashSet<>(entities.keySet()); remaining.removeAll(indexedPositions(sink));
            BlockPos removed = remaining.iterator().next(); remaining.remove(removed);
            h.set(removed, Blocks.AIR.defaultBlockState()); entities.remove(removed);
            for (int i = 64; i < 600; i++) addBarrel(h, entities, position(i)); // Force real fastutil rehashes.
            sink.seen.clear();
            for (int tick = 0; tick < 2; tick++) {
                h.nextTick(); discovery.tick(h.player, sink);
                check(discovery.status().indexedThisTick() <= 24, "snapshot consumption retains the per-tick budget");
            }
            var indexed = indexedPositions(sink);
            check(indexed.size() == remaining.size() && new HashSet<>(indexed).equals(remaining),
                    "native map growth cannot skip, duplicate or replace the remaining detached positions");
            for (int tick = 0; tick < 80; tick++) { h.nextTick(); discovery.tick(h.player, sink); }
            check(sink.seen.stream().anyMatch(c -> c.position().equals(position(599))),
                    "new native map entries must appear on later passes");
            check(sink.seen.stream().noneMatch(c -> c.position().equals(removed)),
                    "a position snapshot is not evidence that its former block entity still exists");
            check(discovery.status().trackedCandidates() == 599 && h.blockUses() == 0 && h.itemUses() == 0,
                    "all remaining native candidates are tracked without world actions");
        }
    }

    private static List<BlockPos> indexedPositions(MachineDiscoveryScannerTest.Recording sink) {
        return sink.seen.stream().filter(c -> c.source().equals("loaded_client_block_entity_index"))
                .map(MachineDiscoveryCandidate::position).toList();
    }
    private static BlockPos position(int index) { return new BlockPos(index % 16, 1 + index / 256, index / 16 % 16); }
    private static void addBarrel(InteractionWorldTestHarness h, Object2ObjectOpenHashMap<BlockPos, BlockEntity> entities, BlockPos pos) {
        var state = Blocks.BARREL.defaultBlockState(); h.set(pos, state);
        var barrel = new BarrelBlockEntity(pos, state); barrel.setLevel(h.level); entities.put(pos, barrel);
    }
    private static Object2ObjectOpenHashMap<BlockPos, BlockEntity> nativeEntities(InteractionWorldTestHarness h) throws Exception {
        h.player.setUUID(UUID.randomUUID()); field(Level.class, "worldBorder").set(h.level, new WorldBorder());
        Object source = field(h.level.getClass(), "chunks").get(h.level), chunk = field(source.getClass(), "chunk").get(source);
        field(chunk.getClass(), "level").set(chunk, h.level); field(ChunkAccess.class, "levelHeightAccessor").set(chunk, h.level);
        var entities = new Object2ObjectOpenHashMap<BlockPos, BlockEntity>(); field(chunk.getClass(), "blockEntities").set(chunk, entities);
        return entities;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException missing) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
