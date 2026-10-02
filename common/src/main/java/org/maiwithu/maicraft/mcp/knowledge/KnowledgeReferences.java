// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.blueprint.BuildingModelContract;

/** 能力契约和实际回执共用可直接读取的资料入口；列出入口不会执行动作或要求重新勘测。 */
public final class KnowledgeReferences {
    private KnowledgeReferences() {}

    public static JsonArray forAbility(String ability) {
        JsonArray references = new JsonArray();
        // 只为选中的能力说明相关资料用途；搜索能力名称时仍保留轻量索引，不展开整套教材。
        switch (ability) {
            case "maicraft:physical_balance" -> references.add(resource(KnowledgeLibrary.PHYSICS,"起飞前工况、配重补丁、气球容积和预测证据边界"));
            case "maicraft:build", "maicraft:design_build" -> {
                references.add(resource(BuildingModelContract.INDEX_URI, "建筑模型的当前格式、图元和完整 Schema"));
                references.add(resource(KnowledgeLibrary.BLUEPRINT, "建筑蓝图、场景组合与续建说明"));
            }
            case "maicraft:build_machine", "maicraft:design_machine", "maicraft:modify_machine" -> {
                references.add(resource(MachineAssemblyResources.URI, "声明机器部件、安装和工件接口时查阅"));
                references.add(resource(KnowledgeLibrary.PROCESSES, "目标含生产意图时查阅工序参数与证据要求"));
                references.add(resource(KnowledgeLibrary.GUIDE, "引用思索结构时区分演示资源与真实运行条件"));
            }
            case "maicraft:inspect_machine", "maicraft:operate_machine", "maicraft:enchant", "maicraft:stonecut" ->
                    references.add(resource(KnowledgeLibrary.PROCESSES, "原生加工契约；具体设备的用法由现场物品资料补充"));
            case "maicraft:acquire_items", "maicraft:craft", "maicraft:cook" ->
                    references.add(resource(KnowledgeLibrary.RECIPES, "材料来源、配方条件与工艺规划的读取方式"));
            case "maicraft:connect_mechanical_power" ->
                    references.add(resource(MachineAssemblyResources.URI, "原生传动接口和安装关系；具体接点沿用当前观察"));
            default -> { /* 无专属资料的能力不附无关总目录，避免每次操作都引发遍历知识库。 */ }
        }
        return references;
    }

    public static JsonObject resource(String uri, String reason) {
        JsonObject reference = new JsonObject(); reference.addProperty("resource_uri", uri);
        reference.addProperty("reason", reason);
        JsonObject arguments = new JsonObject(); arguments.addProperty("view", "knowledge"); arguments.addProperty("resource_uri", uri);
        reference.add("read_arguments", arguments); return reference;
    }

    public static JsonObject recipe(ResourceLocation item, String reason) {
        // 关联精确物品只是提供来源查询，配方存在与否仍由该知识页的实际状态决定。
        JsonObject reference = resource(RecipeKnowledgeSource.uri(item), reason);
        reference.addProperty("item_id", item.toString()); return reference;
    }

    public static JsonObject ability(String ability, String reason) {
        JsonObject reference = new JsonObject(); reference.addProperty("ability", ability); reference.addProperty("reason", reason);
        JsonObject arguments = new JsonObject(); arguments.addProperty("view", "abilities"); arguments.addProperty("focus", ability);
        reference.add("read_arguments", arguments); return reference;
    }
}
