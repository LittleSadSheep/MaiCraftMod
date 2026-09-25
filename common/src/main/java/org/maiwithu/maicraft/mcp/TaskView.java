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
        // 成功证据只默认带最近一步；重复失败次数和历史入口足够提醒宿主，旧结果不随每次轮询重放。
        List<IntentTaskRecord.StepSnapshot> steps = record.stepResults();
        if (!steps.isEmpty()) {
            var step = steps.getLast(); JsonObject last = new JsonObject();
            last.addProperty("index", step.index()); last.addProperty("ability", step.ability());
            last.addProperty("skipped", step.skipped());
            last.add("result", result(step.result(), "/completed_steps/" + (steps.size() - 1) + "/result"));
            result.add("last_step", last); paths.add("/completed_steps");
        }
        if (!record.attempts().isEmpty()) {
            result.addProperty("attempt_count", record.attempts().size()); paths.add("/attempts");
        }
        if (terminal) {
            if (record.terminalSnapshot() != null) {
                result.add("terminal", result(record.terminalSnapshot().result(), "/terminal/result"));
                paths.add("/terminal");
            }
        } else {
            JsonObject execution = record.activeExecution();
            if (execution != null) {
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
        if (!compact.isEmpty()) result.add("data", JsonReadback.preview(compact, path + "/data", 2100));
        return result;
    }
}
