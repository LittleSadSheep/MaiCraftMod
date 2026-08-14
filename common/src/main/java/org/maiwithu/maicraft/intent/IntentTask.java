package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskDispatch;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A semantic parent task. Internal body tools become child tasks and are driven
 * here, so the parent remains the only selected scheduler winner.
 */
final class IntentTask implements Task {

    private static final Set<String> INTERNAL_RESULT_KEYS = Set.of(
            "entity_id", "entity_ids", "requested_entity_ids", "defeated_entity_ids",
            "lost_entity_ids", "unreachable_entity_ids", "combat_by_entity",
            "runtime_id", "runtime_ids", "target_runtime_id", "target_runtime_ids",
            "button", "click", "clicks", "slot", "slots", "inventory_slots",
            "slot_clicks", "click_sequence", "route", "waypoints", "path_nodes",
            "block_ops", "placements", "cells", "continuation_token",
            "continuation_prefix_hash");

    private final LocalPlayer player;
    private final IntentTaskRecord record;
    private final IntentRuntime runtime;

    private Task child;
    private TaskRecord childRecord;
    private IntentAction.Wait wait;
    private List<IntentAction.Tool> chain = List.of();
    private int chainIndex;
    private TaskResult terminalResult;
    private boolean terminalPublished;
    private long childSerial;
    /** Opaque one-use receipts stay inside the Mod; the model only chooses semantic retry. */
    private final Map<String, UUID> mechanicalContinuations = new LinkedHashMap<>();
    /** Verified positions retained inside the semantic parent, never rendered into tool results. */
    private final Map<Integer, Goal.WorldPosition> internalStepPositions = new LinkedHashMap<>();

    IntentTask(LocalPlayer player, IntentTaskRecord record, IntentRuntime runtime) {
        this.player = player;
        this.record = record;
        this.runtime = runtime;
    }

    @Override
    public boolean canRun(LocalPlayer ignored) {
        return !record.paused();
    }

    @Override
    public TaskState tick(LocalPlayer ignored) {
        try {
            return tickSemanticParent();
        } catch (RuntimeException failure) {
            // A malformed adapter result or unexpected internal capability failure must
            // still enter the semantic recovery protocol. Fatal VM errors are deliberately
            // not caught here.
            abandonChildAfterUnexpectedFailure();
            wait = null;
            return failStep(TaskState.FAILED,
                    TaskResult.fail("semantic step failed safely: " + safeMessage(failure)));
        }
    }

    private TaskState tickSemanticParent() {
        if (record.stepIndex() >= record.steps().size()) {
            terminalResult = successResult();
            return TaskState.SUCCESS;
        }

        IntentTaskRecord.DecisionAnswer answer = record.takeAnswer();
        if (answer != null) {
            if ("cancel".equals(answer.choice()) || "cancel_task".equals(answer.choice())) {
                mechanicalContinuations.clear();
                terminalResult = TaskResult.cancelled("cancelled at decision " + answer.decisionId());
                return TaskState.CANCELLED;
            }
            if ("respawn".equals(answer.choice()) || "spectate".equals(answer.choice())) {
                // Death-screen actions are executed natively by the runtime facade.  Once a
                // restored task is explicitly resumed, re-evaluate its semantic step instead of
                // passing a transport/lifecycle answer to an ability adapter.
                return TaskState.RUNNING;
            }
            if ("skip".equals(answer.choice())) {
                discardContinuation(currentGoal());
                completeStep(TaskResult.ok("step skipped by explicit decision"));
                return record.stepIndex() >= record.steps().size() ? TaskState.SUCCESS : TaskState.RUNNING;
            }
            if ("recover".equals(answer.choice()) || "replace_goal".equals(answer.choice())) {
                if ("replace_goal".equals(answer.choice())) discardContinuation(currentGoal());
                return applySemanticAnswer(answer);
            }
            IntentAction resolved = AbilityAdapter.fromAnswer(
                    resolvedCurrentGoal(), answer, player, runtime,
                    continuationFor(currentGoal()));
            return begin(resolved);
        }

        if (child != null) {
            return tickChild();
        }
        if (wait != null) {
            return tickWait();
        }
        if (!chain.isEmpty()) {
            return beginTool(chain.get(chainIndex));
        }

        Goal semanticGoal = currentGoal();
        return begin(AbilityAdapter.adapt(
                resolvedCurrentGoal(), player, runtime, continuationFor(semanticGoal)));
    }

    private TaskState begin(IntentAction action) {
        if (action instanceof IntentAction.Chain nextChain) {
            chain = nextChain.actions();
            chainIndex = 0;
            return beginTool(chain.getFirst());
        }
        if (action instanceof IntentAction.Decision decision) {
            return requestDecision(decision.snapshot());
        }
        if (action instanceof IntentAction.Remember remember) {
            runtime.remember(remember.label(), remember.position());
            completeStep(TaskResult.ok("remembered " + remember.label(),
                    Map.of("label", remember.label(),
                            "x", remember.position().x(),
                            "y", remember.position().y(),
                            "z", remember.position().z())));
            return afterImmediate();
        }
        if (action instanceof IntentAction.Wait nextWait) {
            wait = nextWait;
            return tickWait();
        }
        if (action instanceof IntentAction.Tool toolAction) {
            return beginTool(toolAction);
        }
        return failStep(
                TaskState.FAILED,
                TaskResult.fail("intent adapter produced no executable action"));
    }

    private TaskState beginTool(IntentAction.Tool action) {
        MaiCraftTool tool = ToolRegistry.resolve(action.toolName());
        if (tool == null) {
            return failStep(
                    TaskState.FAILED,
                    TaskResult.fail("required internal capability is not registered: "
                            + action.toolName()));
        }

        AtomicReference<TaskRecord> captured = new AtomicReference<>();
        AtomicReference<String> immediate = new AtomicReference<>();
        String childCallId = "intent-" + record.externalId() + "-"
                + record.stepIndex() + "-" + (++childSerial);
        try {
            TaskDispatch.captureNext(captured::set, () ->
                    tool.onGameCall(childCallId, action.arguments(), player, immediate::set));
        } catch (RuntimeException exception) {
            return failStep(
                    TaskState.FAILED,
                    TaskResult.fail("ability " + currentGoal().ability()
                            + " could not start: " + safeMessage(exception)));
        }

        if (captured.get() != null) {
            childRecord = captured.get();
            childRecord.setState(TaskState.RUNNING);
            childRecord.markStarted(player.level().getGameTime());
            child = TaskFactory.create(player, childRecord);
            try {
                child.start(player);
            } catch (RuntimeException exception) {
                childRecord.setState(TaskState.FAILED);
                try {
                    child.result(TaskState.FAILED);
                } catch (RuntimeException ignoredFailure) {
                }
                TaskResult failure = TaskResult.fail(
                        "child start failed: " + safeMessage(exception));
                clearChild();
                return failStep(TaskState.FAILED, failure);
            }
            if (childRecord.getState().isTerminal()) {
                return finishChild();
            }
            return TaskState.RUNNING;
        }

        if (immediate.get() != null) {
            TaskResult result = parseImmediate(immediate.get());
            if (!result.success()) return failStep(TaskState.FAILED, result);
            return finishToolSuccess(result);
        }

        return failStep(
                TaskState.FAILED,
                TaskResult.fail("internal capability produced neither a child task nor a result: "
                        + action.toolName()));
    }

    private TaskState tickChild() {
        if (player.level().getGameTime() >= childRecord.getDeadlineGameTime()) {
            childRecord.setState(TaskState.TIMEOUT);
        } else {
            try {
                childRecord.setState(child.tick(player));
            } catch (RuntimeException exception) {
                childRecord.setState(TaskState.FAILED);
                childRecord.setResult(TaskResult.fail(
                        "child tick failed: " + safeMessage(exception)));
            }
        }
        return childRecord.getState().isTerminal() ? finishChild() : TaskState.RUNNING;
    }

    private TaskState finishChild() {
        TaskState state = childRecord.getState();
        TaskResult result;
        try {
            result = child.result(state);
        } catch (RuntimeException exception) {
            result = TaskResult.fail("child result failed: " + safeMessage(exception));
            state = TaskState.FAILED;
        }
        if (result == null) result = defaultResult(state);
        InternalPositionReceipt.Position internalPosition = result.success()
                && childRecord instanceof InternalPositionReceipt receipt
                ? receipt.internalVerifiedPosition() : null;
        if (internalPosition != null) {
            internalStepPositions.put(record.stepIndex(), new Goal.WorldPosition(
                    internalPosition.x(), internalPosition.y(), internalPosition.z(),
                    internalPosition.dimension()));
        }
        clearChild();
        if (!result.success()) {
            return failStep(state, result);
        }
        return finishToolSuccess(result);
    }

    private TaskState finishToolSuccess(TaskResult result) {
        if (!chain.isEmpty()) {
            chainIndex++;
            if (chainIndex < chain.size()) return TaskState.RUNNING;
            chain = List.of();
            chainIndex = 0;
        }
        discardContinuation(currentGoal());
        completeStep(result);
        return afterImmediate();
    }

    private TaskState applySemanticAnswer(IntentTaskRecord.DecisionAnswer answer) {
        JsonObject details = answer.details();
        if (!details.has("goal") || !details.get("goal").isJsonObject()) {
            return requestDecision(RecoveryAdvisor.invalidSemanticAnswer(
                    currentGoal(), answer.choice() + " requires details.goal"));
        }
        Goal semanticGoal;
        try {
            semanticGoal = Goal.fromJson(details.getAsJsonObject("goal"));
            runtime.validateGoal(semanticGoal);
        } catch (RuntimeException exception) {
            return requestDecision(RecoveryAdvisor.invalidSemanticAnswer(
                    currentGoal(), safeMessage(exception)));
        }
        if ("recover".equals(answer.choice())) {
            internalStepPositions.remove(record.stepIndex());
            record.insertRecovery(semanticGoal);
            runtime.semanticPlanChanged(record, semanticGoal, false);
        } else {
            internalStepPositions.remove(record.stepIndex());
            record.replaceCurrent(semanticGoal);
            runtime.semanticPlanChanged(record, semanticGoal, true);
        }
        return TaskState.RUNNING;
    }

