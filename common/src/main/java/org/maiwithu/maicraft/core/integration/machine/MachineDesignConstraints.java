// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** 机器展开和原生材料结算共用任务约束，不能借管线、部件或配置工具绕开禁用模组。 */
public final class MachineDesignConstraints {
    private MachineDesignConstraints() {}

    public static Set<String> forbiddenMods(JsonObject document) {
        if (!document.has("constraints")) return Set.of();
        if (!document.get("constraints").isJsonObject()) throw new IllegalArgumentException("constraints must be an object");
        return read(document.getAsJsonObject("constraints"));
    }

    public static Set<String> read(JsonObject constraints) {
        if (!constraints.has("forbidden_mods")) return Set.of();
        var raw = constraints.get("forbidden_mods");
        if (!raw.isJsonArray() || raw.getAsJsonArray().size() > 64)
            throw new IllegalArgumentException("forbidden_mods must contain at most 64 registry namespaces");
        Set<String> result = new LinkedHashSet<>();
        for (var entry : raw.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()
                    || !entry.getAsString().matches("[a-z0-9_.-]{1,64}"))
                throw new IllegalArgumentException("forbidden_mods requires exact registry namespaces");
            result.add(entry.getAsString());
        }
        return Set.copyOf(result);
    }

    public static JsonObject json(Set<String> forbidden) {
        JsonObject result = new JsonObject(); JsonArray values = new JsonArray();
        forbidden.stream().sorted().forEach(values::add); result.add("forbidden_mods", values); return result;
    }

    public static boolean allows(Set<String> forbidden, String id) {
        int separator = id.indexOf(':');
        return separator > 0 && !forbidden.contains(id.substring(0, separator));
    }

    public static void requireAllowed(Set<String> forbidden, String id) {
        if (!allows(forbidden, id)) throw new IllegalArgumentException("forbidden_mod_dependency: " + id);
    }

    /** 最后按真正拿在手里的材料和配置工具复核，而不只检查 LLM 声明的主设备名称。 */
    public static void verifyMaterials(JsonObject document, JsonObject report) {
        Set<String> forbidden = forbiddenMods(document);
        if (report.has("native_material_counts"))
            report.getAsJsonObject("native_material_counts").keySet().forEach(id -> requireAllowed(forbidden, id));
        if (report.has("required_tools"))
            report.getAsJsonArray("required_tools").forEach(id -> requireAllowed(forbidden, id.getAsString()));
    }
}
