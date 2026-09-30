// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.integration.create.CreateBeltAccess;
import org.maiwithu.maicraft.core.integration.create.CreateDeployerHandEvidence;
import org.maiwithu.maicraft.core.integration.create.CreateKineticCapabilities;

/** 整机运行事实独立于结构匹配；直接给出各条原生皮带及其余传动部件的状态，不代替模型判断设计。 */
public final class MachineOperatingState {
    private MachineOperatingState() {}

    public static JsonObject observe(Level world, String dimension, BlockPos anchor, List<BlockPos> positions) {
        JsonArray components = new JsonArray(), unavailable = new JsonArray();
        Map<BlockPos, List<JsonObject>> belts = new LinkedHashMap<>();
        Map<BlockPos, Boolean> beltIdentity = new LinkedHashMap<>();
        int moving = 0, stopped = 0, unknown = 0;
        boolean sameWorld = world.dimension().location().toString().equals(dimension);
        for (BlockPos at : new LinkedHashSet<>(positions)) {
            if (!sameWorld || !world.isLoaded(at)) { unavailable.add(offset(at, anchor)); continue; }
            var block = world.getBlockState(at);
            if (!CreateKineticCapabilities.isKinetic(block)) continue;
            JsonObject state = MachineSurvey.kineticFields(world.getBlockEntity(at));
            if (state == null) state = new JsonObject(); else state = state.deepCopy();
            state.remove("freshness");
            boolean readable = state.has("speed_rpm") && state.has("has_network") && state.has("overstressed");
            state.addProperty("observation_status", readable ? "observed" : "unknown");
            if (!readable) unknown++;
            else if (state.get("speed_rpm").getAsDouble() == 0) stopped++;
            else moving++;
            String id = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            JsonObject row = new JsonObject(); row.add("offset", offset(at, anchor)); row.add("state", state);
            if (id.equals("create:belt")) {
                BlockPos controller = CreateBeltAccess.controller(world, at);
                BlockPos key = controller == null ? at : controller;
                beltIdentity.put(key, controller != null);
                belts.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            } else {
                row.addProperty("block_id", id);
                // 机械手已同步的持料必须随运行状态直接交付，空手与未收到同步不能混为一谈。
                var hand = CreateDeployerHandEvidence.capture(world, at);
                if (!hand.isEmpty()) {
                    JsonObject held = new Gson().toJsonTree(hand).getAsJsonObject();
                    held.remove("received_update_revision"); held.remove("provenance");
                    row.add("held_item", held);
                }
                components.add(row);
            }
        }
        JsonArray beltRows = new JsonArray(); int movingBelts = 0, stoppedBelts = 0, unknownBelts = 0;
        for (var entry : belts.entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("controller_known", beltIdentity.get(entry.getKey()));
            row.add("controller_offset", offset(entry.getKey(), anchor));
            JsonArray members = new JsonArray(); entry.getValue().forEach(value -> members.add(value.get("offset")));
            row.add("member_offsets", members);
            JsonObject first = entry.getValue().getFirst().getAsJsonObject("state");
            boolean uniform = entry.getValue().stream().allMatch(value -> first.equals(value.get("state")));
            // 同一原生控制器的相同状态只展示一次；同步状态不一致时保留每段事实，不合并成虚假的整带转速。
            if (uniform) row.add("state", first.deepCopy());
            else { JsonArray segments = new JsonArray(); entry.getValue().forEach(segments::add); row.add("segment_states", segments); }
            if (!beltIdentity.get(entry.getKey()) || !uniform || !"observed".equals(first.get("observation_status").getAsString())) unknownBelts++;
            else if (first.get("speed_rpm").getAsDouble() == 0) stoppedBelts++;
            else movingBelts++;
            beltRows.add(row);
        }
        JsonObject result = new JsonObject();
        result.addProperty("schema", "machine_operating_state.v1");
        result.addProperty("scope", "kinetic components in the inspected machine targets; rotation and held items are observations, not production proof");
        result.addProperty("provenance", "client_synced_native_fields_may_lag_server");
        result.addProperty("observed_at_tick", world.getGameTime());
        result.addProperty("complete", unavailable.isEmpty() && unknown == 0 && unknownBelts == 0);
        result.addProperty("rotating_components", moving); result.addProperty("stopped_components", stopped);
        result.addProperty("unknown_components", unknown);
        result.addProperty("rotating_belts", movingBelts); result.addProperty("stopped_belts", stoppedBelts);
        result.addProperty("unknown_belt_groups", unknownBelts);
        result.add("belts", beltRows); result.add("other_kinetic_components", components);
        result.add("unavailable_target_offsets", unavailable);
        result.addProperty("production_verified", false);
        return result;
    }

    static JsonArray offset(BlockPos at, BlockPos anchor) {
        var value = new JsonArray(); value.add(at.getX() - anchor.getX()); value.add(at.getY() - anchor.getY()); value.add(at.getZ() - anchor.getZ()); return value;
    }
}
