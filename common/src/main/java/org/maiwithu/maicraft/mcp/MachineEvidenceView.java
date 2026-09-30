package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同一回执中的接口和原生状态共享表达；索引定义就在本页，不要求模型另开工具调用。 */
final class MachineEvidenceView {
    private MachineEvidenceView() {}

    static void shareStates(JsonObject evidence) {
        JsonArray nativeStates = evidence.has("native_states") ? evidence.getAsJsonArray("native_states") : new JsonArray();
        JsonArray ports = evidence.has("port_profiles") ? evidence.getAsJsonArray("port_profiles") : new JsonArray();
        for (var raw : evidence.getAsJsonArray("components")) {
            JsonObject component = raw.getAsJsonObject();
            for (var value : component.getAsJsonArray("samples")) {
                JsonObject sample = value.getAsJsonObject();
                if (sample.has("native")) sample.addProperty("native_state_index", index(nativeStates, sample.remove("native")));
                if (sample.has("ports")) sample.addProperty("port_profile_index", index(ports, sample.remove("ports")));
            }
        }
        evidence.add("native_states", nativeStates); evidence.add("port_profiles", ports);
        evidence.addProperty("sample_index_meaning", "Each sample uses native_states[native_state_index] and port_profiles[port_profile_index] from this same report; all values are present, not deferred reads.");
    }

    private static int index(JsonArray values, JsonElement value) {
        // 只共享完全相等的对象；真实速度、库存、未知状态或接口配置不同就保留独立定义。
        for (int index = 0; index < values.size(); index++) if (values.get(index).equals(value)) return index;
        values.add(value); return values.size() - 1;
    }

    static void compactControl(JsonObject result, JsonObject source) {
        if (!result.has("control_analysis") || !source.has("as_built_blueprint")) return;
        JsonObject layout = source.getAsJsonObject("as_built_blueprint");
        if (!layout.has("blocks") || !layout.has("anchor")) return;
        Map<String, JsonObject> observed = new LinkedHashMap<>(); JsonArray anchor = layout.getAsJsonArray("anchor");
        for (var raw : layout.getAsJsonArray("blocks")) {
            JsonObject block = raw.getAsJsonObject(); JsonArray at = new JsonArray(), offset = block.getAsJsonArray("offset");
            for (int axis = 0; axis < 3; axis++) at.add(anchor.get(axis).getAsInt() + offset.get(axis).getAsInt());
            observed.put(at.toString(), block);
        }
        JsonObject control = result.getAsJsonObject("control_analysis");
        if (!control.has("components")) return;
        for (var raw : control.getAsJsonArray("components")) {
            JsonObject component = raw.getAsJsonObject();
            if (!component.has("facts")) continue;
            JsonObject facts = component.getAsJsonObject("facts");
            JsonObject block = facts.has("storage_position") ? observed.get(facts.get("storage_position").toString()) : null;
            if (block == null) continue;
            // 控制连线继续完整保留；与当前地图逐字段相等的方块身份、朝向等无需在图节点中再次复印。
            for (String field : List.of("block_id", "properties"))
                if (facts.has(field) && facts.get(field).equals(block.get(field))) facts.remove(field);
        }
        control.addProperty("layout_facts", "Unchanged block_id/properties are shared with as_built_blueprint at each storage_position; connection and control facts remain explicit.");
    }
}
