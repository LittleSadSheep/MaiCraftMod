// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;

/** Client-thread projection of event history and authoritative task records from the same runtime. */
final class AttentionSnapshot {
    static JsonObject read(IntentRuntime runtime, JsonObject args, boolean available) {
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
            // Resource-only clients can recover current tasks even after event history was evicted.
            JsonArray tasks = new JsonArray();
            int limit = args.get("limit").getAsInt();
            var retained = runtime.tasks(256);
            for (IntentTaskRecord record : retained.stream().limit(limit).toList()) tasks.add(project(record));
            result.add("tasks", tasks);
            result.addProperty("tasks_truncated", retained.size() > limit);
            result.addProperty("task_status_source", "runtime_task_record");
        }
        if (!available) {
            reason = "runtime_unavailable";
            // Old connection events cannot be mistaken for the currently controlled world.
            result.add("events", new JsonArray());
        }
        result.addProperty("wake_reason", reason);
        result.add("next_attention", continuation(result, rawTask));
        return result;
    }

    private static JsonObject project(IntentTaskRecord record) {
        if (record.getState().isTerminal() || record.terminalSnapshot() != null
                || record.decisionSnapshot() != null || record.pauseSnapshot() != null)
            return MaiCraftRuntimeFacade.taskSnapshot(record);
        JsonObject result = MaiCraftRuntimeFacade.taskSummary(record);
        if (record.activeExecution() != null) result.add("active_execution", record.activeExecution());
        return result;
    }

    static JsonObject continuation(JsonObject checkpoint, String taskId) {
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
