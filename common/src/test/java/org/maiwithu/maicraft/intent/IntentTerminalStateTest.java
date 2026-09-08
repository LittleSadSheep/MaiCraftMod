package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Cancelling a decision-waiting task must expose one terminal state through get, list and restore. */
public final class IntentTerminalStateTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Goal goal = new Goal("maicraft:travel", "Reach the observed destination", null, "{}", "{}", List.of(), List.of());
        var decision = new IntentTaskRecord.DecisionSnapshot(UUID.randomUUID(), "Choose recovery",
                List.of(new IntentTaskRecord.DecisionOption("retry", "Retry the task")), "{}");
        Method snapshot = projection("taskSnapshot"), summary = projection("taskSummary");
        for (TaskState state : List.of(TaskState.CANCELLED, TaskState.FAILED, TaskState.TIMEOUT, TaskState.SUCCESS)) {
            var task = new IntentTaskRecord(UUID.randomUUID(), null, goal);
            task.setState(TaskState.RUNNING);
            task.addAttempt(new IntentTaskRecord.AttemptSnapshot(0, goal, TaskState.FAILED,
                    "earlier attempt", TaskResult.fail("earlier attempt").toJson(), 5));
            task.requestDecision(decision, 10);
            JsonObject waiting = read(snapshot, task);
            check(waiting.get("state").getAsString().equals("waiting_for_decision")
                            && waiting.has("decision") && waiting.has("pause"), "active decisions retain their public contract");
            // TaskSlot marks the record terminal before asking the task to build its final receipt.
            task.setState(state);
            assertTerminal(read(snapshot, task), state);
            check(!task.answer(decision.id(), "retry", new JsonObject()), "a stale decision cannot revive a terminal task");
            task.bindDirty(() -> check(task.getState() == state && task.pauseSnapshot() == null
                            && task.decisionSnapshot() == null && task.pendingAnswerSnapshot() == null,
                    "persistence observes the terminal state and cleared controls atomically"));
            TaskResult result = switch (state) {
                case SUCCESS -> TaskResult.ok("finished");
                case CANCELLED -> TaskResult.cancelled("cancelled");
                case TIMEOUT -> TaskResult.timeout("timed out");
                default -> TaskResult.fail("failed");
            };
            task.terminal(state, result, 20);
            JsonObject terminal = read(snapshot, task);
            assertTerminal(terminal, state);
            check(terminal.getAsJsonObject("terminal").get("state").getAsString().equals(state.name().toLowerCase())
                            && terminal.getAsJsonArray("attempts").size() == 1,
                    "the final receipt remains consistent without losing historical attempts");
            check(read(summary, task).get("state").equals(terminal.get("state")), "get and list report the same terminal state");
            check(!task.paused() && !task.resume() && !task.pause(21, "paused_by_mcp"), "terminal tasks expose no resumable pause");
        }
        var answered = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        answered.requestDecision(decision, 1);
        check(answered.answer(decision.id(), "retry", new JsonObject()), "live recovery answer is accepted");
        answered.terminal(TaskState.CANCELLED, TaskResult.cancelled("cancelled before retry"), 2);
        check(answered.pendingAnswerSnapshot() == null && answered.takeAnswer() == null,
                "cancellation also discards an accepted but unconsumed recovery answer");

        var oldTerminal = new IntentTaskRecord.TerminalSnapshot(TaskState.CANCELLED, TaskResult.cancelled("cancelled").toJson(), 20);
        var restored = IntentTaskRecord.restored(UUID.randomUUID(), null, goal, "test-world", List.of(goal), 0,
                List.of(), Map.of(), Map.of(), List.of(), decision,
                new IntentTaskRecord.DecisionAnswer(decision.id(), "retry", "{}"), oldTerminal, 30);
        assertTerminal(read(snapshot, restored), TaskState.CANCELLED);
        check(restored.decisionSnapshot() == null && restored.pendingAnswerSnapshot() == null && !restored.paused(),
                "legacy terminal checkpoints discard obsolete decision controls during restoration");
        var persisted = IntentStateCodec.encode("test-world", List.of(), List.of(restored), Map.of(), List.of())
                .getAsJsonArray("tasks").get(0).getAsJsonObject();
        check(!persisted.has("decision") && !persisted.has("pending_answer"), "resaving a legacy terminal task cannot preserve its stale question");
        var activeRestore = IntentTaskRecord.restored(UUID.randomUUID(), null, goal, "test-world", List.of(goal), 0,
                List.of(), Map.of(), Map.of(), List.of(), decision, null, null, 30);
        check(read(snapshot, activeRestore).get("state").getAsString().equals("waiting_for_decision") && activeRestore.paused(),
                "restoration must preserve a genuinely active decision");
        var replacement = new Goal("maicraft:travel", "Reach the newly observed lower floor", null,
                "{\"destination\":{\"x\":-71,\"y\":107,\"z\":1}}", "{}", List.of(), List.of());
        var changed = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        changed.setState(TaskState.RUNNING);
        changed.addAttempt(new IntentTaskRecord.AttemptSnapshot(0, goal, TaskState.FAILED,
                "old destination had no path", TaskResult.fail("old destination had no path").toJson(), 10));
        changed.replaceCurrent(replacement);
        JsonObject updated = read(snapshot, changed);
        check(updated.getAsJsonObject("goal").equals(goal.toJson())
                        && updated.getAsJsonObject("current_goal").equals(replacement.toJson())
                        && updated.getAsJsonArray("attempts").size() == 1,
                "replacement must expose the actual current goal without rewriting request or attempt history");
        check(read(summary, changed).get("current_outcome").getAsString().equals(replacement.outcome()),
                "task list must identify the replacement currently being executed");
        Goal recovery = new Goal("maicraft:acquire_items", "Obtain a water bucket", null,
                "{\"item_id\":\"minecraft:water_bucket\",\"count\":1}", "{}", List.of(), List.of());
        changed.insertRecovery(recovery);
        check(read(snapshot, changed).getAsJsonObject("current_goal").equals(recovery.toJson()),
                "a recovery prerequisite is the active goal while the replacement waits behind it");
        changed.addStepResult(new IntentTaskRecord.StepSnapshot(0, recovery.ability(), true,
                "bucket obtained", TaskResult.ok("bucket obtained").toJson()));
        check(read(snapshot, changed).getAsJsonObject("current_goal").equals(replacement.toJson()),
                "completing recovery must expose the resumed replacement");
        changed.terminal(TaskState.CANCELLED, TaskResult.cancelled("test ended"), 30);
        check(!read(snapshot, changed).has("current_goal") && !read(summary, changed).has("current_outcome"),
                "a terminal task must not advertise active work");
        activeExecutionIsObservedWithoutPersisting(goal, snapshot);
        System.out.println("IntentTerminalStateTest: passed");
    }

    private static void activeExecutionIsObservedWithoutPersisting(Goal goal, Method snapshot) throws Exception {
        var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        record.setState(TaskState.RUNNING);
        var task = new IntentTask(null, record, null);
        var field = IntentTask.class.getDeclaredField("child");
        field.setAccessible(true);
        field.set(task, new org.maiwithu.maicraft.task.Task() {
            public TaskState tick(net.minecraft.client.player.LocalPlayer player) { throw new AssertionError("read-only progress"); }
            public void stop(net.minecraft.client.player.LocalPlayer player, StopReason reason) { throw new AssertionError("read-only progress"); }
            public String name() { return "supply"; }
            public Map<String, Object> progress() {
                return Map.of("phase", "material_supply", "child", Map.of("source", "mine",
                        "item_id", "minecraft:oak_log", "position", Map.of("x", 1, "y", 2, "z", 3)));
            }
        });
        record.observeExecution(task.progress(), 42);
        JsonObject live = read(snapshot, record).getAsJsonObject("active_execution");
        check(live.get("phase").getAsString().equals("material_supply")
                        && live.getAsJsonObject("child").get("source").getAsString().equals("mine")
                        && !live.getAsJsonObject("child").has("position"),
                "active execution exposes the actual nested material task without leaking planned coordinates");
        live.addProperty("phase", "caller changed");
        record.pause(43, "paused_by_mcp");
        JsonObject paused = read(snapshot, record);
        check(paused.get("state").getAsString().equals("paused")
                        && paused.getAsJsonObject("active_execution").get("phase").getAsString().equals("material_supply")
                        && paused.getAsJsonObject("active_execution").get("observed_game_time").getAsLong() == 42,
                "pause preserves a detached last-observed progress snapshot, not a claim of ongoing work");
        JsonObject saved = IntentStateCodec.encode("test-world", List.of(), List.of(record), Map.of(), List.of())
                .getAsJsonArray("tasks").get(0).getAsJsonObject();
        check(!saved.has("active_execution"), "live diagnostics must not survive restoration as stale work");
        record.terminal(TaskState.CANCELLED, TaskResult.cancelled("cancelled"), 44);
        check(!read(snapshot, record).has("active_execution"), "terminal snapshots stop advertising active execution");
    }

    private static Method projection(String name) throws Exception {
        Method method = MaiCraftRuntimeFacade.class.getDeclaredMethod(name, IntentTaskRecord.class);
        method.setAccessible(true); return method;
    }
    private static JsonObject read(Method method, IntentTaskRecord task) throws Exception {
        return (JsonObject) method.invoke(null, task);
    }
    private static void assertTerminal(JsonObject snapshot, TaskState state) {
        check(snapshot.get("state").getAsString().equals(state.name().toLowerCase())
                        && snapshot.get("internal_state").getAsString().equals(state.name().toLowerCase())
                        && !snapshot.has("decision") && !snapshot.has("pause"),
                "terminal task reads must never advertise a pending decision or pause");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
