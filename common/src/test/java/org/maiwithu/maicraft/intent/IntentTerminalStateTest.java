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
        System.out.println("IntentTerminalStateTest: passed");
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
