// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeReferences;
import org.maiwithu.maicraft.task.TaskResult;

/** 原生任务结束后，按已报告的缺口提供只读知识选项；不执行恢复、不改变许可或替模型挑选方案。 */
final class RecoveryKnowledge {
    private static final String ID = "inspect_related_knowledge";
    private RecoveryKnowledge() {}

    static TaskResult attach(Goal goal, TaskResult result) {
        if (goal == null || result == null || result.success()) return result;
        try { return describe(goal, result); }
        catch (RuntimeException | LinkageError unavailable) {
            // 提示只是附加知识，读取异常仍须交付原生失败、已完成效果和未知消费，不能中断任务结算。
            var data = new LinkedHashMap<String, Object>(result.data() == null ? Map.of() : result.data());
            data.put("recovery_knowledge_status", "lookup_unavailable");
            data.put("recovery_knowledge_error", unavailable.getClass().getSimpleName());
            return result.withData(data);
        }
    }

    private static TaskResult describe(Goal goal, TaskResult result) {
        Map<String, Object> data = new LinkedHashMap<>(result.data() == null ? Map.of() : result.data());
        JsonObject facts = new Gson().toJsonTree(data).getAsJsonObject();
        Map<String, JsonObject> resources = new LinkedHashMap<>(), abilities = new LinkedHashMap<>();
        for (var value : KnowledgeReferences.forAbility(goal.ability())) resource(resources, value.getAsJsonObject());
        ability(abilities, goal.ability(), "这次未完成步骤的参数、边界和原生操作契约");
        // 失败码自带的成因知识：塌落、工具等级等卡片解释"为什么会这样失败"，与能力资料互补。
        FailureType type = failureType(facts);
        if (type != null) for (String uri : type.knowledgeRefs())
            resource(resources, KnowledgeReferences.resource(uri, "失败成因相关的机制常识（" + type.name().toLowerCase(Locale.ROOT) + "）"));
        JsonObject handoff = object(facts.get("planning_handoff"));
        JsonObject blocked = object(facts.get("blocked_need"));
        if (blocked.isEmpty()) blocked = object(handoff.get("blocked_need"));
        // 缺料回执只关联明确缺少的物品，不遍历背包，也不把相似产物猜成另一条制造路线。
        for (JsonElement item : array(blocked.get("item_ids"))) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) continue;
            ResourceLocation id = ResourceLocation.tryParse(item.getAsString());
            if (id != null) resource(resources, KnowledgeReferences.recipe(id, "这次 blocked_need 中的材料来源与原生工艺"));
        }
        if (!blocked.isEmpty() || !handoff.isEmpty()) {
            resource(resources, KnowledgeReferences.resource(KnowledgeLibrary.RECIPES, "材料需求和配方条件的读取方式"));
            // 已有机器现场足够时直接复用；这里只列契约供选择，不提示重新执行同一观察。
            if (!facts.has("latest_snapshot") && !facts.has("machine"))
                ability(abilities, "maicraft:inspect_machine", "需要调查尚未观察的设备时，可先读取该能力契约");
            ability(abilities, "maicraft:operate_machine", "选定原生工艺后可读取实际操作参数");
            ability(abilities, "maicraft:sequence", "确需多个语义前置目标时读取组合方式");
        }
        uris(resources, handoff.get("knowledge_uris"));
        JsonArray previous = array(facts.get("recovery_options"));
        for (JsonElement value : previous) {
            JsonObject option = object(value); uris(resources, option.get("knowledge_uris"));
            String id = option.has("id") ? option.get("id").getAsString() : "";
            switch (id) {
                case "prepare_inventory_capacity", "inspect_inventory_storage" ->
                        ability(abilities, "maicraft:manage_container", "原回执提供了库存整理选项，可先查看其参数与取用范围");
                case "travel_dimension" -> ability(abilities, "maicraft:travel_dimension", "原回执指出维度条件，查看已注册跨维度能力");
                case "continue_mining_from_another_semantic_area", "continue_from_another_semantic_area" ->
                        ability(abilities, "maicraft:travel", "原回执提供了更换区域选项，查看语义移动参数");
                case "semantic_prerequisite" -> ability(abilities, "maicraft:sequence", "需要组合原回执中的语义前置目标时查阅");
                default -> { /* 未识别的原生建议完整保留，不从文字推导新的动作或授权。 */ }
            }
        }
        JsonObject evidence = new JsonObject();
        fields(evidence, facts, "effects", "completed_effects", "effects_observed", "native_effects", "confirmed_harvests",
                "inventory_before_by_item", "inventory_after_by_item", "actual_inventory");
        fields(evidence, facts, "remaining", "blocked_need", "missing", "required_final_count", "observed_final_count",
                "remaining_effects", "pending_effects", "not_performed", "skipped_steps", "skipped_effects", "construction_progress", "blueprint_diff");
        fields(evidence, facts, "uncertainty", "outcome_uncertain", "mechanical_retry_allowed", "pending_output", "unresolved_mutations");
        fields(evidence, facts, "observations", "failure_observation", "latest_snapshot", "machine", "operating_state", "ground_failure",
                "planning_handoff", "inventory_capacity", "issues", "preparation_failure", "wireless_stock_evidence");
        JsonObject option = new JsonObject(); option.addProperty("id", ID); option.addProperty("risk", "read_only");
        option.addProperty("summary", "先使用本次回执的实际效果、未完成部分和未知项；具体知识缺口可按以下入口读取，由模型选择下一步。");
        // 失败码级替代入口只列名字；"对账后可考虑"的措辞在这里统一加上，防止被读成换路重试的许可。
        if (type != null && type.alternatives() != null)
            option.addProperty("alternatives",
                    "After reconciling this receipt's effects, consider: " + type.alternatives() + ".");
        option.add("evidence_fields", evidence);
        option.addProperty("evidence_scope", "Field names refer to this result's original data. Missing fields are not proof of no effects; existing observations are reused.");
        JsonArray knowledge = new JsonArray(), contracts = new JsonArray(); resources.values().forEach(knowledge::add); abilities.values().forEach(contracts::add);
        option.add("knowledge", knowledge); option.add("ability_contracts", contracts);
        JsonArray options = new JsonArray(); options.add(option);
        for (JsonElement old : previous) {
            JsonObject oldObject = object(old);
            if (!oldObject.has("id") || !ID.equals(oldObject.get("id").getAsString())) options.add(old.deepCopy());
        }
        // 原来的恢复说明、风险与参数补丁原样保留；即使消费未知，新增入口也只能读取资料而不会重放动作。
        if (facts.has("recovery_options") && !facts.get("recovery_options").isJsonArray()) data.put("recovery_knowledge", option);
        else data.put("recovery_options", options);
        return result.withData(data);
    }

    private static void fields(JsonObject index, JsonObject facts, String group, String... keys) {
        JsonArray names = new JsonArray(); for (String key : keys) if (facts.has(key)) names.add(key);
        if (!names.isEmpty()) index.add(group, names);
    }
    private static void resource(Map<String, JsonObject> references, JsonObject reference) {
        references.putIfAbsent(reference.get("resource_uri").getAsString(), reference);
    }
    private static void ability(Map<String, JsonObject> references, String id, String reason) {
        if (IntentRuntime.KNOWN_ABILITIES.contains(id)) references.putIfAbsent(id, KnowledgeReferences.ability(id, reason));
    }
    private static void uris(Map<String, JsonObject> references, JsonElement value) {
        for (JsonElement element : array(value)) if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String uri = element.getAsString();
            if (uri.startsWith("maicraft://knowledge/")) resource(references, KnowledgeReferences.resource(uri, "原生恢复回执关联的知识资料"));
        }
    }
    private static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject(); }
    private static JsonArray array(JsonElement value) { return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray(); }
    /** failure_type 由任务基类统一写入（小写枚举名）；machine 系 ad-hoc 码不是枚举成员，不强行指路。 */
    private static FailureType failureType(JsonObject facts) {
        JsonElement value = facts.get("failure_type");
        if (value == null || !value.isJsonPrimitive()) return null;
        try { return FailureType.valueOf(value.getAsString().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException unrecognized) { return null; }
    }
}
