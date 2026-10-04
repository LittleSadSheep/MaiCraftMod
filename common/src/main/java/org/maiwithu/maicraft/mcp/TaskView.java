package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.maiwithu.maicraft.intent.IntentTaskRecord;

/** 任务查询默认呈现现在需要处理的事实；原目标和完整操作证据仍从任务单按路径读取。 */
final class TaskView {
    private TaskView() {}

    static JsonObject read(IntentTaskRecord record, JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull()) return status(record);
        JsonObject result = MaiCraftRuntimeFacade.taskSummary(record);
        result.add("detail", args.get("offset").getAsInt() == 0
                ? JsonReadback.complete(MaiCraftRuntimeFacade.taskSnapshot(record), args.get("path").getAsString())
                : JsonReadback.page(MaiCraftRuntimeFacade.taskSnapshot(record),
                        args.get("path").getAsString(), args.get("offset").getAsInt(), args.get("limit").getAsInt()));
        return result;
    }

    static JsonObject status(IntentTaskRecord record) {
        JsonObject result = MaiCraftRuntimeFacade.taskSummary(record);
        if (record.planId() != null) result.addProperty("plan_id", record.planId().toString());
        boolean terminal = record.getState().isTerminal() || record.terminalSnapshot() != null;
        List<IntentTaskRecord.StepSnapshot> steps = record.stepResults();
        // 生产任务被改成观察后，终态能力也取实际结算步骤，不能又回退到最初的生产能力名称。
        result.addProperty("ability", !terminal && record.stepIndex() < record.steps().size()
                ? record.steps().get(record.stepIndex()).ability()
                : !steps.isEmpty() ? steps.getLast().ability() : record.goal().ability());
        JsonArray paths = new JsonArray(); paths.add("/goal");
        if (!terminal && record.stepIndex() < record.steps().size()) paths.add("/current_goal");
        // 结束后默认带最近一步的结果并保留跳过标记；执行中只列历史入口，避免把上一阶段误当作当前进度。
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
                    result.add("active_execution", execution.deepCopy());
                paths.add("/active_execution");
            }
            if (record.pauseSnapshot() != null && record.decisionSnapshot() == null)
                result.addProperty("pause_reason", record.pauseSnapshot().reason());
            // 执行中被自卫带离工位的经历默认可见：在哪接管、最远多远、是否已走回，模型据此判断后续产出发生在哪里。
            JsonArray excursions = record.selfDefenseExcursions();
            if (!excursions.isEmpty()) result.add("self_defense_excursions", excursions);
            if (record.decisionSnapshot() != null) {
                result.add("decision", decision(record)); paths.add("/decision");
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

    private static JsonObject decision(IntentTaskRecord record) {
        IntentTaskRecord.DecisionSnapshot source = record.decisionSnapshot();
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
        result.add("context", context);
        // decision 附可照抄的应答样板；模型改 choice 即可提交，不必再翻工具 schema 试参数位置。
        JsonObject example = new JsonObject(); example.addProperty("action", "answer");
        example.addProperty("task_id", record.externalId().toString());
        JsonObject answer = new JsonObject(); answer.addProperty("decision_id", source.id().toString());
        if (!source.options().isEmpty()) answer.addProperty("choice", source.options().get(0).choice());
        example.add("answer", answer);
        result.add("answer_example", example);
        return result;
    }

    private static JsonObject result(JsonObject raw, String path) {
        JsonObject result = new JsonObject();
        for (String key : List.of("success", "message", "timed_out", "interrupted", "cancel_source"))
            if (raw.has(key)) result.add(key, raw.get(key).deepCopy());
        if (!raw.has("data") || !raw.get("data").isJsonObject()) return result;
        JsonObject data = raw.getAsJsonObject("data").deepCopy();
        // 现场变化的最新观察优先显示，不能被原设计、效果账本或其他大诊断折叠成只剩过期提示。
        if (data.has("latest_snapshot") && data.get("latest_snapshot").isJsonObject()) {
            result.add("latest_snapshot", MachineSnapshotView.present(data.getAsJsonObject("latest_snapshot"),
                    path + "/data/latest_snapshot", 3200, null));
            for (String key : List.of("failure_code", "previous_snapshot_id", "next_action"))
                if (data.has(key)) result.add(key, data.get(key).deepCopy());
            data.remove("latest_snapshot");
        }
        // 机器修改和检查结束时，首次回执就给出差异，不能让旧蓝图与组件目录把真正变化折叠掉。
        machineDifference(result, data, path + "/data");
        if (data.has("machine") && data.get("machine").isJsonObject())
            data.add("machine", MachineSnapshotView.present(data.getAsJsonObject("machine"), path + "/data/machine", 0, null));
        // 饥饿和健康门槛先于换配方；默认回执直接保留身体前置及当时的饱食度、生命证据。
        if (data.has("body_preparation_required")) result.add("body_preparation_required", data.remove("body_preparation_required"));
        if (data.has("food_preparation")) result.add("food_preparation", data.remove("food_preparation"));
        // 机器大蓝图被折叠时仍直接携带施工恢复事实，读取失败位置不必逐层穿过蓝图和批次归档。
        if (data.has("construction_progress")) result.add("construction_progress", data.remove("construction_progress"));
        // 自卫插曲直接放在结果顶层，不被施工或机器的大段证据埋住；它说明产出可能发生在离开原工位之后。
        if (data.has("self_defense_excursions")) result.add("self_defense_excursions", data.remove("self_defense_excursions"));
        // 大蓝图不能遮掉缺料的加工前置；超长交接仍给直接分页入口，不需要重做一次取材来找回原因。
        if (data.has("planning_handoff")) {
            result.addProperty("material_planning_required", true);
            result.add("planning_handoff", data.remove("planning_handoff"));
        }
        // 失败决策需要知道此前实际做了什么，默认交付全部完成效果；重复布局仍沿原有机器投影共享。
        if (data.has("completed_effects") && data.get("completed_effects").isJsonArray()
                && (!raw.has("success") || !raw.get("success").getAsBoolean())) {
            JsonArray effects = data.getAsJsonArray("completed_effects");
            for (int index = 0; index < effects.size(); index++) {
                if (!effects.get(index).isJsonObject()) continue;
                JsonObject effect = effects.get(index).getAsJsonObject();
                if (effect.has("confirmed_effect") && effect.get("confirmed_effect").isJsonObject()
                        && effect.getAsJsonObject("confirmed_effect").has("success"))
                    effect.add("confirmed_effect", result(effect.getAsJsonObject("confirmed_effect"),
                            path + "/data/completed_effects/" + index + "/confirmed_effect"));
            }
        }
        // 成功结果的历史账本仍可按路径展开；失败、取消和未完成部分保持默认可见，避免重复开工。
        for (String key : List.of("steps", "completed_effects")) {
            if (key.equals("completed_effects") && (!raw.has("success") || !raw.get("success").getAsBoolean())) continue;
            if (data.has(key) && data.get(key).isJsonArray()) {
                JsonArray ledger = data.getAsJsonArray(key);
                JsonObject ref = new JsonObject(); ref.addProperty("count", ledger.size());
                ref.addProperty("detail_path", path + "/data/" + key); data.add(key, ref);
            }
        }
        data.remove("task_id");
        // 缺料、身体维护、路径失败和未结产物都可能决定下一步，不能用通用字符阈值随机删去嵌套事实。
        if (!data.isEmpty()) result.add("data", data);
        return result;
    }

    private static void machineDifference(JsonObject result, JsonObject data, String path) {
        JsonObject owner = data;
        if (!owner.has("blueprint_diff") && data.has("machine") && data.get("machine").isJsonObject()) {
            owner = data.getAsJsonObject("machine"); path += "/machine";
        }
        if (!owner.has("blueprint_diff")) return;
        String diffPath = path + "/blueprint_diff";
        JsonElement stored = owner.get("blueprint_diff"), diff = stored;
        // 旧存档把差异写成字符串，展示时也恢复成结构；原记录与详情路径保持可读，不改写历史回执。
        if (stored.isJsonPrimitive() && stored.getAsJsonPrimitive().isString()) {
            try { diff = JsonParser.parseString(stored.getAsString()); }
            catch (RuntimeException invalid) { /* 非 JSON 的历史说明保留为原文。 */ }
        }
        result.add("blueprint_diff", diff.deepCopy());
        result.addProperty("blueprint_diff_path", diffPath);
        // 大机器的差异明细可直接读数组，不必逐层翻过机器的整份勘测；旧文本仍从原路径连续读取。
        if (stored.isJsonObject() && stored.getAsJsonObject().has("differences"))
            result.addProperty("blueprint_differences_path", diffPath + "/differences");
        owner.remove("blueprint_diff");
    }

}
