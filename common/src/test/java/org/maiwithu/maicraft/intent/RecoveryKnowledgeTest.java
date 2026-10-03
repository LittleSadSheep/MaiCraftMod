// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeReferences;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 恢复知识必须随真实失败事实进入归档与通知，不能改写消费未知、原生建议或成功动作。 */
public final class RecoveryKnowledgeTest {
    public static void main(String[] args) {
        var goal = new Goal("maicraft:acquire_items", "取得缺少的材料", null, "{}", "{}", List.of(), List.of());
        var source = TaskResult.cancelled("尚有原生消费未结清", "operator").withData(Map.of(
                "blocked_need", Map.of("item_ids", List.of("minecraft:iron_ingot"), "missing", 2),
                "completed_effects", List.of(Map.of("item_id", "minecraft:iron_ingot", "count", 1)),
                "outcome_uncertain", true, "mechanical_retry_allowed", false,
                "latest_snapshot", Map.of("snapshot_id", "already-returned", "structure_complete", false),
                "recovery_options", List.of(Map.of("id", "prepare_inventory_capacity", "risk", "existing_authorization_required"))));
        var result = RecoveryKnowledge.attach(goal, source);
        var json = JsonParser.parseString(result.toJson()).getAsJsonObject(); var data = json.getAsJsonObject("data");
        var options = data.getAsJsonArray("recovery_options"); var knowledge = options.get(0).getAsJsonObject();
        check(!result.success() && result.interrupted() && result.cancelSource().equals("operator")
                && data.get("outcome_uncertain").getAsBoolean() && !data.get("mechanical_retry_allowed").getAsBoolean(),
                "knowledge keeps settlement and unknown-consumption markers intact");
        check(options.get(1).equals(JsonParser.parseString(source.toJson()).getAsJsonObject().getAsJsonObject("data")
                .getAsJsonArray("recovery_options").get(0)), "existing native recovery option remains unchanged");
        check(knowledge.getAsJsonArray("knowledge").asList().stream().anyMatch(value -> value.getAsString()
                .endsWith("minecraft/iron_ingot")), "exact blocked material has a direct recipe entry");
        for (String group : List.of("knowledge", "ability_contracts")) for (var hint : knowledge.getAsJsonArray(group)) {
            // 使用同一份读取模板还原每个入口；压缩包装之后仍必须能调用原有知识接口。
            JsonObject read = knowledge.getAsJsonObject("read_templates").getAsJsonObject(group).deepCopy();
            read.addProperty(group.equals("knowledge") ? "resource_uri" : "focus", hint.getAsString());
            if (read.get("view").getAsString().equals("knowledge"))
                check(KnowledgeLibrary.perceptionRequest(read).get("action").getAsString().equals("read"), "knowledge reference selects a document");
            else check(IntentRuntime.KNOWN_ABILITIES.contains(read.get("focus").getAsString()), "ability reference selects an existing contract");
            check(!read.has("goal") && !read.get("view").getAsString().equals("surroundings"), "recovery hints only read knowledge, never repeat world actions or surveys");
            check(!read.has("focus") || !read.get("focus").getAsString().equals("maicraft:inspect_machine"), "returned snapshot suppresses unnecessary inspection advice");
        }
        check(knowledge.getAsJsonObject("evidence_fields").getAsJsonArray("effects").toString().contains("completed_effects"),
                "confirmed effects and remaining need are located in the same receipt");
        check(RecoveryKnowledge.attach(goal, result).toJson().equals(result.toJson()), "repeated presentation does not accumulate advice");
        var success = TaskResult.ok("原生动作完成，结构差异由模型判断", Map.of("blueprint_diff", Map.of("structure_matches_blueprint", false)));
        check(RecoveryKnowledge.attach(goal, success) == success, "a structural mismatch never turns a successful action into recovery failure");
        // 失败提示跟随任务快照和 Attention 通知；持久化的是这次回执本身，不依赖后来重新查世界。
        var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        record.terminal(TaskState.FAILED, result, 41L);
        check(record.terminalSnapshot().result().getAsJsonObject("data").get("recovery_options").equals(options), "frozen task snapshot retains direct references");
        var runtime = IntentRuntime.get(); long cursor = runtime.attentionCheckpoint().get("cursor").getAsLong();
        runtime.terminal(record, TaskState.FAILED, result);
        var delivered = runtime.attention(cursor, 20).getAsJsonArray("events").get(0).getAsJsonObject().getAsJsonObject("data");
        check(delivered.getAsJsonObject("data").get("recovery_options").equals(options), "default failure notification preserves full structured recovery hints");
        wideMaterialKnowledge(goal);
        castingResources();
        System.out.println("RecoveryKnowledgeTest: passed");
    }

    private static void wideMaterialKnowledge(Goal goal) {
        // 大标签的全部材料都保留精确入口；用旧包装实测字节变化，防止把固定截断误当成压缩。
        var items = new ArrayList<String>(); JsonArray legacy = new JsonArray();
        for (int i = 0; i < 128; i++) {
            var id = ResourceLocation.parse("test:material_" + i); items.add(id.toString());
            legacy.add(KnowledgeReferences.recipe(id, "这次 blocked_need 中的材料来源与原生工艺"));
        }
        var facts = Map.<String, Object>of("blocked_need", Map.of("item_ids", items, "missing", 4),
                "completed_effects", List.of("已有实物仍在背包"), "outcome_uncertain", true);
        var result = RecoveryKnowledge.attach(goal, TaskResult.cancelled("材料缺口", "operator").withData(facts));
        var data = JsonParser.parseString(result.toJson()).getAsJsonObject().getAsJsonObject("data");
        var knowledge = data.getAsJsonArray("recovery_options").get(0).getAsJsonObject().getAsJsonArray("knowledge");
        check(knowledge.size() == items.size() + 1, "所有材料入口和配方总入口完整保留");
        check(data.getAsJsonObject("blocked_need").getAsJsonArray("item_ids").size() == items.size()
                && data.get("outcome_uncertain").getAsBoolean() && data.has("completed_effects"), "材料事实、效果与未知项不能为缩短回执而丢失");
        int before = legacy.toString().getBytes(StandardCharsets.UTF_8).length;
        int after = knowledge.toString().getBytes(StandardCharsets.UTF_8).length;
        check(after * 2 < before, "仅去重知识包装就应显著减小体积");
        System.out.println("RecoveryKnowledgeTest: 128 material links " + before + " -> " + after + " UTF-8 bytes");
    }
    private static void castingResources() {
        // 地狱门缺水时，实际准备事实和跑图入口一起交给模型；这些入口不会替模型自动选方向或改写许可。
        var goal = new Goal("maicraft:prepare_portal", "浇筑地狱门", null, "{\"portal_method\":\"lava_cast\"}", "{}", List.of(), List.of());
        var facts = Map.<String, Object>of("construction_phase_started", false,
                "resource_preparation", Map.of("initial_water_prepared", false, "water_buckets", 0),
                "recovery_options", List.of(Map.of("id", "locate_casting_resources", "missing_resource", "water")));
        var result = RecoveryKnowledge.attach(goal, TaskResult.fail("find_water_source_not_observed", facts));
        var data = JsonParser.parseString(result.toJson()).getAsJsonObject().getAsJsonObject("data");
        var hint = data.getAsJsonArray("recovery_options").get(0).getAsJsonObject();
        var abilities = hint.getAsJsonArray("ability_contracts").toString();
        check(abilities.contains("maicraft:explore") && abilities.contains("maicraft:travel")
                && abilities.contains("maicraft:interact"), "resource absence names physical exploration and collection abilities");
        check(!data.get("construction_phase_started").getAsBoolean()
                && data.getAsJsonObject("resource_preparation").get("water_buckets").getAsInt() == 0,
                "preparation facts survive recovery wrapping");
        check(hint.getAsJsonObject("evidence_fields").getAsJsonArray("observations").toString().contains("resource_preparation"),
                "resource preparation is directly indexed for the model");
        check(RecoveryKnowledge.attach(goal, result).toJson().equals(result.toJson()), "repeated presentation does not duplicate discovery advice");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
