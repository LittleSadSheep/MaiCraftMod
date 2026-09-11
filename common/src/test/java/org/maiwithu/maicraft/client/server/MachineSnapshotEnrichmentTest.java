// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshots;
import sun.misc.Unsafe;

/** Native enrichment updates the actual snapshot cache without manufacturing new structural freshness. */
public final class MachineSnapshotEnrichmentTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        var world = (ClientLevel) memory.allocateInstance(ClientLevel.class);
        var player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        field(Level.class, "dimension").set(world, Level.OVERWORLD);
        field(Entity.class, "level").set(player, world);
        field(Entity.class, "uuid").set(player, UUID.randomUUID());
        var cache = (Map<String, MachineSnapshots.Snapshot>) field(MachineSnapshots.class, "SNAPSHOTS").get(null);
        var saved = new LinkedHashMap<>(cache);
        Object savedLevel = field(MachineSnapshots.class, "level").get(null);
        Object savedPlayer = field(MachineSnapshots.class, "playerId").get(null);
        try {
            var bind = MachineSnapshots.class.getDeclaredMethod("bind", LocalPlayer.class);
            bind.setAccessible(true); bind.invoke(null, player);
            String id = UUID.randomUUID().toString();
            JsonObject original = new JsonObject();
            original.addProperty("snapshot_id", id);
            original.addProperty("structure_fingerprint", "observed-structure");
            var anchor = new MachineSnapshots.Snapshot(id, "machine", "minecraft:overworld",
                    new BlockPos(4, 5, 6), 4, 123, "observed-structure", original.toString());
            cache.put(id, anchor);
            var evidence = new JsonObject();
            evidence.addProperty("resource_id", "opaque-exact-key");
            evidence.addProperty("tick", 150);
            var enriched = MachineSnapshots.enrich(player, anchor, evidence);
            check(cache.get(id) == enriched && enriched.id().equals(anchor.id()) && enriched.gameTime() == 123
                    && enriched.fingerprint().equals(anchor.fingerprint()), "cache enrichment preserves snapshot identity, anchor tick and structural fingerprint");
            check(enriched.report().getAsJsonObject("server_evidence").get("resource_id").getAsString().equals("opaque-exact-key"),
                    "later native operations can read the actual stored enhanced report");
            evidence.addProperty("resource_id", "modified-by-caller");
            check(!enriched.report().toString().contains("modified-by-caller"), "external JSON mutations cannot rewrite retained evidence");
            var record = new ServerMachineObservationTaskRecord("test", 200, anchor);
            check(record.internalVerifiedPosition() == null, "a queued observation does not expose a completed location receipt");
            record.observed();
            var position = record.internalVerifiedPosition();
            check(position.x() == 4 && position.y() == 5 && position.z() == 6 && position.dimension().equals(anchor.dimension()),
                    "pure-client and enhanced observations retain the same prior_result location");
            MachineSnapshots.consume(enriched);
            try { MachineSnapshots.enrich(player, anchor, evidence); throw new AssertionError("consumed anchor resurrected"); }
            catch (IllegalArgumentException expected) { /* consumed mutation anchors remain unavailable */ }
        } finally {
            cache.clear(); cache.putAll(saved);
            field(MachineSnapshots.class, "level").set(null, savedLevel);
            field(MachineSnapshots.class, "playerId").set(null, savedPlayer);
        }
        System.out.println("MachineSnapshotEnrichmentTest: passed");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
