// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.lang.reflect.Field;
import java.util.EnumMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** Uses real native heightmaps: a server-only preallocated map is not a client terrain observation. */
public final class KineticClientHeightmapTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        check(Heightmap.Types.MOTION_BLOCKING.sendToClient() && !Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.sendToClient(),
                "only the client-synchronized blocking heightmap may supply relay foundation levels");
        try (var h = new InteractionWorldTestHarness()) {
            Object cache = field(h.level.getClass(), "chunks").get(h.level);
            LevelChunk chunk = (LevelChunk) field(cache.getClass(), "chunk").get(cache);
            field(ChunkAccess.class, "levelHeightAccessor").set(chunk, h.level);
            var maps = new EnumMap<Heightmap.Types, Heightmap>(Heightmap.Types.class);
            field(ChunkAccess.class, "heightmaps").set(chunk, maps);
            var client = new Heightmap(chunk, Heightmap.Types.MOTION_BLOCKING);
            for (int x=0;x<16;x++) for (int z=0;z<16;z++) client.update(x, 0, z, Blocks.STONE.defaultBlockState());
            maps.put(Heightmap.Types.MOTION_BLOCKING, client);
            maps.put(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new Heightmap(chunk, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
            check(chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)
                    && chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,4,4)==h.level.getMinBuildHeight()-1,
                    "native client construction reproduces the misleading present-but-empty server-only map");
            var terrain = new KineticPlanningTerrain(h.level);
            check(Integer.valueOf(0).equals(terrain.groundHeight(4,4)), "relay columns must start above observed terrain, not the world bottom");
            maps.remove(Heightmap.Types.MOTION_BLOCKING);
            check(terrain.groundHeight(4,4)==null && !maps.containsKey(Heightmap.Types.MOTION_BLOCKING), "missing maps remain unknown without implicit priming");
            maps.put(Heightmap.Types.MOTION_BLOCKING, new Heightmap(chunk, Heightmap.Types.MOTION_BLOCKING));
            check(terrain.groundHeight(4,4)==null, "an empty or unsynchronized column cannot become a foundation at minY-1");
            check(terrain.groundHeight(32,4)==null, "missing chunks must not be loaded by terrain quotes");
            check(h.blockUses()==0 && h.itemUses()==0, "terrain observation stays read-only");
        }
        System.out.println("KineticClientHeightmapTest: native client heightmap type, empty-map rejection and no lazy priming passed");
    }
    private static Field field(Class<?> owner,String name) throws Exception {
        for(Class<?> type=owner;type!=null;type=type.getSuperclass()) {
            try {var field=type.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException ignored){}
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
