package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import org.maiwithu.maicraft.intent.IntentTaskRecord;

/** 任务查询默认呈现现在需要处理的事实；原目标和完整操作证据仍从任务单按路径读取。 */
final class TaskView {
    private TaskView() {}

    static JsonObject read(IntentTaskRecord record, JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull()) return status(record);
        JsonObject result = MaiCraftRuntimeFacade.taskSummary(record);
        result.add("detail", JsonReadback.page(MaiCraftRuntimeFacade.taskSnapshot(record),
                args.get("path").getAsString(), args.get("offset").getAsInt(), args.get("limit").getAsInt()));
        return result;
    }

    static JsonObject status(IntentTaskRecord record) {
        JsonObject result = MaiCraftRuntimeFacade.taskSummary(record);
        if (record.planId() != null) result.addProperty("plan_id", record.planId().toString());
        result.addProperty("ability", record.stepIndex() < record.steps().size()
                ? record.steps().get(record.stepIndex()).ability() : record.goal().ability());
        JsonArray paths = new JsonArray(); paths.add("/goal");
        boolean terminal = record.getState().isTerminal() || record.terminalSnapshot() != null;
        if (!terminal && record.stepIndex() < record.steps().size()) paths.add("/current_goal");
        // 结束后默认带最近一步的结果并保留跳过标记；执行中只列历史入口，避免把上一阶段误当作当前进度。
        List<IntentTaskRecord.StepSnapshot> steps = record.stepResults();
        if (!steps.isEmpty()) paths.add("/completed_steps");
        if (terminal && !steps.isEmpty()) {
            var step = steps.getLast(); JsonObject last = new JsonObject();
            last.addProperty("index", step.index()); last.addProperty("ability", step.ability());
            last.addProperty("skipped", step.skipped());
            last.add("result", result(step.result(), "/completed_steps/" + (steps.size() - 1) + "/result"));
            result.add("last_step", last);
        }
        if (!record.attempts().isEmpty()) {
            result.addProperty("retained_attempt_count", record.attempts().size()); paths.add("/attempts");
        }
        if (terminal) {
            if (record.terminalSnapshot() != null) {
                JsonObject finished = new JsonObject();
                finished.addProperty("game_time", record.terminalSnapshot().gameTime());
                finished.add("result", result(record.terminalSnapshot().result(), "/terminal/result"));
                result.add("terminal", finished);
                paths.add("/terminal");
            }
        } else {
            JsonObject execution = record.activeExecution();
            if (execution != null) {
                // 待答问题已接替执行进度；旧诊断仍可按路径读取，但不与暂停原因争抢注意力。
                if (record.decisionSnapshot() == null)
                    result.add("active_execution", JsonReadback.preview(execution, "/active_execution", 1800));
                paths.add("/active_execution");
            }
            if (record.pauseSnapshot() != null && record.decisionSnapshot() == null)
                result.addProperty("pause_reason", record.pauseSnapshot().reason());
            if (record.decisionSnapshot() != null) {
                result.add("decision", decision(record.decisionSnapshot())); paths.add("/decision");
            }
        }
        result.add("detail_paths", paths);
        return result;
    }

    static JsonObject list(List<IntentTaskRecord> records, int offset, int limit) {
        if (offset > records.size()) throw new IllegalArgumentException("Task offset exceeds retained history");
        JsonArray tasks = new JsonArray(); int end = Math.min(records.size(), offset + limit);
        for (int i = offset; i < end; i++) {
            IntentTaskRecord record = records.get(i);
            JsonObject row = MaiCraftRuntimeFacade.taskSummary(record);
            if (record.decisionSnapshot() != null) row.addProperty("decision_id", record.decisionSnapshot().id().toString());
            tasks.add(row);
        }
        JsonObject result = new JsonObject(); result.add("tasks", tasks); result.addProperty("total", records.size());
        if (end < records.size()) result.addProperty("next_offset", end);
        return result;
    }

    private static JsonObject decision(IntentTaskRecord.DecisionSnapshot source) {
        JsonObject result = new JsonObject(); result.addProperty("decision_id", source.id().toString());
        result.addProperty("question", source.question()); JsonArray options = new JsonArray();
        source.options().forEach(option -> {
            JsonObject row = new JsonObject(); row.addProperty("choice", option.choice());
            row.addProperty("description", option.description()); options.add(row);
        });
        result.add("options", options);
        JsonObject context = source.context();
        // 问题保留当前失败与重试限制；原始大蓝图和通用规则按需找回，避免同一目标出现三份。
        for (String key : List.of("goal", "semantic_goal_rule", "ability", "outcome")) context.remove(key);
        if (context.has("failure") && context.get("failure").isJsonObject())
            context.add("failure", result(context.getAsJsonObject("failure"), "/decision/context/failure"));
        result.add("context", JsonReadback.preview(context, "/decision/context", 3400));
        return result;
    }

    private static JsonObject result(JsonObject raw, String path) {
        JsonObject result = new JsonObject();
        for (String key : List.of("success", "message", "timed_out", "interrupted"))
            if (raw.has(key)) result.add(key, JsonReadback.preview(raw.get(key), path + "/" + key, 600));
        if (!raw.has("data") || !raw.get("data").isJsonObject()) return result;
        JsonObject data = raw.getAsJsonObject("data").deepCopy();
        // 机器大蓝图被折叠时仍直接携带施工恢复事实，读取失败位置不必逐层穿过蓝图和批次归档。
        if (data.has("construction_progress")) result.add("construction_progress", data.get("construction_progress").deepCopy());
        // 大蓝图不能遮掉缺料的加工前置；超长交接仍给直接分页入口，不需要重做一次取材来找回原因。
        if (data.has("planning_handoff")) {
            result.addProperty("material_planning_required", true);
            result.add("planning_handoff", materialPlanning(data.get("planning_handoff"), path + "/data/planning_handoff"));
        }
        // 汇总账本不复印每个已完成步骤的全部结果；保留数量和定位路径，供恢复时核对实际发生的效果。
        for (String key : List.of("steps", "completed_effects", "remaining_effects", "skipped_steps")) {
            if (data.has(key) && data.get(key).isJsonArray()) {
                JsonArray ledger = data.getAsJsonArray(key);
                JsonObject ref = new JsonObject(); ref.addProperty("count", ledger.size());
                ref.addProperty("detail_path", path + "/data/" + key); data.add(key, ref);
            }
        }
        data.remove("task_id");
        JsonObject compact = new JsonObject();
        for (var entry : data.entrySet()) {
            JsonElement value = JsonReadback.preview(entry.getValue(), JsonReadback.childPath(path + "/data", entry.getKey()), 900);
            compact.add(entry.getKey(), value);
        }
        if (!compact.isEmpty()) {
            JsonElement displayed = JsonReadback.preview(compact, path + "/data", 2100);
            result.add("data", displayed);
            // 产物已生成但尚未收回时，即使大诊断需要展开，也要保留待收取证据的直接入口。
            if (displayed.isJsonObject() && displayed.getAsJsonObject().has("detail_path") && data.has("pending_output"))
                result.add("pending_output", JsonReadback.preview(data.get("pending_output"), path + "/data/pending_output", 600));
        }
        return result;
    }

    private static JsonElement materialPlanning(JsonElement full, String path) {
        if (!full.isJsonObject() || JsonReadback.fits(full, 1400)) return JsonReadback.preview(full, path, 1400);
        // 历史配方链和通用边界说明不能挤掉眼前缺哪种原料、缺多少；完整交接仍按原路径读取。
        JsonObject source = full.getAsJsonObject(), result = new JsonObject();
        result.addProperty("summary_only", true); result.addProperty("detail_path", path);
        if (source.has("kind")) result.add("kind", JsonReadback.preview(source.get("kind"), path + "/kind", 120));
        if (source.has("blocked_need") && source.get("blocked_need").isJsonObject()) {
            var blocked = source.getAsJsonObject("blocked_need"); var need = new JsonObject();
            for (String key : List.of("item_ids", "required_final_count", "observed_final_count", "missing"))
                if (blocked.has(key)) need.add(key, JsonReadback.preview(blocked.get(key), path + "/blocked_need/" + key, 500));
            result.add("blocked_need", need);
        }
        for (String key : List.of("knowledge_uris", "final_inventory_goal"))
            if (source.has(key)) result.add(key, JsonReadback.preview(source.get(key), path + "/" + key, 500));
        return result;
    }
}
