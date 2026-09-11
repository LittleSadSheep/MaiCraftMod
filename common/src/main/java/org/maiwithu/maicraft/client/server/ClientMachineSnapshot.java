// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import static org.maiwithu.maicraft.client.server.ClientRequestReceipt.*;

/** Client-only observations use the snapshot schema while explicitly retaining unknown server facts. */
final class ClientMachineSnapshot implements ClientFallback {
    @Override public boolean supported() { return true; }

    @Override public Availability availability(JsonObject arguments) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return Availability.unavailable("no_world");
        if (!arguments.has("positions")) return Availability.unavailable("positions_required");
        if (!arguments.get("positions").isJsonArray() || arguments.getAsJsonArray("positions").isEmpty()
                || arguments.getAsJsonArray("positions").size() > 4)
            return Availability.unavailable("positions_must_contain_1_to_4_blocks");
        return Availability.ready();
    }

    @Override public void submit(UUID id, JsonObject arguments, Consumer<Result> completion) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) throw new IllegalStateException("client snapshot is client-thread only");
        JsonObject result = new JsonObject();
        result.addProperty("schema", "maicraft.machine_snapshot.v1");
        result.addProperty("dimension", minecraft.level.dimension().location().toString());
        result.addProperty("tick", minecraft.level.getGameTime());
        result.addProperty("scope", "client_loaded_world");
        result.addProperty("complete", false);
        result.addProperty("truncated", false);
        JsonArray observations = new JsonArray();
        for (var element : arguments.getAsJsonArray("positions")) {
            JsonObject point = element.getAsJsonObject();
            BlockPos position = new BlockPos(point.get("x").getAsInt(), point.get("y").getAsInt(), point.get("z").getAsInt());
            JsonObject observation = new JsonObject();
            observation.add("position", point.deepCopy());
            observation.addProperty("tick", minecraft.level.getGameTime());
            observation.addProperty("provenance", "client_world_observation");
            JsonArray unknown = new JsonArray();
            if (minecraft.level.isLoaded(position)) {
                observation.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(
                        minecraft.level.getBlockState(position).getBlock()).toString());
                observation.addProperty("block_state", minecraft.level.getBlockState(position).toString());
            } else unknown.add("chunk_not_loaded");
            unknown.add("authoritative_resources_require_server_or_visible_native_menu");
            unknown.add("port_compatibility_membership_and_production_not_observed");
            observation.add("unknown", unknown);
            observation.add("resources", new JsonArray());
            observation.add("ports", new JsonArray());
            observation.add("native", new JsonObject());
            observations.add(observation);
        }
        result.add("observations", observations);
        completion.accept(new Result(Status.SUCCEEDED, Effect.NOT_APPLIED, result, "", "client observation only"));
    }
}
