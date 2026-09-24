// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.function.BiConsumer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.mcp.MetadataSearch;

/** 直接在已加载区块的方块实体索引检索动力，公开少量接口候选；不移动角色、不打开机器，也不返回整份方块目录。 */
public final class KineticSourceQueries {
    private KineticSourceQueries() {}

    public static JsonObject observe(LocalPlayer player, int radius, int limit, String query, String focus,
            BiConsumer<String, BlockPos> remember) {
        var origin = player.blockPosition();
        var matcher = query == null ? null : new MetadataSearch.Query(query);
        var scan = new KineticSourceDiscovery(origin, radius, 0, player.getEyePosition(), origin.getY(), state -> {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return (focus == null || focus.equals(id)) && (matcher == null || matcher.match(id, state.getBlock().getName().getString(),
                    id + " " + state.getBlock().getName().getString() + " 应力源 动力源 kinetic stress rotational source input") != null);
        });
        // 单次只读请求按时间与实体样本双重限额收尾；部分扫描必须明说，不能把预算截止解释为附近没有网络。
        long deadline = System.nanoTime() + 20_000_000L;
        boolean complete;
        do { complete = scan.tick(player.clientLevel); } while (!complete && System.nanoTime() < deadline);
        var found = new LinkedHashMap<BlockPos, JsonObject>();
        for (var value : scan.sources(player.clientLevel)) {
            if (!value.powered()) continue;
            BlockPos at = value.endpoint().position();
            if (!found.containsKey(at) && found.size() >= Math.min(limit, 8)) continue;
            JsonObject candidate = found.computeIfAbsent(at, key -> {
                String label = "kinetic_" + Integer.toHexString(player.level().dimension().hashCode()) + "_" + key.getX() + "_" + key.getY() + "_" + key.getZ();
                remember.accept(label, key);
                var row = new JsonObject(); row.addProperty("source_label", label); row.addProperty("block_id", value.blockId());
                row.addProperty("name", player.level().getBlockState(key).getBlock().getName().getString());
                row.addProperty("rpm", value.rpm()); row.addProperty("powered", true);
                row.addProperty("distance", Math.sqrt(key.distSqr(origin))); row.addProperty("height_delta", key.getY() - origin.getY());
                row.addProperty("additional_stress_capacity", "requires_runtime_check");
                row.addProperty("operation_authorized", false); row.add("ports", new JsonArray()); return row;
            });
            var port = new JsonObject(); port.addProperty("axis", value.endpoint().axis().getName());
            var faces = new JsonArray(); value.endpoint().shaftFaces().forEach(face -> faces.add(face.getName()));
            port.add("shaft_faces", faces); port.addProperty("chain_interface", value.endpoint().chainInterface());
            candidate.getAsJsonArray("ports").add(port);
        }
        var out = new JsonObject(); var candidates = new JsonArray(); found.values().forEach(candidates::add);
        out.add("candidates", candidates); out.addProperty("scan_complete", complete && scan.samples() < 8192);
        out.addProperty("more_candidates_may_exist", found.size() >= Math.min(limit, 8) || !complete || scan.samples() >= 8192);
        out.addProperty("sample_limit_reached", scan.samples() >= 8192); out.addProperty("sampled_block_entities", scan.samples());
        out.addProperty("candidate_limit", Math.min(limit, 8)); out.addProperty("radius", radius);
        out.addProperty("maximum_height_delta", KineticSourceScope.HEIGHT_DELTA);
        out.addProperty("scope", "loaded chunk block-entity index; visible outlets near current work height; protected uses excluded; ownership not inferred");
        out.addProperty("next_step", "Use a candidate source_label only within the player's authorized work area or known shared network. Otherwise request a known authorized outlet. No candidates in a bounded/partial search do not prove the surrounding world has no power. Native connection checks stress and interfaces again.");
        out.addProperty("observation_only", true); return out;
    }
}
