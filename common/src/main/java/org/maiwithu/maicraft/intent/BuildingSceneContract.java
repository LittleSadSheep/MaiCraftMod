// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.Set;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompiler;
import org.maiwithu.maicraft.core.blueprint.BuildingModelContract;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;

/**
 * 定义建模操作的输入规则，它们仍属于 build 或 design_build 的模式。design_build 只能查看和准备，不能启动施工。
 */
final class BuildingSceneContract {
    static final Set<String> OPERATIONS = BuildingModelContract.OPERATIONS;
    private static final Set<String> FIELDS = Set.of("operation", "scene", "scene_id", "blueprint",
            "edits", "object_name", "component_name", "format", "page", "replace_existing", "material_policy", "protected_labels", "project_id",
            BuildingModelContract.EXPECTED_CAPABILITY, BuildingModelContract.EXPECTED_SCHEMA);

    private BuildingSceneContract() {}

    // 带模型来源或操作时校验作者设计；单独的 project_id 则由续建契约读取冻结施工单。
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
        // 设计 Agent 点名契约版本时先核对，过期请求不能保存新草稿或进入角色施工流程。
        BuildingModelContract.checkExpected(p);
        String op = operation(goal);
        if (!OPERATIONS.contains(op)) throw new IllegalArgumentException("Unknown build operation: " + op);
        if (BuildDesignAdapter.ABILITY.equals(goal.ability()) && "build".equals(op))
            throw new IllegalArgumentException("design_build cannot start construction");
        // 模型原文、已保存模型编号和逐格蓝图必须且只能选一个来源，避免含糊地把几份设计叠加。
        int sources = (p.has("scene") ? 1 : 0) + (p.has("scene_id") ? 1 : 0) + (p.has("blueprint") ? 1 : 0);
        if (sources != 1) throw new IllegalArgumentException("Supply exactly one of scene, scene_id or blueprint");
        if (p.has("scene")) BuildingSceneCompiler.validateWire(object(p, "scene"));
        // 建筑逐格输入与作者模型共用建筑预算，协议预检不得偷偷沿用机器规划的较小上限。
        if (p.has("blueprint")) MachineBlueprintDocument.validateBuildingWire(object(p, "blueprint"));
        if (p.has("scene_id")) java.util.UUID.fromString(string(p, "scene_id"));
        if (Set.of("update_scene", "get_scene_info", "get_object_info", "get_component_info", "export_scene", "revise_project").contains(op)
                && !p.has("scene_id")) throw new IllegalArgumentException(op + " needs scene_id");
        // 采用新场景修订必须明确指向旧项目，且不夹带新的取材、地点或替换权限；普通续建仍只读取冻结目标。
        if (op.equals("revise_project")) {
            java.util.UUID.fromString(string(p, "project_id"));
            if (goal.target() != null || !Set.of("operation", "scene_id", "project_id",
                    BuildingModelContract.EXPECTED_CAPABILITY,BuildingModelContract.EXPECTED_SCHEMA).containsAll(p.keySet()))
                throw new IllegalArgumentException("revise_project keeps the original site and policies; supply only scene_id and project_id");
        } else if (p.has("project_id")) throw new IllegalArgumentException("model operations accept project_id only with revise_project");
        if ("create_scene".equals(op) && !p.has("scene")) throw new IllegalArgumentException("create_scene needs scene");
        if ("update_scene".equals(op)) validateEdits(object(p, "edits"));
        else if (p.has("edits")) throw new IllegalArgumentException("edits is only used by update_scene");
        if ("get_object_info".equals(op)) string(p, "object_name");
        else if (p.has("object_name")) throw new IllegalArgumentException("object_name is only used by get_object_info");
        // 组件定义按保存的名字单独查询，不能把实例路径当定义名，也不因此启动预览或施工。
        if ("get_component_info".equals(op)) string(p, "component_name");
        else if (p.has("component_name")) throw new IllegalArgumentException("component_name is only used by get_component_info");
        if (p.has("format") && (!"export_scene".equals(op) || !Set.of("json", "nbt").contains(string(p, "format"))))
            throw new IllegalArgumentException("export_scene format must be json or nbt");
        // 场景按源对象分页，对象/组件按展开路径分页，避免大阵列后面的实例只能靠作者猜名字。
        if (p.has("page")) {
            if (!Set.of("get_scene_info", "get_object_info", "get_component_info").contains(op) || !p.get("page").isJsonPrimitive()
                    || !p.getAsJsonPrimitive("page").isNumber() || p.get("page").getAsBigDecimal().scale() > 0
                    || p.get("page").getAsBigDecimal().signum() < 0
                    || p.get("page").getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(1024)) > 0)
                throw new IllegalArgumentException("model query page must be an integer from 0 to 1024");
        }
        if (p.has("replace_existing") && (!p.get("replace_existing").isJsonPrimitive()
                || !p.getAsJsonPrimitive("replace_existing").isBoolean()))
            throw new IllegalArgumentException("replace_existing must be boolean");
        if (p.has("material_policy") && !Set.of("specified", "inventory_only", "storage_available", "ordinary")
                .contains(string(p, "material_policy")))
            throw new IllegalArgumentException("Explicit models keep exact materials; choose specified, inventory_only, storage_available or ordinary");
    }

    static void validateEdits(JsonObject edits) {
        // 公共编辑与保存使用同一套 v1/v2 字段校验；完整合并并编译成功后才发布不可变的新版本。
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
