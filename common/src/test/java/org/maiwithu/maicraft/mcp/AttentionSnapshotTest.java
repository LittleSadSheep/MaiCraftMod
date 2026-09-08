// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

public final class AttentionSnapshotTest {
    public static void main(String[] args) throws Exception {
        var constructor = IntentRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        IntentRuntime runtime = constructor.newInstance();
        var field = IntentRuntime.class.getDeclaredField("tasks"); field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, IntentTaskRecord> tasks = (Map<UUID, IntentTaskRecord>) field.get(runtime);
        Goal goal = new Goal("maicraft:travel", "Reach the elevator", null, "{}", "{}", List.of(), List.of());
        var task = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        tasks.put(task.externalId(), task); task.setState(TaskState.RUNNING);
        JsonObject request = request(runtime, task);
        check(reason(runtime, request).equals("idle"), "running task with no events waits");
        var pending = new IntentTaskRecord.DecisionSnapshot(UUID.randomUUID(), "Choose a floor",
                List.of(new IntentTaskRecord.DecisionOption("retry", "Choose a floor")), "{\"floors\":[\"G\",\"R\"]}");
        invoke(task, "requestDecision", new Class<?>[]{IntentTaskRecord.DecisionSnapshot.class, long.class}, pending, 10L);
        JsonObject result = AttentionSnapshot.read(runtime, request, true);
        check(result.get("wake_reason").getAsString().equals("decision_required")
                        && result.getAsJsonObject("task").getAsJsonObject("decision").get("decision_id")
                        .getAsString().equals(pending.id().toString()), "decision is recovered from the record without a retained event");
        check(task.answer(pending.id(), "retry", new JsonObject()), "answer exact decision");
        check(reason(runtime, request).equals("idle"), "answered decision no longer wakes wait");
        task.pause(11, "player_attention");
        check(reason(runtime, request).equals("task_paused"), "pause never waits indefinitely");
        task.resume();
        for (TaskState state : List.of(TaskState.SUCCESS, TaskState.FAILED, TaskState.TIMEOUT, TaskState.CANCELLED)) {
            var finished = new IntentTaskRecord(UUID.randomUUID(), null, goal);
            tasks.put(finished.externalId(), finished);
            JsonObject before = request(runtime, finished);
            TaskResult receipt = new TaskResult(state == TaskState.SUCCESS, "final receipt", state == TaskState.TIMEOUT,
                    state == TaskState.CANCELLED, Map.of("preview_id", "retained-result", "verified", true));
            invoke(finished, "terminal", new Class<?>[]{TaskState.class, TaskResult.class, long.class}, state, receipt, 12L);
            for (int i = 0; i < 300; i++) runtime.gameEvent("game.message_received", "noise", null);
            result = AttentionSnapshot.read(runtime, before, true);
            check(result.get("history_lost").getAsBoolean() && result.get("wake_reason").getAsString().equals("task_terminal"),
                    "evicted event does not hide authoritative terminal state " + state);
            check(result.getAsJsonObject("task").equals(MaiCraftRuntimeFacade.taskSnapshot(finished)),
                    "attention and task/get share the full authoritative terminal receipt");
            JsonObject again = normalize(result.getAsJsonObject("next_attention"));
            check(reason(runtime, again).equals("task_terminal"), "late wait after completion returns immediately");
        }
        request = request(runtime, task);
        result = AttentionSnapshot.read(runtime, request, false);
        check(result.get("wake_reason").getAsString().equals("runtime_unavailable") && !result.has("task"),
                "detached body cannot advertise stale task authority");
        request.addProperty("task_id", UUID.randomUUID().toString());
        check(reason(runtime, request).equals("task_unavailable"), "missing task explicitly ends waiting");
        request.remove("task_id");
        result = AttentionSnapshot.read(runtime, request, true);
        check(result.getAsJsonArray("tasks").size() == tasks.size(), "resource readers recover task snapshots");
        request.addProperty("limit", 1);
        check(AttentionSnapshot.read(runtime, request, true).get("tasks_truncated").getAsBoolean(), "bounded task inventory reports truncation");
        validateInputs();
        System.out.println("AttentionSnapshotTest: passed");
    }

    private static JsonObject request(IntentRuntime runtime, IntentTaskRecord task) {
        return normalize(AttentionSnapshot.continuation(runtime.attentionCheckpoint(), task.externalId().toString()));
    }

    private static JsonObject normalize(JsonObject request) {
        return PublicToolCatalog.validateAndNormalize("perceive", request);
    }

    private static String reason(IntentRuntime runtime, JsonObject request) {
        return AttentionSnapshot.read(runtime, request, true).get("wake_reason").getAsString();
    }

    private static void invoke(IntentTaskRecord task, String name, Class<?>[] signature, Object... args) throws Exception {
        var method = IntentTaskRecord.class.getDeclaredMethod(name, signature);
        method.setAccessible(true); method.invoke(task, args);
    }

    private static void validateInputs() {
        JsonObject request = new JsonObject(); request.addProperty("view", "attention");
        request.addProperty("after_cursor", 4_000_000_000L);
        check(normalize(request).get("after_cursor").getAsLong() == 4_000_000_000L, "cursor is not narrowed to int");
        for (String invalid : List.of("-1", "1.5", "9007199254740992", "1e40", "\"12\"")) {
            request.add("after_cursor", com.google.gson.JsonParser.parseString(invalid));
            rejected(request);
        }
        request.addProperty("after_cursor", 0);
        request.addProperty("stream_id", UUID.randomUUID().toString());
        request.addProperty("view", "tasks"); rejected(request);
        request.remove("stream_id"); request.addProperty("task_id", UUID.randomUUID().toString());
        request.addProperty("view", "situation"); rejected(request);
    }

    private static void rejected(JsonObject request) {
        try { normalize(request); throw new AssertionError("invalid attention contract accepted: " + request); }
        catch (IllegalArgumentException expected) { }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
