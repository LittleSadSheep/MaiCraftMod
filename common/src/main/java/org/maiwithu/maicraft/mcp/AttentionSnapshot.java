// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;

/** 把最近消息和任务的真实记录放在同一份回复里；即使“完成”消息已经被挤掉，也能读到任务最终结果。 */
final class AttentionSnapshot {
    static JsonObject read(IntentRuntime runtime, JsonObject args, boolean available) {
        // 先取消息，再补任务当前状态；后面的判断优先级更高，例如已结束比“有普通消息”更值得唤醒调用者。
        String rawTask = string(args, "task_id");
        UUID taskId = rawTask == null ? null : UUID.fromString(rawTask);
        JsonObject result = runtime.attention(args.get("after_cursor").getAsLong(),
                args.get("limit").getAsInt(), string(args, "stream_id"), taskId);
        result.addProperty("schema_version", 2);
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
            for (IntentTaskRecord record : retained.stream().limit(limit).toList()) tasks.add(project(record));
            result.add("tasks", tasks);
            result.addProperty("tasks_truncated", retained.size() > limit);
            result.addProperty("task_status_source", "runtime_task_record");
        }
        if (!available) {
            // 玩家不在可用世界时，不把上一世界的消息当成当前情况交出去。
            reason = "runtime_unavailable";
            // Old connection events cannot be mistaken for the currently controlled world.
            result.add("events", new JsonArray());
        }
        result.addProperty("wake_reason", reason);
        result.add("next_attention", continuation(result, rawTask));
        return result;
    }

    private static JsonObject project(IntentTaskRecord record) {
        // 平时只给简短进度；已经结束、暂停或需要回答时给完整信息，调用者才有依据做决定。
        if (record.getState().isTerminal() || record.terminalSnapshot() != null
                || record.decisionSnapshot() != null || record.pauseSnapshot() != null)
            return MaiCraftRuntimeFacade.taskSnapshot(record);
        JsonObject result = MaiCraftRuntimeFacade.taskSummary(record);
        if (record.activeExecution() != null) result.add("active_execution", record.activeExecution());
        return result;
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
