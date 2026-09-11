// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Native state snapshots are observations; neither geometry nor inventory presence proves production. */
public final class ServerMachineSnapshot {
    private ServerMachineSnapshot() {}

    public static JsonObject inspect(ServerPlayer player, JsonObject body) {
        JsonArray positions = body.getAsJsonArray("positions");
        if (positions == null || positions.isEmpty() || positions.size() > 4) {
            throw ServerAccess.denied("invalid_argument", "Observe between one and four positions per page");
        }
        int offset = body.has("resource_offset") ? ServerAccess.integer(body, "resource_offset", 0, 4096) : 0;
        int limit = body.has("resource_limit") ? ServerAccess.integer(body, "resource_limit", 1, 128) : 64;
        SnapshotBudget budget = new SnapshotBudget(offset, limit);
        Set<Direction> faces = new LinkedHashSet<>();
        if (body.has("faces")) {
            if (body.getAsJsonArray("faces").size() > 6) throw ServerAccess.denied("invalid_argument", "At most six faces are allowed");
            for (JsonElement face : body.getAsJsonArray("faces")) {
                Direction direction = Direction.byName(face.getAsString());
                if (direction == null) throw ServerAccess.denied("invalid_argument", "Unknown face");
                faces.add(direction);
            }
        } else faces.addAll(java.util.List.of(Direction.values()));
        JsonObject result = new JsonObject();
        result.addProperty("schema", "maicraft.machine_snapshot.v1");
        result.addProperty("dimension", player.serverLevel().dimension().location().toString());
        result.addProperty("tick", player.serverLevel().getGameTime());
        JsonArray observations = new JsonArray();
        result.add("observations", observations);
        boolean complete = true;
        for (JsonElement position : positions) {
            BlockPos pos = ServerAccess.position(position.getAsJsonObject());
            BlockEntity entity = ServerAccess.check(player, pos, false);
            JsonObject observation = new JsonObject();
            observation.add("position", position.deepCopy());
            observation.addProperty("tick", player.serverLevel().getGameTime());
            observation.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(player.serverLevel().getBlockState(pos).getBlock()).toString());
            observation.addProperty("provenance", "server_native");
            observation.addProperty("resource_views_may_overlap", true);
            observation.add("resources", new JsonArray());
            observation.add("ports", new JsonArray());
            observation.add("native", new JsonObject());
            observation.add("unknown", new JsonArray());
            for (Direction side : faces) NativePortSnapshot.inspect(player, pos, side, observation, budget);
            if (entity != null) {
                CreateMachineAdapter.inspect(player, entity, observation);
                MekanismMachineAdapter.inspect(entity, observation);
                MekanismConnectionConfiguration.inspect(entity, observation);
                Ae2MachineObservation.inspect(entity, observation);
                JsonObject mekanism = observation.getAsJsonObject("native").getAsJsonObject("mekanism");
                if (mekanism != null && mekanism.has("multiblock")) {
                    JsonObject structure = mekanism.getAsJsonObject("multiblock");
                    if (structure.has("membership") && !structure.get("membership").isJsonNull()) {
                        String membership = "mekanism:multiblock:" + structure.get("membership").getAsString();
                        for (JsonElement resource : observation.getAsJsonArray("resources")) {
                            resource.getAsJsonObject().addProperty("membership", membership);
                            resource.getAsJsonObject().addProperty("shared_multiblock_view", true);
                        }
                    }
                }
            }
            complete &= observation.getAsJsonArray("unknown").isEmpty();
            if (result.toString().length() + observation.toString().length() > 54_000) { budget.truncate(); break; }
            observations.add(observation);
        }
        result.addProperty("truncated", budget.truncated());
        result.addProperty("complete", complete && !budget.truncated());
        result.addProperty("next_resource_offset", budget.nextOffset());
        result.addProperty("resource_scope", "sided_views_do_not_sum_across_sides");
        result.addProperty("flow_verified", false);
        result.addProperty("production_verified", false);
        return result;
    }
}
