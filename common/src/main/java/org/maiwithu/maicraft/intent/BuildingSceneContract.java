// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.Set;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompiler;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;

/**
 * 定义建模操作的输入规则，它们仍属于 build 或 design_build 的模式。design_build 只能查看和准备，不能启动施工。
 */
final class BuildingSceneContract {
    static final Set<String> OPERATIONS = Set.of("create_scene", "update_scene", "get_scene_info",
            "get_object_info", "export_scene", "preview", "build");
    private static final Set<String> FIELDS = Set.of("operation", "scene", "scene_id", "blueprint",
            "edits", "object_name", "format", "page", "replace_existing", "material_policy", "protected_labels");

    private BuildingSceneContract() {}

    // 在这两个能力里，只要带 scene、scene_id、blueprint 或 operation，就采用这一套模型规则，不再走普通房屋模板参数。
    static boolean supports(Goal goal) {
        if (!"maicraft:build".equals(goal.ability()) && !BuildDesignAdapter.ABILITY.equals(goal.ability())) return false;
        var p = goal.parameters();
        return p.has("scene") || p.has("scene_id") || p.has("blueprint") || p.has("operation");
    }

    // 省略 operation 时，design_build 默认预览，build 默认实际施工；调用方要只保存模型时必须明确 create_scene。
    static String operation(Goal goal) {
        return goal.parameters().has("operation") ? string(goal.parameters(), "operation")
                : BuildDesignAdapter.ABILITY.equals(goal.ability()) ? "preview" : "build";
    }

    static boolean noConstruction(Goal goal) {
        return supports(goal) && !"build".equals(operation(goal));
    }

    static void validate(Goal goal) {
        var p = goal.parameters();
        for (String key : p.keySet()) if (!FIELDS.contains(key))
            throw new IllegalArgumentException("Explicit building models do not accept " + key
                    + "; encode geometry and materials in the scene or blueprint.");
        String op = operation(goal);
        if (!OPERATIONS.contains(op)) throw new IllegalArgumentException("Unknown build operation: " + op);
        if (BuildDesignAdapter.ABILITY.equals(goal.ability()) && "build".equals(op))
            throw new IllegalArgumentException("design_build cannot start construction");
        // 模型原文、已保存模型编号和逐格蓝图必须且只能选一个来源，避免含糊地把几份设计叠加。
        int sources = (p.has("scene") ? 1 : 0) + (p.has("scene_id") ? 1 : 0) + (p.has("blueprint") ? 1 : 0);
        if (sources != 1) throw new IllegalArgumentException("Supply exactly one of scene, scene_id or blueprint");
        if (p.has("scene")) BuildingSceneCompiler.validateWire(object(p, "scene"));
        if (p.has("blueprint")) MachineBlueprintDocument.validateWire(object(p, "blueprint"));
        if (p.has("scene_id")) java.util.UUID.fromString(string(p, "scene_id"));
        if (Set.of("update_scene", "get_scene_info", "get_object_info", "export_scene").contains(op)
                && !p.has("scene_id")) throw new IllegalArgumentException(op + " needs scene_id");
        if ("create_scene".equals(op) && !p.has("scene")) throw new IllegalArgumentException("create_scene needs scene");
        if ("update_scene".equals(op)) validateEdits(object(p, "edits"));
        else if (p.has("edits")) throw new IllegalArgumentException("edits is only used by update_scene");
        if ("get_object_info".equals(op)) string(p, "object_name");
        else if (p.has("object_name")) throw new IllegalArgumentException("object_name is only used by get_object_info");
        if (p.has("format") && (!"export_scene".equals(op) || !Set.of("json", "nbt").contains(string(p, "format"))))
            throw new IllegalArgumentException("export_scene format must be json or nbt");
        // 页码仅用于场景列表，范围为 0..1024；当前还要求小数表示的 scale 不大于零，所以写 1.0 也会被拒绝。
        if (p.has("page")) {
            if (!"get_scene_info".equals(op) || !p.get("page").isJsonPrimitive()
                    || !p.getAsJsonPrimitive("page").isNumber() || p.get("page").getAsBigDecimal().scale() > 0
                    || p.get("page").getAsBigDecimal().signum() < 0
                    || p.get("page").getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(1024)) > 0)
                throw new IllegalArgumentException("get_scene_info page must be an integer from 0 to 1024");
        }
        if (p.has("replace_existing") && (!p.get("replace_existing").isJsonPrimitive()
                || !p.getAsJsonPrimitive("replace_existing").isBoolean()))
            throw new IllegalArgumentException("replace_existing must be boolean");
        if (p.has("material_policy") && !Set.of("specified", "inventory_only", "storage_available", "ordinary")
                .contains(string(p, "material_policy")))
            throw new IllegalArgumentException("Explicit models keep exact materials; choose specified, inventory_only, storage_available or ordinary");
    }

    static void validateEdits(JsonObject edits) {
        for (String key : edits.keySet()) if (!Set.of("objects", "materials", "remove_objects").contains(key))
            throw new IllegalArgumentException("Unknown scene edit: " + key);
        if (edits.isEmpty()) throw new IllegalArgumentException("edits must change objects or materials");
        // 编辑时可以只给变化字段；完整对象和对象之间的引用要等与原版本合并后再检查。
        org.maiwithu.maicraft.core.blueprint.BuildingSceneStore.validateEdits(edits);
    }

    static JsonObject object(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonObject()) throw new IllegalArgumentException(key + " must be an object");
        return value.getAsJsonObject(key);
    }

    static String string(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString()
                || value.get(key).getAsString().isBlank() || value.get(key).getAsString().length() > 256)
            throw new IllegalArgumentException(key + " must be a nonempty string of at most 256 characters");
        return value.get(key).getAsString();
    }
}
