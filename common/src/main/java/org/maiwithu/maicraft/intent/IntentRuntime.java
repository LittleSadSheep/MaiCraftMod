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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.io.IOException;

/**
 * Client-thread semantic state around the one Task scheduler.
 *
 * <p>The maps retain plans, public ids and read models only. All physical
 * advancement remains in {@link CompanionTickDispatcher}.</p>
 */
public final class IntentRuntime {

    private static final int MAX_PLANS = 128;
    private static final int MAX_TASKS = 256;
    private static final int MAX_ATTENTION = 256;
    private static final int MAX_REQUEST_KEYS = 512;
    private static final int MAX_LANDMARKS = 256;
    private static final long SAVE_INTERVAL_NANOS = 5_000_000_000L;
    private static final Set<String> ATTENTION_INTERNAL_KEYS = Set.of(
            "entity_id", "entity_ids", "runtime_id", "runtime_ids",
            "slot", "slots", "click", "clicks", "route", "waypoints",
            "path_nodes", "block_ops", "placements", "cells");

    private static final Set<String> CORE_ABILITIES = Set.of(
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
            "maicraft:light_area",
            "maicraft:connect_mechanical_power",
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
        validateGoal(goal);
        if (requestKey != null && !requestKey.isBlank()) {
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
        String key = normalizeLabel(label);
        landmarks.put(key, new Landmark(label, position));
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
            if (bodyAttached) saveNow(true);
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
        if (dirty && System.nanoTime() >= nextSaveNanos) saveNow(false);
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
     * body detached after a successful save prevents cancellation cleanup from overwriting the
     * resumable semantic snapshot; the normal post-scheduler bind then clears the old read model.
     */
    public void beforeBodyTick(Minecraft minecraft) {
        StateIdentity next = StateIdentity.resolve(minecraft).orElse(null);
        if (next != null && stateIdentity != null && bodyAttached
                && !stateIdentity.key().equals(next.key()) && saveNow(true)) {
            bodyAttached = false;
        }
    }

    /** Save before the body scheduler cancels records during disconnect or replacement. */
    public void bodyUnavailable() {
        saveNow(true);
        bodyAttached = false;
    }

    /** Persist the exact semantic checkpoint while the death-screen body is still addressable. */
    public boolean checkpointDeath() {
        return saveNow(true);
    }

    /**
     * Persist and detach before a native respawn replaces LocalPlayer in the same world identity.
     * The next normal persistence bind restores the non-terminal task in a paused state.
     */
    public boolean prepareRespawnHandoff() {
        boolean saved = saveNow(true);
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
        saveNow(true);
    }

    /** Loader shutdown hook: a synchronous best-effort forced save, never a background write. */
