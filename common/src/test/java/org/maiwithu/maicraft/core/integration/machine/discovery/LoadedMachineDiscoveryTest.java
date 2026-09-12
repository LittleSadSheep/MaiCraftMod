// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.discovery;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.UUID;
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

/** The real native adapter runs against a chunk source whose getChunk throws if load=true. */
public final class LoadedMachineDiscoveryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.player.setUUID(UUID.randomUUID()); field(Level.class, "worldBorder").set(h.level, new WorldBorder());
            Object source = field(h.level.getClass(), "chunks").get(h.level), chunk = field(source.getClass(), "chunk").get(source);
            field(chunk.getClass(), "level").set(chunk, h.level); field(ChunkAccess.class, "levelHeightAccessor").set(chunk, h.level);
            var entities = new HashMap<BlockPos, BlockEntity>(); field(chunk.getClass(), "blockEntities").set(chunk, entities);
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
        System.out.println("LoadedMachineDiscoveryTest: native chunk index discovery with load=false passed");
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
