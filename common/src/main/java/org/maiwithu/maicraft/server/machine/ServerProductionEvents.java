// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** Only native processing hooks append events; RPC clients cannot submit production evidence. */
public final class ServerProductionEvents {
    private static final Map<ServerLevel, ProductionEventJournal> JOURNALS = new WeakHashMap<>();
    private ServerProductionEvents() {}

    public static void pressed(BlockEntity producer, String recipe, ItemStack input, List<ItemStack> outputs) {
        if (!(producer.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        JsonArray inputs = new JsonArray(), actualOutputs = new JsonArray();
        inputs.add(item(level, input));
        for (ItemStack output : outputs) if (!output.isEmpty()) actualOutputs.add(item(level, output));
        recordProduction(level, producer.getBlockPos(), recipe, inputs, actualOutputs, 1, "create.press.tryProcessOnBelt");
    }

    /** Amounts already describe this actual native operation; operations is metadata, never a multiplier. */
    public static void recordProduction(ServerLevel level, BlockPos producer, String recipeId, JsonArray inputs,
                                        JsonArray outputs, long operations, String nativeCall) {
        if (!level.getServer().isSameThread() || producer == null || operations <= 0) return;
        if (!hasPositiveResource(outputs) && !hasPositiveResource(inputs)) return;
        JsonObject event = new JsonObject();
        event.addProperty("producer", key(producer));
        event.addProperty("kind", "recipe_output");
        event.addProperty("completed", true);
        JsonObject position = new JsonObject();
        position.addProperty("x", producer.getX());
        position.addProperty("y", producer.getY());
        position.addProperty("z", producer.getZ());
        event.add("position", position);
        event.addProperty("tick", level.getGameTime());
        event.addProperty("recipe_id", recipeId);
        event.addProperty("operations", operations);
        event.addProperty("provenance", "native_recipe_output");
        event.addProperty("native_call", nativeCall);
        event.addProperty("delivery_confirmed", false);
        // append validates depth/size and immediately encodes an immutable payload; no recursive pre-copy.
        event.add("outputs", outputs);
        event.add("inputs", inputs);
        if (inputs.size() == 1) {
            event.add("input_resource_id", inputs.get(0).getAsJsonObject().get("resource_id").deepCopy());
            event.add("input_amount", inputs.get(0).getAsJsonObject().get("amount").deepCopy());
        }
        journal(level).append(event);
    }

    private static boolean hasPositiveResource(JsonArray resources) {
        for (var raw : resources) {
            if (!raw.isJsonObject()) continue;
            JsonObject value = raw.getAsJsonObject();
            if (!value.has("identity") || !value.get("identity").isJsonObject()
                    || !value.has("resource_id") || !value.has("amount")) continue;
            try {
                if (value.get("amount").getAsBigDecimal().longValueExact() > 0
                        && ResourceIdentity.key(value.getAsJsonObject("identity")).equals(value.get("resource_id").getAsString())) return true;
            } catch (RuntimeException invalid) { /* Unverifiable resource descriptors cannot prove native completion. */ }
        }
        return false;
    }

    private static JsonObject item(ServerLevel level, ItemStack stack) {
        JsonObject result = new JsonObject(), identity = ResourceIdentity.item(stack, level.registryAccess());
        result.addProperty("resource_id", ResourceIdentity.key(identity));
        result.add("identity", identity); result.addProperty("amount", stack.getCount()); return result;
    }

    public static void recordTransfer(ServerLevel level, BlockPos source, BlockPos destination,
                                      JsonObject identity, long amount, String provenance) {
        recordTransfer(level, source, destination, identity, amount, provenance, null);
    }

    /** Only native successful extraction callbacks may freeze this marker, never the later delivery callback. */
    public static ProductionEventJournal.OrderingMarker markExtraction(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Extraction marker requires the server thread");
        return journal(level).markExtraction(level.getGameTime());
    }

    public static void recordTransfer(ServerLevel level, BlockPos source, BlockPos destination,
                                      JsonObject identity, long amount, String provenance,
                                      ProductionEventJournal.OrderingMarker extraction) {
        if (!level.getServer().isSameThread() || source == null || destination == null || amount <= 0) return;
        JsonObject event = new JsonObject();
        event.addProperty("kind", "resource_transferred");
        event.addProperty("producer", key(source));
        event.addProperty("source", key(source));
        event.addProperty("destination", key(destination));
        event.addProperty("tick", level.getGameTime());
        event.addProperty("provenance", provenance);
        event.addProperty("resource_id", ResourceIdentity.key(identity));
        event.add("identity", identity);
        event.addProperty("amount", amount);
        if (extraction != null) {
            event.addProperty("extraction_scope", extraction.scope());
            event.addProperty("extraction_sequence", extraction.sequence());
            event.addProperty("extraction_tick", extraction.tick());
        }
        journal(level).append(event);
    }

    public static JsonObject inspect(ServerPlayer player, JsonObject body) {
        if (player.getServer() == null || !player.getServer().isSameThread()) {
            throw ServerAccess.denied("wrong_thread", "Production history requires the server thread");
        }
        Set<String> producers = new HashSet<>();
        Set<BlockPos> targets = new HashSet<>();
        JsonArray positions = body.has("positions") ? body.getAsJsonArray("positions") : new JsonArray();
        if (body.has("position")) positions = positions.deepCopy();
        if (body.has("position")) positions.add(body.get("position"));
        if (positions.isEmpty() || positions.size() > 4) throw ServerAccess.denied("invalid_argument", "One to four producer positions required");
        for (var raw : positions) {
            BlockPos pos = ServerAccess.position(raw.getAsJsonObject());
            targets.add(pos);
            producers.add(key(pos));
        }
        String expected = body.has("scope") ? ServerAccess.text(body, "scope") : null;
        ProductionEventJournal journal = journal(player.serverLevel());
        Object connection = connection(player);
        if (body.has("release_watch") && ServerAccess.bool(body, "release_watch")) {
            // Only this connection's prior reservation is removed. Cleanup reads no target/world data.
            return journal.release(connection, producers, expected);
        }
        for (BlockPos pos : targets) ServerAccess.check(player, pos, false);
        long after;
        try {
            boolean requestedBaseline = body.has("baseline") && ServerAccess.bool(body, "baseline");
            boolean baseline = !body.has("after_sequence") || body.get("after_sequence").isJsonNull()
                    || requestedBaseline;
            after = baseline ? journal.latestSequence() : body.get("after_sequence").getAsBigDecimal().longValueExact();
            if (after < 0) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw ServerAccess.denied("invalid_argument", "Invalid event cursor"); }
        if (journal.validCursor(after, expected) && !journal.retain(connection, producers)) {
            throw ServerAccess.denied("retention_limit", "Release completed production watches before retaining more endpoints");
        }
        JsonObject result = journal.page(after, producers, player.serverLevel().getGameTime(), expected);
        result.add("retention", journal.retention(connection, producers));
        return result;
    }

    /** Called for logout, respawn or world change; replacement players can share the same connection. */
    public static void disconnected(ServerPlayer player) {
        Object connection = connection(player);
        for (ProductionEventJournal journal : JOURNALS.values()) journal.disconnected(connection);
    }

    private static Object connection(ServerPlayer player) { return player.connection == null ? player : player.connection; }

    /** Server modules may read native history; this is not an RPC event ingestion endpoint. */
    public static ProductionEventJournal trustedJournal(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Native history requires the server thread");
        return journal(level);
    }

    private static ProductionEventJournal journal(ServerLevel level) {
        return JOURNALS.computeIfAbsent(level, key -> new ProductionEventJournal(key.dimension().location().toString()));
    }

    public static String key(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }
}
