package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.io.IOException;
import java.time.Duration;

/**
 * 保存客户端业务任务的计划、公开 ID、进度、决策和持久化状态，供 MCP 查询。
 *
 * <p>这里负责登记任务和发布状态；身体动作由 {@link CompanionTickDispatcher} 调度，
 * 普通业务任务的父子步骤由 IntentTask 推进。
 */
public final class IntentRuntime {

    private static final int MAX_PLANS = 128;
    private static final int MAX_TASKS = 256;
    private static final int MAX_REQUEST_KEYS = 512;
    private static final int MAX_LANDMARKS = 256;
    private static final int MAX_ATTENTION_ARRAY = 12;
    private static final int MAX_ATTENTION_STRING = 1_024;
    private static final long SAVE_INTERVAL_NANOS = 5_000_000_000L;
    private static final Set<String> ATTENTION_INTERNAL_KEYS = Set.of(
            "entity_id", "entity_ids", "runtime_id", "runtime_ids",
            "slot", "slots", "click", "clicks", "route", "waypoints",
            "path_nodes", "block_ops", "placements", "cells",
            "x", "y", "z", "position", "center", "location", "destination", "bounds");
    private static final Set<String> ATTENTION_RESULT_DATA_KEYS = Set.of(
            "chat_state", "typed_characters", "total_characters", "submission_attempted",
            "delivery_status", "effects_started", "mechanical_retry_allowed",
            "task_id", "failure_code", "failure_type", "requires_decision",
            "build_diagnostics", "support_access", "completed", "placed", "cleared", "stopped_phase", "temporary_supports_remaining",
            "requires_narration", "outcome_uncertain", "recoverable", "goal",
            "item_ids", "required_final_count", "observed_final_count", "missing",
            "allowed_sources", "achieved_coverage",
            "required_coverage", "dark_cell_count", "site_verified",
            "waterfront_required", "max_distance", "farthest_body_distance",
            "target", "requested", "gathered", "confirmed_target_breaks", "scope",
            "last_probe", "suggestions", "recovery_options", "decision", "recovery", "steps",
            "completed_effects", "remaining_effects", "landing_assist", "landing_assist_observed");
    private static final Set<String> ATTENTION_ISSUE_FACT_KEYS = Set.of(
            "failure_type", "recipe_id", "missing", "item_ids", "required_final_count",
            "observed_final_count", "target", "requested",
            "gathered", "confirmed_target_breaks", "candidate_count");

    private static final Set<String> CORE_ABILITIES = Set.of(
            ChatAbilityAdapter.ABILITY,
            "maicraft:remember_place",
            "maicraft:sleep",
            "maicraft:travel",
            "maicraft:travel_dimension",
            "maicraft:find_structure",
            "maicraft:reach_milestone",
            "maicraft:defeat_ender_dragon",
            "maicraft:obtain_elytra",
            "maicraft:craft",
            "maicraft:cook",
            "maicraft:trade",
            "maicraft:build",
            BuildDesignAdapter.ABILITY,
            "maicraft:light_area",
            "maicraft:connect_mechanical_power",
            "maicraft:inspect_machine",
            "maicraft:design_machine",
            "maicraft:operate_machine",
            "maicraft:modify_machine",
            "maicraft:build_machine",
            "maicraft:acquire_items",
            "maicraft:wait_for_condition",
            "maicraft:sequence");

    /** Immutable startup snapshot: core and general adapters cannot drift after publication. */
    public static final Set<String> KNOWN_ABILITIES = Stream.concat(
                    CORE_ABILITIES.stream(), GeneralAbilityAdapter.abilities().stream())
            .collect(Collectors.toUnmodifiableSet());

    private static final IntentRuntime INSTANCE = new IntentRuntime();
    private static boolean registered;

    private final LinkedHashMap<UUID, Plan> plans = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, IntentTaskRecord> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<String, UUID> requestKeys = new LinkedHashMap<>();
    private final LinkedHashMap<String, Landmark> landmarks = new LinkedHashMap<>();
    private final AttentionFeed attention = new AttentionFeed();
    private final IntentStateStore stateStore = new IntentStateStore();
    private StateIdentity stateIdentity;
    private boolean bodyAttached;
    private boolean dirty;
    private long nextSaveNanos;

    private IntentRuntime() {}

    public static IntentRuntime get() {
        ensureRegistered();
        return INSTANCE;
    }

    private static synchronized void ensureRegistered() {
        // 业务父任务也走同一个 TaskFactory；不另外启动一套与身体调度器争抢控制权的循环。
        if (registered) return;
        TaskFactory.register(IntentTaskRecord.class,
                (player, record) -> new IntentTask(player, record, INSTANCE));
        registered = true;
    }

    public Plan compile(Goal goal, long gameTime) {
        validateGoal(goal);
        Plan plan = Plan.compile(goal, gameTime);
        plans.put(plan.id(), plan);
        trimOldest(plans, MAX_PLANS);
        markDirty();
        return plan;
    }

    public Plan plan(UUID id) {
        return plans.get(id);
    }

    public IntentTaskRecord execute(LocalPlayer player, Goal goal, UUID planId, String requestKey) {
        return execute(player, goal, planId, requestKey,
                org.maiwithu.maicraft.client.preview.PreviewController::showDesign);
    }

    public static boolean isReadOnlyDesign(Goal goal) {
        return BuildDesignAdapter.ABILITY.equals(goal.ability()) || BuildingSceneContract.noConstruction(goal);
    }

    IntentTaskRecord execute(LocalPlayer player, Goal goal, UUID planId, String requestKey,
                            java.util.function.Predicate<org.maiwithu.maicraft.client.preview.PreviewSession> publishDesign) {
        validateGoal(goal);
        if (requestKey != null && !requestKey.isBlank()) {
            // 网络重试可能重复提交同一请求；同一 request_key 复用原记录，避免重复开工。
            UUID existingId = requestKeys.get(requestKey);
            IntentTaskRecord existing = existingId == null ? null : tasks.get(existingId);
            if (existing != null) return existing;
        }

        if (stateIdentity == null) {
            throw new IllegalStateException(
                    "semantic state is not bound to the active game connection yet");
        }
        if (requestKey != null && requestKey.length() > 256) {
            throw new IllegalArgumentException("request_key accepts at most 256 characters");
        }
        UUID taskId = UUID.randomUUID();
        IntentTaskRecord record = new IntentTaskRecord(
                taskId, planId, goal, stateIdentity.key());
        record.bindDirty(this::markDirty);
        tasks.put(taskId, record);
        if (requestKey != null && !requestKey.isBlank()) {
            requestKeys.put(requestKey, taskId);
            trimOldest(requestKeys, MAX_REQUEST_KEYS);
        }
        trimTasks();
        markDirty();
        publish("started", record, "Started: " + goal.outcome(), new JsonObject());
        if (isReadOnlyDesign(goal)) {
            TaskResult result;
            try {
                var action = BuildingSceneContract.supports(goal)
                        ? BuildingSceneAdapter.adapt(goal, player, this, publishDesign)
                        : BuildDesignAdapter.design(goal, player, this, publishDesign);
                if (!(action instanceof IntentAction.Report report))
                    throw new IllegalStateException("read-only design returned an executable action");
                result = report.result();
            } catch (RuntimeException failure) {
                result = TaskResult.fail("Read-only design failed: " + failure.getMessage(),
                        Map.of("failure_code", "preview_design_failed", "construction_started", false));
            }
            Map<String, Object> data = new LinkedHashMap<>(result.data() == null ? Map.of() : result.data());
            data.put("task_id", taskId.toString());
            result = new TaskResult(result.success(), result.message(), false, false, data);
            record.addStepResult(new IntentTaskRecord.StepSnapshot(0, goal.ability(),
                    result.success(), result.message(), result.toJson()));
            TaskState state = result.success() ? TaskState.SUCCESS : TaskState.FAILED;
            record.terminal(state, result, player.level().getGameTime());
            terminal(record, state, result);
            return record;
        }
        // 放入唯一的当前任务槽，会替换旧任务；这里的接单不等于业务目标已经完成。
        CompanionTickDispatcher.submitCurrent(player, record);
        return record;
    }

    public IntentTaskRecord task(UUID id) {
        return tasks.get(id);
    }

    /** Resolve an idempotent execute request without asking the model to guess a task id. */
    public IntentTaskRecord taskForRequestKey(String requestKey) {
        if (requestKey == null || requestKey.isBlank()) return null;
        UUID id = requestKeys.get(requestKey);
        return id == null ? null : tasks.get(id);
    }

    public List<IntentTaskRecord> tasks(int limit) {
        List<IntentTaskRecord> all = new ArrayList<>(tasks.values());
        int from = Math.max(0, all.size() - Math.max(1, limit));
        List<IntentTaskRecord> result = new ArrayList<>(all.subList(from, all.size()));
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    public void remember(String label, Goal.WorldPosition position) {
        remember(label, position, LandmarkAreaRole.ORDINARY);
    }

    public void remember(
            String label, Goal.WorldPosition position, LandmarkAreaRole areaRole) {
        String key = normalizeLabel(label);
        landmarks.put(key, new Landmark(label, position, areaRole));
        trimOldest(landmarks, MAX_LANDMARKS);
        markDirty();
    }

    public Landmark landmark(String label) {
        return label == null ? null : landmarks.get(normalizeLabel(label));
    }

    public List<Landmark> landmarks() {
        return List.copyOf(landmarks.values());
    }

    /**
     * Bind semantic memory after the scheduler has bound the current player/world for this tick.
     * A different identity is a hard boundary; a same-identity portal handoff keeps the exact
     * semantic parent only when it also survived in the scheduler.
     */
    public void tickPersistence(Minecraft minecraft, LocalPlayer player) {
        StateIdentity next = StateIdentity.resolve(minecraft).orElse(null);
        if (next == null || player == null) return;
        if (stateIdentity == null) {
            clearSemanticState();
            stateIdentity = next;
            restoreBound(player.level().getGameTime());
        } else if (!stateIdentity.key().equals(next.key())) {
            if (bodyAttached) captureCheckpoint(true);
            clearSemanticState();
            stateIdentity = next;
            restoreBound(player.level().getGameTime());
        } else if (!bodyAttached) {
            boolean semanticHandoffSurvived =
                    CompanionTickDispatcher.current() instanceof IntentTaskRecord current
                    && tasks.get(current.externalId()) == current;
            stateIdentity = next;
            if (!semanticHandoffSurvived) {
                clearSemanticState();
                restoreBound(player.level().getGameTime());
            }
        } else {
            stateIdentity = next;
        }
        bodyAttached = true;
        if ((dirty || stateStore.hasFailedSave(stateIdentity))
                && System.nanoTime() >= nextSaveNanos) captureCheckpoint(false);
    }

    /** Bind an MCP request without racing a pending body/world replacement. */
    public void bindForRequest(Minecraft minecraft, LocalPlayer player) {
        StateIdentity next = StateIdentity.resolve(minecraft).orElseThrow(
                () -> new IllegalStateException("semantic state has no active game identity"));
        if (stateIdentity == null) {
            clearSemanticState();
            stateIdentity = next;
            restoreBound(player.level().getGameTime());
            bodyAttached = true;
            return;
        }
        if (!bodyAttached || !stateIdentity.key().equals(next.key())) {
            throw new IllegalStateException(
                    "semantic state is changing connection or world; retry after the next client tick");
        }
    }

    /**
     * Save the old connection before the body scheduler observes a replacement world.  Marking the
     * body detached after capturing a checkpoint prevents cancellation cleanup from overwriting the
     * resumable semantic snapshot; the normal post-scheduler bind then clears the old read model.
     */
    public void beforeBodyTick(Minecraft minecraft) {
        StateIdentity next = StateIdentity.resolve(minecraft).orElse(null);
        if (next != null && stateIdentity != null && bodyAttached
                && !stateIdentity.key().equals(next.key()) && captureCheckpoint(true)) {
            bodyAttached = false;
        }
    }

    /** Save before the body scheduler cancels records during disconnect or replacement. */
    public void bodyUnavailable() {
        captureCheckpoint(true);
        bodyAttached = false;
        gameEvent("runtime.unavailable", "The controlled body disconnected; task state must be resynchronized.", new JsonObject());
    }

    /** Capture the exact checkpoint before death cleanup; disk persistence continues asynchronously. */
    public boolean checkpointDeath() {
        return captureCheckpoint(true);
    }

    /**
     * Capture and detach before a native respawn replaces LocalPlayer in the same world identity.
     * The next normal persistence bind restores the non-terminal task in a paused state.
     */
    public boolean prepareRespawnHandoff() {
        boolean saved = captureCheckpoint(true);
        bodyAttached = false;
        return saved;
    }

    /** Install a task-scoped death decision without creating a death-specific public ability. */
    public void requestDeathDecision(
            IntentTaskRecord record, boolean hardcore, boolean spectator,
            JsonObject suppliedContext) {
        if (record == null || record.getState().isTerminal()) return;
        JsonObject context = suppliedContext == null
                ? new JsonObject() : suppliedContext.deepCopy();
        context.addProperty("decision_kind", "death_recovery");
        context.addProperty("hardcore", hardcore);
        context.addProperty("spectator", spectator);
        context.addProperty("native_action_requires_explicit_answer", true);
        context.addProperty("superseded_decision_pending", record.decisionSnapshot() != null);
        context.addProperty("item_recovery_started", false);
        context.addProperty("item_recovery_claimed", false);
        String primaryChoice = hardcore || spectator ? "spectate" : "respawn";
        String primaryDescription = hardcore || spectator
                ? "Explicitly request the native spectate action; do not claim task recovery."
                : "Explicitly request native respawn, then reassess safety before resuming.";
        IntentTaskRecord.DecisionSnapshot snapshot = new IntentTaskRecord.DecisionSnapshot(
                UUID.randomUUID(),
                hardcore || spectator
                        ? "The agent died in a mode where normal auto-respawn is unavailable. Spectate or cancel the task?"
                        : "The agent died without auto-respawn authorisation. Respawn or cancel the task?",
                List.of(
                        new IntentTaskRecord.DecisionOption(primaryChoice, primaryDescription),
                        new IntentTaskRecord.DecisionOption(
                                "cancel_task", "Cancel the semantic task and leave respawn to the player.")),
                context.toString());
        Minecraft minecraft = Minecraft.getInstance();
        long gameTime = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        record.requestDecision(snapshot, gameTime);
        decision(record, snapshot);
        captureCheckpoint(true);
    }

    /** 保存最后检查点，并给后台写盘最多两秒；已脱离身体的交接快照不被取消后的记录覆盖。 */
    public void shutdownPersistence() {
        captureCheckpoint(true);
        bodyAttached = false;
        IntentStateStore.FlushResult result = stateStore.awaitPendingSaves(Duration.ofSeconds(2));
        if (result != IntentStateStore.FlushResult.SAVED) {
            Constants.LOG.warn("Could not confirm final MaiCraft checkpoints on disk before shutdown ({})", result);
        }
    }

    public void requireCurrentBinding(IntentTaskRecord record) {
        if (record == null || stateIdentity == null || !bodyAttached
                || record.bindingKey() == null
                || !stateIdentity.key().equals(record.bindingKey())) {
            throw new IllegalStateException(
                    "semantic task belongs to another connection or world");
        }
    }

    public void restoredTaskAttached(IntentTaskRecord record) {
        requireCurrentBinding(record);
        record.markAttached();
        markDirty();
    }

    private void restoreBound(long gameTime) {
        IntentStateStore.LoadResult loaded = stateStore.load(stateIdentity);
        int restoredTasks = 0;
        int restoredLandmarks = 0;
        int restoredTerminal = 0;
        String status = loaded.status().name().toLowerCase(Locale.ROOT);
        if (loaded.status() == IntentStateStore.Status.LOADED) {
            try {
                IntentStateCodec.Decoded decoded = IntentStateCodec.decode(loaded.root());
                for (Plan plan : decoded.plans()) {
                    validateGoal(plan.goal());
                    if (plans.putIfAbsent(plan.id(), plan) != null) {
                        throw new IllegalArgumentException("duplicate persisted plan id");
                    }
                }
                for (IntentStateCodec.TaskSnapshot snapshot : decoded.tasks()) {
                    validateGoal(snapshot.goal());
                    for (Goal step : snapshot.steps()) validateGoal(step);
                    for (IntentTaskRecord.AttemptSnapshot attempt : snapshot.attempts()) {
                        validateGoal(attempt.goal());
                    }
                    IntentTaskRecord record = IntentTaskRecord.restored(
                            snapshot.id(), snapshot.planId(), snapshot.goal(),
                            stateIdentity.key(), snapshot.steps(), snapshot.stepIndex(),
                            snapshot.completed(), snapshot.internalPositions(),
                            snapshot.internalAreaProtections(),
                            snapshot.attempts(), snapshot.decision(),
                            snapshot.pendingAnswer(), snapshot.terminal(), gameTime);
                    record.bindDirty(this::markDirty);
                    if (tasks.putIfAbsent(record.externalId(), record) != null) {
                        throw new IllegalArgumentException("duplicate persisted task id");
                    }
                    restoredTasks++;
                    if (record.getState().isTerminal()) restoredTerminal++;
                }
                for (Map.Entry<String, UUID> entry : decoded.requestKeys().entrySet()) {
                    if (tasks.containsKey(entry.getValue())) {
                        requestKeys.put(entry.getKey(), entry.getValue());
                    }
                }
                for (Landmark landmark : decoded.landmarks()) {
                    if (landmark.label() == null || landmark.label().isBlank()
                            || landmark.position() == null) {
                        throw new IllegalArgumentException("invalid persisted landmark");
                    }
                    landmarks.put(normalizeLabel(landmark.label()), landmark);
                    restoredLandmarks++;
                }
            } catch (RuntimeException invalidModel) {
                stateStore.quarantine(stateIdentity);
                clearSemanticState();
                restoredTasks = 0;
                restoredLandmarks = 0;
                restoredTerminal = 0;
                status = "corrupt";
                Constants.LOG.warn(
                        "MaiCraft semantic state model was invalid and quarantined ({})",
                        invalidModel.getClass().getSimpleName());
            }
        }
        dirty = false;
        nextSaveNanos = System.nanoTime() + SAVE_INTERVAL_NANOS;
        JsonObject data = new JsonObject();
        data.addProperty("status", status);
        data.addProperty("restored_tasks", restoredTasks);
        data.addProperty("restored_terminal_tasks", restoredTerminal);
        data.addProperty("restored_landmarks", restoredLandmarks);
        attention.publish(
                "state_restored",
                null,
                "Restored " + restoredTasks + " semantic task(s) and "
                        + restoredLandmarks + " landmark(s); non-terminal work is paused.",
                data);
    }

    /** Returns whether a detached in-process checkpoint is available, not a disk durability receipt. */
    private boolean captureCheckpoint(boolean force) {
        if (stateIdentity == null) return false;
        // Body teardown mutates old task records after the checkpoint. Never replace the
        // detached handoff with those cancellation side effects, including on shutdown.
        if (!bodyAttached) return stateStore.hasSnapshot(stateIdentity);
        if (!force && !dirty && !stateStore.hasFailedSave(stateIdentity)) return true;
        try {
            JsonObject root = IntentStateCodec.encode(
                    stateIdentity.key(), plans.values(), tasks.values(),
                    requestKeys, landmarks.values());
            stateStore.saveAsync(stateIdentity, root);
            dirty = false;
            return true;
        } catch (IOException | RuntimeException failure) {
            dirty = true;
            Constants.LOG.warn(
                    "Could not save MaiCraft semantic state ({})",
                    failure.getClass().getSimpleName());
            return false;
        } finally {
            nextSaveNanos = System.nanoTime() + SAVE_INTERVAL_NANOS;
        }
    }

    private void clearSemanticState() {
        bodyAttached = false;
        plans.clear();
        tasks.clear();
        requestKeys.clear();
        landmarks.clear();
        attention.clear();
        dirty = false;
    }

    private void markDirty() {
        dirty = true;
    }

    void decision(IntentTaskRecord record, IntentTaskRecord.DecisionSnapshot decision) {
        markDirty();
        JsonObject data = new JsonObject();
        data.addProperty("decision_id", decision.id().toString());
        JsonArray options = new JsonArray();
        for (IntentTaskRecord.DecisionOption option : decision.options()) {
            JsonObject item = new JsonObject();
            item.addProperty("choice", option.choice());
            item.addProperty("description", option.description());
            options.add(item);
        }
        data.add("options", options);
        data.add("context", sanitizeAttentionContext(decision.context()));
        publish("decision", record, decision.question(), data);
    }

    public void paused(IntentTaskRecord record, String reason) {
        markDirty();
        publish("paused", record, reason, new JsonObject());
    }

    public void resumed(IntentTaskRecord record) {
        markDirty();
        publish("resumed", record, "Task resumed", new JsonObject());
    }

    void stepCompleted(IntentTaskRecord record) {
        JsonObject data = new JsonObject();
        data.addProperty("completed_step_count", record.stepIndex());
        data.addProperty("step_count", record.steps().size());
        publish("step_completed", record, "A semantic step finished; the parent task may still be running.", data);
    }

    /** Pause only an otherwise-running semantic parent while first-person control is unavailable. */
    public void controlUnavailable(LocalPlayer player, String reason) {
        if (player == null) return;
        if (!(CompanionTickDispatcher.current() instanceof IntentTaskRecord record)
                || record.getState().isTerminal() || record.pauseSnapshot() != null) {
            return;
        }
        if (!record.pause(player.level().getGameTime(), "control_unavailable")) return;
        markDirty();
        JsonObject data = new JsonObject();
        data.addProperty("reason", reason == null || reason.isBlank()
                ? "first-person automation control is unavailable" : reason);
        publish("paused", record,
                "Task paused because first-person automation control is unavailable.", data);
    }

    /** Resume only the pause installed by {@link #controlUnavailable}; preserve every other pause. */
    public void controlAvailable() {
        if (!(CompanionTickDispatcher.current() instanceof IntentTaskRecord record)
                || record.getState().isTerminal() || record.decisionSnapshot() != null
                || record.pauseSnapshot() == null
                || !"control_unavailable".equals(record.pauseSnapshot().reason())) {
            return;
        }
        if (record.resume()) resumed(record);
    }

    void validateGoal(Goal goal) {
        SemanticGoalContract.validate(goal, KNOWN_ABILITIES);
        IntentStateCodec.requirePersistableGoal(goal);
        rejectMicroInstructions(goal);
    }

    /** Validate public decision details before the answer can unpause or mutate a task record. */
    public void validateDecisionAnswer(
            IntentTaskRecord record, String choice, JsonObject details) {
        JsonObject supplied = details == null ? new JsonObject() : details;
        IntentTaskRecord.DecisionSnapshot pending = record.decisionSnapshot();
        if ("retry".equals(choice) && pending != null) {
            JsonObject context = pending.context();
            JsonObject failure = context.has("failure") && context.get("failure").isJsonObject()
                    ? context.getAsJsonObject("failure") : null;
            if (!RecoveryAdvisor.ordinaryRetryAllowed(failure)) {
                throw new SemanticContractException(
                        "unsafe_retry", "answer.choice",
                        record.stepIndex() < record.steps().size()
                                ? record.steps().get(record.stepIndex()).ability() : null,
                        "ordinary retry is forbidden because the previous mechanical outcome "
                                + "is uncertain or explicitly unsafe to repeat; inspect current "
                                + "facts and choose recover, replace_goal, skip or cancel");
            }
        }
        boolean semanticReplacement = "recover".equals(choice) || "replace_goal".equals(choice);
        if (semanticReplacement) {
            if (!supplied.has("goal") || !supplied.get("goal").isJsonObject()) {
                throw new SemanticContractException(
                        "decision_goal_required", "answer.details.goal",
                        record.stepIndex() < record.steps().size()
                                ? record.steps().get(record.stepIndex()).ability() : null,
                        choice + " requires exactly one semantic Goal in details.goal.");
            }
            if (supplied.size() != 1) {
                throw new SemanticContractException(
                        "unknown_decision_detail", "answer.details",
                        record.stepIndex() < record.steps().size()
                                ? record.steps().get(record.stepIndex()).ability() : null,
                        choice + " accepts details.goal only; extra fields were refused.");
            }
            validateGoal(Goal.fromJson(supplied.getAsJsonObject("goal")));
            return;
        }
        if (supplied.has("goal")) {
            throw new SemanticContractException(
                    "decision_goal_not_allowed", "answer.details.goal",
                    record.stepIndex() < record.steps().size()
                            ? record.steps().get(record.stepIndex()).ability() : null,
                    choice + " cannot carry a replacement Goal; use recover or replace_goal.");
        }
        if (record.stepIndex() >= record.steps().size()) {
            return;
        }
        JsonObject updates = supplied.has("parameters")
                && supplied.get("parameters").isJsonObject()
                ? supplied.getAsJsonObject("parameters") : supplied;
        Goal current = record.steps().get(record.stepIndex());
        JsonObject merged = current.parameters();
        updates.entrySet().forEach(entry ->
                merged.add(entry.getKey(), entry.getValue().deepCopy()));
        validateGoal(current.withParameters(merged));
    }

    void semanticPlanChanged(IntentTaskRecord record, Goal semanticGoal, boolean replaced) {
        markDirty();
        JsonObject data = new JsonObject();
        data.addProperty("mode", replaced ? "replace" : "prerequisite");
        data.add("goal", semanticGoal.toJson());
        publish(
                "plan_changed",
                record,
                replaced
                        ? "Replaced the current semantic step."
                        : "Inserted a semantic prerequisite; the failed step will be retried afterwards.",
                data);
    }

    void terminal(IntentTaskRecord record, TaskState state, TaskResult result) {
        markDirty();
        String type = switch (state) {
            case SUCCESS -> "completed";
            case CANCELLED -> "cancelled";
            default -> "failed";
        };
        JsonObject data = result == null ? new JsonObject() : compactAttentionResult(resultJson(result));
        String message = result == null ? state.name().toLowerCase() : result.message();
        publish(type, record, message, data);
    }

    public JsonObject attention(long afterCursor, int limit) {
        return attention(afterCursor, limit, null, null);
    }

    public JsonObject attention(long afterCursor, int limit, String streamId, UUID taskId) {
        return attention.read(afterCursor, limit, streamId, taskId);
    }

    public JsonObject attentionCheckpoint() {
        return attention.checkpoint();
    }

    public boolean attentionAvailable() {
        return bodyAttached;
    }

    public AutoCloseable subscribeAttention(Consumer<JsonElement> listener) {
        return attention.subscribe(listener);
    }

    /** Publish a concise game-side fact that may require the LLM's attention. */
    public void gameEvent(String type, String message, JsonObject data) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("game event type is required");
        }
        attention.publish(type, null, message, data == null ? new JsonObject() : data);
    }

    private void publish(String type, IntentTaskRecord record, String message, JsonObject data) {
        attention.publish(type, record.externalId(), message, data);
    }

    private static JsonObject resultJson(TaskResult result) {
        return JsonParser.parseString(result.toJson()).getAsJsonObject();
    }

    private static JsonObject sanitizeAttentionContext(JsonObject source) {
        JsonObject compactSource = source.deepCopy();
        if (compactSource.has("failure") && compactSource.get("failure").isJsonObject()) {
            compactSource.add(
                    "failure", compactAttentionResult(compactSource.getAsJsonObject("failure")));
        }
        JsonElement sanitized = sanitizeAttentionValue(compactSource);
        return sanitized != null && sanitized.isJsonObject()
                ? sanitized.getAsJsonObject() : new JsonObject();
    }

    private static JsonObject compactAttentionResult(JsonObject source) {
        JsonObject result = new JsonObject();
        copyAttentionField(source, result, "success");
        copyAttentionField(source, result, "message");
        copyAttentionField(source, result, "timed_out");
        copyAttentionField(source, result, "interrupted");
        if (!source.has("data") || !source.get("data").isJsonObject()) return result;

        JsonObject sourceData = source.getAsJsonObject("data");
        JsonObject data = new JsonObject();
        for (String key : ATTENTION_RESULT_DATA_KEYS) {
            copyAttentionField(sourceData, data, key);
        }
        if (sourceData.has("issues") && sourceData.get("issues").isJsonArray()) {
            JsonArray issues = new JsonArray();
            JsonArray all = sourceData.getAsJsonArray("issues");
            int from = Math.max(0, all.size() - 6);
            for (int index = from; index < all.size(); index++) {
                JsonElement value = all.get(index);
                if (!value.isJsonObject()) continue;
                JsonObject sourceIssue = value.getAsJsonObject();
                JsonObject issue = new JsonObject();
                copyAttentionField(sourceIssue, issue, "source");
                copyAttentionField(sourceIssue, issue, "code");
                copyAttentionField(sourceIssue, issue, "summary");
                if (sourceIssue.has("facts") && sourceIssue.get("facts").isJsonObject()) {
                    JsonObject facts = new JsonObject();
                    for (String key : ATTENTION_ISSUE_FACT_KEYS) {
                        copyAttentionField(sourceIssue.getAsJsonObject("facts"), facts, key);
                    }
                    if (facts.size() > 0) issue.add("facts", facts);
                }
                issues.add(issue);
            }
            if (issues.size() > 0) data.add("issues", issues);
        }
        if (data.size() > 0) result.add("data", data);
        return result;
    }

    private static void copyAttentionField(JsonObject source, JsonObject target, String key) {
        if (!source.has(key)) return;
        JsonElement sanitized = sanitizeAttentionValue(source.get(key));
        if (sanitized != null) target.add(key, sanitized);
    }

    private static JsonElement sanitizeAttentionValue(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonArray()) {
            JsonArray clean = new JsonArray();
            JsonArray source = value.getAsJsonArray();
            int copied = 0;
            for (JsonElement element : source) {
                if (copied >= MAX_ATTENTION_ARRAY) break;
                JsonElement nested = sanitizeAttentionValue(element);
                if (nested != null) {
                    clean.add(nested);
                    copied++;
                }
            }
            return clean;
        }
        if (value.isJsonObject()) {
            JsonObject clean = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (internalAttentionKey(entry.getKey())) continue;
                JsonElement nested = sanitizeAttentionValue(entry.getValue());
                if (nested != null) clean.add(entry.getKey(), nested);
            }
            return clean;
        }
        if (value.getAsJsonPrimitive().isString()) {
            String sanitized = value.getAsString()
                    .replaceAll("(?i)entity\\s*#?\\s*\\d+", "selected entity")
                    .replaceAll("(?i)runtime\\s+id\\s*[:=]?\\s*\\d+", "internal target");
            if (sanitized.length() > MAX_ATTENTION_STRING) {
                sanitized = sanitized.substring(0, MAX_ATTENTION_STRING) + "...";
            }
            return new JsonPrimitive(sanitized);
        }
        return value.deepCopy();
    }

    private static boolean internalAttentionKey(String raw) {
        String key = raw.toLowerCase(Locale.ROOT);
        return ATTENTION_INTERNAL_KEYS.contains(key)
                || key.endsWith("_cells") || key.endsWith("_ops")
                || key.endsWith("_placements") || key.endsWith("_receipts")
                || key.endsWith("_routes") || key.endsWith("_waypoints")
                || key.endsWith("_path_nodes") || key.endsWith("_position")
                || key.endsWith("_center") || key.endsWith("_location")
                || key.endsWith("_destination") || key.endsWith("_bounds")
                || key.endsWith("_x") || key.endsWith("_y") || key.endsWith("_z")
                || key.endsWith("_entity_id")
                || key.endsWith("_entity_ids") || key.endsWith("_runtime_id")
                || key.endsWith("_runtime_ids");
    }

    private static String normalizeLabel(String label) {
        return label.strip().toLowerCase(Locale.ROOT);
    }

    private static void rejectMicroInstructions(Goal goal) {
        String forbidden = findMicroInstruction(BlueprintGoalData.instructionView(goal));
        if (forbidden != null) {
            throw new IllegalArgumentException(
                    "semantic goals cannot contain " + forbidden + "; describe the outcome instead");
        }
    }

    private static String findMicroInstruction(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return null;
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                String nested = findMicroInstruction(element);
                if (nested != null) return nested;
            }
            return null;
        }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (isMicroInstructionKey(key)) return entry.getKey();
            String nested = findMicroInstruction(entry.getValue());
            if (nested != null) return nested;
        }
        return null;
    }

    private static boolean isMicroInstructionKey(String key) {
        if (Set.of(
                "route", "waypoints", "path_nodes", "click", "clicks", "slot_clicks",
                "click_sequence", "inventory_slots", "block_ops", "placements", "cells",
                "blueprint", "blocks", "offset", "offsets", "block_states", "state_properties",
                "block_properties", "placement_face", "build_order", "action_sequence", "ops",
                "entity_id", "entity_ids", "entity_uuid", "entity_uuids", "runtime_id",
                "runtime_ids", "target_runtime_id", "target_runtime_ids", "receipt",
                "receipts").contains(key)) return true;
        return key.endsWith("_entity_id") || key.endsWith("_entity_ids")
                || key.endsWith("_entity_uuid") || key.endsWith("_entity_uuids")
                || key.endsWith("_runtime_id") || key.endsWith("_runtime_ids")
                || key.endsWith("_click") || key.endsWith("_clicks")
                || key.endsWith("_route") || key.endsWith("_waypoints")
                || key.endsWith("_path_nodes") || key.endsWith("_receipt")
                || key.endsWith("_receipts");
    }

    private void trimTasks() {
        if (tasks.size() <= MAX_TASKS) return;
        List<UUID> removable = tasks.entrySet().stream()
                .filter(entry -> entry.getValue().getState().isTerminal())
                .map(Map.Entry::getKey)
                .toList();
        for (UUID id : removable) {
            if (tasks.size() <= MAX_TASKS) break;
            tasks.remove(id);
            requestKeys.values().removeIf(id::equals);
        }
    }

    private static <K, V> void trimOldest(LinkedHashMap<K, V> map, int max) {
        while (map.size() > max) {
            K first = map.keySet().iterator().next();
            map.remove(first);
        }
    }

    /** Typed area meaning; human landmark labels are never interpreted as policy. */
    public enum LandmarkAreaRole {
        ORDINARY("ordinary"),
        MANAGED_SETTLEMENT("managed_settlement");

        private final String id;

        LandmarkAreaRole(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Missing, malformed and future values remain ordinary instead of granting protection. */
        public static LandmarkAreaRole fromPersisted(String value) {
            if (MANAGED_SETTLEMENT.id.equals(value)) return MANAGED_SETTLEMENT;
            return ORDINARY;
        }
    }

    public record Landmark(
            String label, Goal.WorldPosition position, LandmarkAreaRole areaRole) {
        public Landmark {
            areaRole = areaRole == null ? LandmarkAreaRole.ORDINARY : areaRole;
        }

        public Landmark(String label, Goal.WorldPosition position) {
            this(label, position, LandmarkAreaRole.ORDINARY);
        }
    }

}
