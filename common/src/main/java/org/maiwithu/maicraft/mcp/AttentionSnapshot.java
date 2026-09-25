// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.List;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;

/** 事件只提示发生了什么，任务记录给当前处理依据；丢失旧消息后仍能凭任务编号找回证据。 */
final class AttentionSnapshot {
    static JsonObject read(IntentRuntime runtime, JsonObject args, boolean available) {
        // 先取消息，再补任务当前状态；后面的判断优先级更高，例如已结束比“有普通消息”更值得唤醒调用者。
        String rawTask = string(args, "task_id");
        UUID taskId = rawTask == null ? null : UUID.fromString(rawTask);
        JsonObject result = runtime.attention(args.get("after_cursor").getAsLong(),
                args.get("limit").getAsInt(), string(args, "stream_id"), taskId);
        compactTaskEvents(result.getAsJsonArray("events"));
        result.addProperty("schema_version", 3);
        result.addProperty("runtime_available", available);
        String reason = "idle";
        if (result.getAsJsonArray("events").size() > 0) reason = "events";
        if (result.get("resync_required").getAsBoolean()) reason = "resync_required";
        if (taskId != null && available) {
            // 指定一个任务时直接读它的任务单，不靠历史消息猜它是否还在运行。
            IntentTaskRecord record = runtime.task(taskId);
            result.addProperty("task_id", rawTask);
            result.addProperty("task_found", record != null);
            if (record == null) reason = "task_unavailable";
            else {
                JsonObject task = project(record);
                result.add("task", task);
                result.addProperty("task_status_source", "runtime_task_record");
                if (record.pauseSnapshot() != null) reason = "task_paused";
                if (record.decisionSnapshot() != null) reason = "decision_required";
                if (record.getState().isTerminal() || record.terminalSnapshot() != null) reason = "task_terminal";
            }
        } else if (available) {
            // 没指定任务时附上最近几项任务，方便刚连上的调用者重新了解当前进度。
            JsonArray tasks = new JsonArray();
            int limit = args.get("limit").getAsInt();
            var retained = runtime.tasks(256);
            // 重新连接时先给任务索引；只有指定任务才附当前决策与结果，避免一口气重放多个旧任务。
            for (IntentTaskRecord record : retained.stream().limit(limit).toList()) tasks.add(MaiCraftRuntimeFacade.taskSummary(record));
            result.add("tasks", tasks);
            result.addProperty("tasks_truncated", retained.size() > limit);
            result.addProperty("task_status_source", "runtime_task_record");
        }
        if (!available) {
            // 玩家不在可用世界时，不把上一世界的消息当成当前情况交出去。
            reason = "runtime_unavailable";
            // 旧连接事件不能被误认为当前正在控制的世界。
            result.add("events", new JsonArray());
        }
        result.addProperty("wake_reason", reason);
        result.add("next_attention", continuation(result, rawTask));
        return result;
    }

    private static JsonObject project(IntentTaskRecord record) {
        // 等待时始终保留状态；需处理问题或结束时补当前证据摘要，历史通过 task get 的 detail_path 找回。
        if (record.getState().isTerminal() || record.terminalSnapshot() != null
                || record.decisionSnapshot() != null || record.pauseSnapshot() != null)
            return TaskView.status(record);
        JsonObject result = MaiCraftRuntimeFacade.taskSummary(record);
        if (record.activeExecution() != null) result.add("active_execution",
                JsonReadback.preview(record.activeExecution(), "/active_execution", 1800));
        return result;
    }

    private static void compactTaskEvents(JsonArray events) {
        for (var value : events) {
            JsonObject event = value.getAsJsonObject();
            if (!event.has("task_id") || !event.has("data") || !event.get("data").isJsonObject()) continue;
            if (!List.of("decision", "completed", "failed", "cancelled", "plan_changed")
                    .contains(event.get("type").getAsString())) continue;
            JsonObject data = event.getAsJsonObject("data");
            // 决策、目标修订和终态的完整内容在任务单中；通知保留身份与成败，游标和事件顺序照常推进。
            JsonObject brief = new JsonObject();
            for (String key : List.of("decision_id", "mode", "success", "timed_out", "interrupted",
                    "completed_step_count", "step_count", "skipped", "reason"))
                if (data.has(key)) brief.add(key, data.get(key));
            // 未指定任务的订阅者也必须看到未决消费，随后按 task_id 查当前证据再决定是否继续。
            if (data.has("data") && data.get("data").isJsonObject()) {
                JsonObject facts = data.getAsJsonObject("data");
                for (String key : List.of("outcome_uncertain", "mechanical_retry_allowed", "effects_started"))
                    if (facts.has(key)) brief.add(key, facts.get(key));
            }
            event.add("data", brief);
        }
    }

    static JsonObject continuation(JsonObject checkpoint, String taskId) {
        // 帮调用者拼好下一次等待参数；还有未读页就马上读下一页，读完才开始等新消息。
        JsonObject args = new JsonObject();
        args.addProperty("view", "attention");
        if (taskId != null) args.addProperty("task_id", taskId);
        args.add("stream_id", checkpoint.get("stream_id").deepCopy());
        args.add("after_cursor", checkpoint.get("cursor").deepCopy());
        args.addProperty("wait_ms", checkpoint.has("has_more") && checkpoint.get("has_more").getAsBoolean() ? 0 : 30_000);
        return args;
    }

    private static String string(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : null;
    }
}
