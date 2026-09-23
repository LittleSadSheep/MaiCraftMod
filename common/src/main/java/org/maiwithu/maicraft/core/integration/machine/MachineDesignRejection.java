// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** 把设计拒绝变成可定位的修订资料；不会为了修图请求重新扫描无关世界状态。 */
public final class MachineDesignRejection extends IllegalArgumentException {
    private final JsonArray issues = new JsonArray();
    private final int count;
    public MachineDesignRejection(List<JsonObject> failures) {
        super(failures.getFirst().get("message").getAsString());
        count = failures.size(); failures.stream().limit(32).forEach(row -> issues.add(row.deepCopy()));
    }
    public JsonObject details() {
        JsonObject result = new JsonObject(); result.add("design_diagnostics", issues.deepCopy());
        result.addProperty("diagnostic_count", count); result.addProperty("diagnostics_truncated", count > 32);
        result.addProperty("rules_uri", "maicraft://knowledge/machine_assembly"); return result;
    }
    public static JsonObject issue(String message, String path) {
        String text = message == null ? "invalid_blueprint" : message;
        String code = text.split("[:;\\s]", 2)[0];
        JsonObject row = new JsonObject(); row.addProperty("code", code.matches("[a-z][a-z0-9_]+") ? code : "invalid_blueprint");
        row.addProperty("path", path); row.addProperty("message", text.length() > 1024 ? text.substring(0, 1024) : text);
        row.addProperty("next_action", "Revise the indicated design while preserving expected_output and forbidden_mods, then review it again. Runtime-only unknowns require targeted inspection.");
        return row;
    }
    public static JsonObject beltIssue(String message, String path, JsonElement raw, Map<BlockPos, JsonObject> blocks) {
        JsonObject issue = issue(message, path);
        if (!raw.isJsonObject()) return issue;
        JsonObject context = new JsonObject();
        for (String key : List.of("first", "second")) {
            var value = raw.getAsJsonObject().get(key);
            if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) continue;
            if (value.getAsJsonArray().asList().stream().anyMatch(axis -> !axis.isJsonPrimitive() || !axis.getAsJsonPrimitive().isNumber() || axis.getAsString().length() > 20)) continue;
            context.add(key, value.deepCopy());
            try {
                var block = blocks.get(MachineAssemblyDocument.position(value));
                if (block != null) context.addProperty(key + "_axis", MachineAssemblyDocument.properties(block).get("axis"));
            } catch (IllegalArgumentException invalidPosition) { context.addProperty(key + "_valid", false); }
        }
        context.addProperty("shaft_rule", "Endpoint and intermediate pulley axes must match and be perpendicular to belt travel. Omit endpoint blocks to derive their axes; path splits horizontal turns.");
        issue.add("context", context); return issue;
    }
    public static JsonObject inspectRequest(JsonElement request) {
        if (request == null || !request.isJsonObject()) return null;
        var goal = request.getAsJsonObject().get("goal");
        if (goal == null || !goal.isJsonObject()) return null;
        var parameters = goal.getAsJsonObject().get("parameters");
        if (parameters == null || !parameters.isJsonObject()) return null;
        var blueprint = parameters.getAsJsonObject().get("blueprint");
        if (blueprint == null || !blueprint.isJsonObject()) return null;
        // 即使外层目标字段出错，也可同时指出独立的蓝图格式错误；所有操作都停留在纯设计审阅。
        try { MachineBlueprintDocument.validateWire(blueprint.getAsJsonObject()); return null; }
        catch (MachineDesignRejection rejected) { return rejected.details(); }
        catch (IllegalArgumentException rejected) { return new MachineDesignRejection(List.of(issue(rejected.getMessage(), "goal.parameters.blueprint"))).details(); }
        // 可选模组未就绪时保留主请求的拒绝结果，补充预检未知，不能让诊断生成覆盖原来的错误。
        catch (RuntimeException | LinkageError unavailable) { return new MachineDesignRejection(List.of(issue("blueprint_preflight_unavailable", "goal.parameters.blueprint"))).details(); }
    }
}
