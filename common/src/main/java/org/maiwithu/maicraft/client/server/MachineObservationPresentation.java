// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只压缩原生观察中重复的空槽和不存在接口，让有限报告优先容纳各台机器的实际工件。 */
final class MachineObservationPresentation {
    private MachineObservationPresentation() {}

    static JsonObject compact(JsonObject page) {
        JsonObject result = page.deepCopy();
        if (result.has("observations")) for (var raw : result.getAsJsonArray("observations")) {
            if (!raw.isJsonObject()) continue;
            JsonObject observation = raw.getAsJsonObject();
            emptyItems(observation);
            absentPorts(observation);
        }
        return result;
    }

    private static void emptyItems(JsonObject observation) {
        if (!observation.has("resources")) return;
        JsonArray occupied = new JsonArray(); Map<String, JsonObject> groups = new LinkedHashMap<>();
        for (var raw : observation.getAsJsonArray("resources")) {
            if (!emptyItem(raw)) { occupied.add(raw); continue; }
            JsonObject row = raw.getAsJsonObject(), shared = row.deepCopy(), view = new JsonObject();
            for (String key : new String[]{"slot", "storage_id"}) if (shared.has(key)) view.add(key, shared.remove(key));
            // 仅合并同一原生页、同一侧且全部其他属性相同的空槽；每个存储视图仍保留，绝不跨侧累计容量。
            JsonObject group = groups.computeIfAbsent(shared.toString(), ignored -> {
                shared.add("views", new JsonArray()); return shared;
            });
            group.getAsJsonArray("views").add(view);
        }
        if (groups.isEmpty()) return;
        JsonArray empty = new JsonArray(); groups.values().forEach(empty::add);
        observation.add("resources", occupied); observation.add("empty_item_views", empty);
        observation.addProperty("empty_item_views_meaning", "Each entry preserves common fields of observed empty item slots; views retain individual slot/storage references. Capacity is per view; sided views and pages may overlap.");
    }

    private static boolean emptyItem(JsonElement raw) {
        if (!raw.isJsonObject()) return false;
        JsonObject row = raw.getAsJsonObject();
        if (!row.has("amount") || !row.get("amount").isJsonPrimitive()
                || !row.getAsJsonPrimitive("amount").isNumber() || row.get("amount").getAsBigDecimal().signum() != 0
                || !row.has("identity") || !row.get("identity").isJsonObject()) return false;
        JsonObject identity = row.getAsJsonObject("identity");
        return text(identity, "kind", "items") && text(identity, "id", "minecraft:air")
                && identity.has("components") && identity.get("components").isJsonObject()
                && identity.getAsJsonObject("components").isEmpty();
    }

    private static void absentPorts(JsonObject observation) {
        if (!observation.has("ports")) return;
        JsonArray available = new JsonArray(); Map<String, JsonObject> groups = new LinkedHashMap<>();
        for (var raw : observation.getAsJsonArray("ports")) {
            if (!raw.isJsonObject() || !text(raw.getAsJsonObject(), "status", "absent")
                    || !raw.getAsJsonObject().has("side")) { available.add(raw); continue; }
            JsonObject shared = raw.getAsJsonObject().deepCopy(); JsonElement side = shared.remove("side");
            // 接口不存在是已观察事实；按相同接口属性合并侧面，未知或可用的接口仍逐条保留原文。
            JsonObject group = groups.computeIfAbsent(shared.toString(), ignored -> {
                shared.add("sides", new JsonArray()); return shared;
            });
            group.getAsJsonArray("sides").add(side);
        }
        if (groups.isEmpty()) return;
        JsonArray absent = new JsonArray(); groups.values().forEach(absent::add);
        observation.add("ports", available); observation.add("absent_ports", absent);
        observation.addProperty("absent_ports_meaning", "Each entry applies its observed absent status and other common fields to every listed side; omitted interfaces remain unknown.");
    }

    private static boolean text(JsonObject row, String key, String expected) {
        return row.has(key) && row.get(key).isJsonPrimitive() && row.getAsJsonPrimitive(key).isString()
                && expected.equals(row.get(key).getAsString());
    }
}
