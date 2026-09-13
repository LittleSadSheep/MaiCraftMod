// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.server.*;

/** Simulated server observations exercise the real router and task states, not Create's physical APIs. */
final class UtilityConnectionReplayFixture implements AutoCloseable {
    final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
    final BlockPos source = new BlockPos(2, 0, 2), target = new BlockPos(4, 0, 2);
    final List<JsonObject> sent = new ArrayList<>();
    final ClientRequestRouter router;
    final Field routerField = field(ServerSessionRuntime.class, "router"), installedField = field(ServerSessionRuntime.class, "installed");
    final Object oldRouter = routerField.get(null), oldInstalled = installedField.get(null);
    String targetNetwork = "city-a";
    boolean edgeConnected = true;
    int cursor;
    long tick;

    UtilityConnectionReplayFixture(boolean enhanced) throws Exception {
        field(Level.class, "isClientSide").setBoolean(world.level, true);
        Object chunks = field(world.level.getClass(), "chunks").get(world.level), chunk = field(chunks.getClass(), "chunk").get(chunks);
        field(chunk.getClass(), "level").set(chunk, world.level);
        field(ChunkAccess.class, "levelHeightAccessor").set(chunk, world.level);
        field(chunk.getClass(), "blockEntities").set(chunk, new java.util.HashMap<>());
        // Native absent-BE lookup consults this queue before deciding that a plain block has no entity.
        field(chunk.getClass(), "pendingBlockEntities").set(chunk, new java.util.HashMap<>());
        world.set(target.above(), Blocks.STONE.defaultBlockState());
        router = new ClientRequestRouter(() -> enhanced, envelope -> { sent.add(envelope.deepCopy()); return true; },
                () -> {}, Runnable::run, (receipt, send) -> { throw new AssertionError("idempotent utility check must never submit mutations"); });
        router.register(new ClientOperation("machine.snapshot", 1, false, new ClientFallback() {
            public boolean supported() { return true; }
            public Availability availability(JsonObject body) { return Availability.ready(); }
            public void submit(UUID id, JsonObject body, Consumer<ClientRequestReceipt.Result> done) {
                throw new AssertionError("external utility task must not invoke the client-only snapshot fallback");
            }
        }));
        router.register(new ClientOperation("machine.connections", 1, false, null));
        router.bind(1, 1, "minecraft:overworld", 1, true, 0);
        if (enhanced) {
            JsonObject welcome = new JsonObject(); welcome.addProperty("kind", "welcome"); welcome.addProperty("bootstrap", 1);
            welcome.addProperty("status", "succeeded"); welcome.add("clientNonce", sent.getFirst().get("clientNonce"));
            welcome.addProperty("sessionId", "utility-replay"); welcome.addProperty("dimension", "minecraft:overworld");
            JsonObject features = new JsonObject();
            for (String operation : List.of("machine.snapshot", "machine.connections")) {
                JsonObject feature = new JsonObject(); feature.addProperty("version", 1); feature.addProperty("enabled", true); feature.addProperty("mutating", false);
                features.add(operation, feature);
            }
            welcome.add("features", features); router.receive(welcome, 1);
            JsonObject control = sent.getLast().deepCopy(); control.addProperty("status", "succeeded"); router.receive(control, 1);
        }
        routerField.set(null, router); installedField.setBoolean(null, true); cursor = sent.size();
    }
    void advance() throws Exception {
        router.observe(++tick); router.dispatch(false);
        while (cursor < sent.size()) {
            JsonObject request = sent.get(cursor++);
            if (!request.get("kind").getAsString().equals("request")) continue;
            JsonObject body = request.getAsJsonObject("body"), result = new JsonObject();
            result.addProperty("dimension", "minecraft:overworld"); result.addProperty("complete", true);
            if (body.has("positions")) {
                JsonArray observations = new JsonArray();
                for (var position : body.getAsJsonArray("positions")) {
                    JsonObject observed = new JsonObject(); observed.add("position", position.deepCopy());
                    observed.addProperty("block_id", "minecraft:stone"); observed.addProperty("provenance", "server_native");
                    observed.add("native", JsonParser.parseString("{create:{hasNetwork:true,isOverStressed:false,getSpeed:64,shaft_faces:['up','down']}}"));
                    observed.getAsJsonObject("native").getAsJsonObject("create").addProperty("network_id",
                            position.equals(UtilityConnectionEvidence.position(source)) ? "city-a" : targetNetwork);
                    observations.add(observed);
                }
                result.add("observations", observations);
            } else {
                result.add("path", body.get("path").deepCopy()); result.addProperty("verified_connection", edgeConnected);
                result.addProperty("operational", edgeConnected); result.addProperty("flow_verified", false);
            }
            JsonObject reply = request.deepCopy(); reply.remove("body"); reply.addProperty("kind", "receipt");
            reply.addProperty("status", "succeeded"); reply.addProperty("effect", "not_applied"); reply.addProperty("code", "");
            reply.addProperty("serverTick", tick); reply.add("result", result); router.receive(reply, 1);
        }
        world.nextTick();
    }
    public void close() throws Exception { router.close(); routerField.set(null, oldRouter); installedField.set(null, oldInstalled); world.close(); }
    static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
            catch (NoSuchFieldException missing) { }
        }
        throw new NoSuchFieldException(name);
    }
}
