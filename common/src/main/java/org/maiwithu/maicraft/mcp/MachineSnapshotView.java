package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/** 整机观察只消除可还原的重复表达；真实布局、运行、材料、差异和未知项不按字符预算截断。 */
final class MachineSnapshotView {
    record Observation(JsonObject snapshot, JsonObject owner, String path) {}
    private MachineSnapshotView() {}

    static Observation find(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        if (object.has("latest_snapshot") && object.get("latest_snapshot").isJsonObject())
            return new Observation(object.getAsJsonObject("latest_snapshot"), object, path + "/latest_snapshot");
        for (String key : List.of("task", "terminal", "result", "decision", "context", "data", "last_step")) {
            Observation found = find(object.get(key), path + "/" + key);
            if (found != null) return found;
        }
        return null;
    }

    static JsonObject present(JsonObject snapshot, String path, int ignoredBudget, Function<String, String> uri) {
        JsonObject result = snapshot.deepCopy();
        // 相邻格能从布局精确推导；名字猜出的角色和每次重复的通用分析教材不属于新观察。
        for (String key : List.of("native_component_offsets", "candidate_adjacencies", "candidate_adjacencies_format",
                "adjacency_meaning", "omitted_candidate_adjacencies", "analysis_rules")) result.remove(key);
        if (result.has("palette")) for (var raw : result.getAsJsonArray("palette")) {
            raw.getAsJsonObject().remove("inferred_roles"); raw.getAsJsonObject().remove("role_basis");
        }
        if (result.has("server_evidence") && result.get("server_evidence").isJsonObject()) {
            JsonObject evidence = result.getAsJsonObject("server_evidence");
            if (evidence.has("components")) {
                // 完整部件样本已携带原生事实，默认不再复印原始分页和同一库存的第二份索引。
                for (String key : List.of("pages", "occupied_resource_views", "occupied_resource_views_meaning", "component_reference")) evidence.remove(key);
                evidence.addProperty("raw_pages_path", path + "/server_evidence/pages");
                MachineEvidenceView.shareStates(evidence);
            }
        }
        MachineEvidenceView.compactControl(result, snapshot);
        if (result.has("as_built_blueprint") && result.get("as_built_blueprint").isJsonObject()) {
            result.add("as_built_blueprint", layout(result.getAsJsonObject("as_built_blueprint")));
            if (result.getAsJsonObject("as_built_blueprint").get("capture_complete").getAsBoolean()
                    && result.has("relative_blocks") && result.has("palette")) {
                // 完整地图已有机器布局，把旧索引证据转成自身位置和方块身份后移除重复的几何表。
                JsonArray rows = result.getAsJsonArray("relative_blocks"), palette = result.getAsJsonArray("palette");
                if (result.has("component_evidence")) for (var raw : result.getAsJsonArray("component_evidence")) {
                    JsonObject component = raw.getAsJsonObject();
                    if (!component.has("block_index")) continue;
                    JsonArray row = rows.get(component.remove("block_index").getAsInt()).getAsJsonArray();
                    JsonArray offset = new JsonArray(); for (int axis = 0; axis < 3; axis++) offset.add(row.get(axis));
                    component.add("offset", offset);
                    component.add("block_id", palette.get(row.get(3).getAsInt()).getAsJsonObject().get("block_id"));
                }
                for (String key : List.of("relative_blocks", "relative_blocks_format", "palette", "unlisted_space")) result.remove(key);
            }
        }
        if (result.has("operating_state") && result.has("component_evidence")) {
            JsonObject operating = result.getAsJsonObject("operating_state"); Set<String> grouped = new HashSet<>();
            if (operating.has("belts")) for (var belt : operating.getAsJsonArray("belts"))
                belt.getAsJsonObject().getAsJsonArray("member_offsets").forEach(at -> grouped.add(at.toString()));
            if (operating.has("other_kinetic_components")) for (var part : operating.getAsJsonArray("other_kinetic_components"))
                grouped.add(part.getAsJsonObject().get("offset").toString());
            if (result.has("server_evidence") && result.getAsJsonObject("server_evidence").has("components"))
                for (var part : result.getAsJsonObject("server_evidence").getAsJsonArray("components"))
                    grouped.add(part.getAsJsonObject().get("offset").toString());
            var iterator = result.getAsJsonArray("component_evidence").iterator();
            while (iterator.hasNext()) {
                JsonObject component = iterator.next().getAsJsonObject();
                if (!component.has("offset") || !grouped.contains(component.get("offset").toString())) continue;
                // 已有运行表或原生部件样本时不再复印实体存在和类型；AE 部件等独有证据仍留在这里。
                for (String key : List.of("create_kinetic_client_fields", "create_deployer_hand_client",
                        "block_entity_present", "block_entity_type", "source")) component.remove(key);
                if (component.size() == 2) iterator.remove();
            }
        }
        return result;
    }

    private static JsonObject layout(JsonObject source) {
        JsonObject result = source.deepCopy();
        if (!result.has("blocks")) return result;
        JsonArray palette = new JsonArray(), cells = new JsonArray(); Map<String, Integer> indices = new LinkedHashMap<>();
        // 石平台和围挡的方块状态只写一次；每格仍保留原坐标，空气范围和未加载格也沿用原观察语义。
        for (var raw : result.getAsJsonArray("blocks")) {
            JsonObject state = raw.getAsJsonObject().deepCopy(); JsonArray offset = state.remove("offset").getAsJsonArray();
            int index = indices.computeIfAbsent(state.toString(), ignored -> { int next = palette.size(); palette.add(state); return next; });
            JsonArray cell = offset.deepCopy(); cell.add(index); cells.add(cell);
        }
        result.remove("blocks"); result.add("palette", palette); result.add("cells", cells);
        result.addProperty("cells_format", "[offset_x,offset_y,offset_z,palette_index]; unlisted cells are air only when capture_complete=true");
        return result;
    }
}
