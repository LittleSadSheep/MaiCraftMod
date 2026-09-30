// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 面向整机决策的原生事实：部件状态、接口与材料直接给全，侧面别名不累计成库存。 */
final class MachineComponentFacts {
    private MachineComponentFacts() {}

    static JsonArray collect(JsonArray pages) {
        Map<String, JsonObject> components = new LinkedHashMap<>();
        for (var rawPage : pages) {
            JsonObject page = rawPage.getAsJsonObject();
            if (!page.has("observations")) continue;
            for (var raw : page.getAsJsonArray("observations")) {
                JsonObject observation = raw.getAsJsonObject();
                JsonElement offset = page.get("component_offset");
                String key = offset.toString();
                JsonObject component = components.computeIfAbsent(key, ignored -> {
                    JsonObject value = new JsonObject(); value.add("offset", offset.deepCopy());
                    value.add("block_id", observation.get("block_id").deepCopy());
                    value.add("observed_from_tick", observation.get("tick").deepCopy());
                    value.add("samples", new JsonArray()); return value;
                });
                component.add("observed_through_tick", observation.get("tick").deepCopy());
                JsonObject sample = new JsonObject();
                sample.add("native", prune(observation.get("native")));
                sample.add("ports", ports(observation));
                sample.add("resources", resources(observation));
                sample.add("unknown", observation.get("unknown").deepCopy());
                sample.add("resource_page_complete", page.get("complete").deepCopy());
                JsonArray samples = component.getAsJsonArray("samples");
                // 相同状态只延长观察时间；跨页或跨刻出现变化时保留各次样本，不能拼成同一时刻的库存。
                JsonObject previous = samples.isEmpty() ? null : samples.get(samples.size() - 1).getAsJsonObject();
                JsonObject comparable = previous == null ? null : previous.deepCopy();
                if (comparable != null) { comparable.remove("from_tick"); comparable.remove("through_tick"); }
                if (sample.equals(comparable)) previous.add("through_tick", observation.get("tick").deepCopy());
                else {
                    sample.add("from_tick", observation.get("tick").deepCopy());
                    sample.add("through_tick", observation.get("tick").deepCopy()); samples.add(sample);
                }
            }
        }
        JsonArray result = new JsonArray(); components.values().forEach(result::add); return result;
    }

    private static JsonArray ports(JsonObject observation) {
        Map<String, JsonObject> groups = new LinkedHashMap<>();
        JsonArray values = new JsonArray();
        for (String field : List.of("ports", "absent_ports"))
            if (observation.has(field)) observation.getAsJsonArray(field).forEach(values::add);
        for (var raw : values) {
            JsonObject shared = raw.getAsJsonObject().deepCopy();
            JsonArray sides = shared.has("sides") ? shared.remove("sides").getAsJsonArray() : new JsonArray();
            if (shared.has("side")) sides.add(shared.remove("side"));
            // 接口方向未检测是统一语义，不为每个不存在的能量、化学品接口重复三次 unknown。
            for (String field : List.of("input", "output", "resource_compatibility"))
                if (shared.has(field) && "unknown".equals(shared.get(field).getAsString())) shared.remove(field);
            JsonObject group = groups.computeIfAbsent(shared.toString(), ignored -> {
                shared.add("sides", new JsonArray()); return shared;
            });
            sides.forEach(group.getAsJsonArray("sides")::add);
        }
        JsonArray result = new JsonArray(); groups.values().forEach(result::add); return result;
    }

    private static JsonArray resources(JsonObject observation) {
        Map<String, JsonObject> groups = new LinkedHashMap<>();
        if (observation.has("resources")) for (var raw : observation.getAsJsonArray("resources")) {
            JsonObject value = raw.getAsJsonObject().deepCopy();
            JsonObject view = new JsonObject();
            for (String field : List.of("side", "slot", "tank")) if (value.has(field)) view.add(field, value.remove(field));
            // 物品身份、数量、容量及原生成员关系不变；只把相同资源的各个侧面视图收在同一行。
            for (String field : List.of("storage_id", "provenance", "external_change_attribution", "views_may_overlap")) value.remove(field);
            if (value.has("amount")) value.add("amount_per_view", value.remove("amount"));
            JsonObject group = groups.computeIfAbsent(value.toString(), ignored -> {
                value.add("views", new JsonArray()); return value;
            });
            group.getAsJsonArray("views").add(view);
        }
        JsonArray result = new JsonArray(); groups.values().forEach(result::add); return result;
    }

    private static JsonElement prune(JsonElement raw) {
        if (raw == null) return new JsonObject();
        if (!raw.isJsonObject()) return raw.deepCopy();
        JsonObject result = new JsonObject();
        for (var field : raw.getAsJsonObject().entrySet()) {
            // 生产归因的工具提示不属于某台设备的现场状态；真实速度、网络、滤镜和加工进度均保留。
            if (field.getKey().equals("production_attribution")) continue;
            result.add(field.getKey(), prune(field.getValue()));
        }
        return result;
    }
}
