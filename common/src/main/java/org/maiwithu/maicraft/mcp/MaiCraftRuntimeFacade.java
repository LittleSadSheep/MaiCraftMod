package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticAbilityCatalog;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.intent.Plan;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** RuntimeFacade backed by the active LocalPlayer and the single Task scheduler. */
public final class MaiCraftRuntimeFacade implements RuntimeFacade {

    private static final MaiCraftRuntimeFacade INSTANCE = new MaiCraftRuntimeFacade();

    private final IntentRuntime intents;

    private MaiCraftRuntimeFacade() {
        this.intents = IntentRuntime.get();
    }

    /** Stable loader entry point; construction also idempotently registers IntentTaskRecord. */
    public static MaiCraftRuntimeFacade instance() {
        return INSTANCE;
    }

    @Override
    public CompletionStage<JsonElement> perceive(JsonObject arguments) {
        if ("attention".equals(arguments.get("view").getAsString())
                && arguments.get("wait_ms").getAsInt() > 0) {
            return waitForAttention(arguments);
        }
        return onClient(() -> perceiveOnClient(arguments));
    }

    @Override
    public CompletionStage<JsonElement> plan(JsonObject arguments) {
        return onClient(() -> {
            Minecraft minecraft = requireWorld();
            intents.bindForRequest(minecraft, minecraft.player);
            Goal goal = Goal.fromJson(arguments.getAsJsonObject("goal"));
            Plan plan = intents.compile(goal, minecraft.level.getGameTime());
            JsonObject result = plan.toJson();
            result.addProperty("status", "compiled");
            return result;
        });
    }

    @Override
    public CompletionStage<JsonElement> execute(JsonObject arguments) {
        return onClient(() -> {
            Minecraft minecraft = requireWorld();
            LocalPlayer player = minecraft.player;
            intents.bindForRequest(minecraft, player);
            Goal goal;
            UUID planId = null;
            if (arguments.has("plan_id") && !arguments.get("plan_id").isJsonNull()) {
                planId = UUID.fromString(arguments.get("plan_id").getAsString());
                Plan plan = intents.plan(planId);
                if (plan == null) throw new IllegalArgumentException("unknown or expired plan_id: " + planId);
                goal = plan.goal();
            } else {
                goal = Goal.fromJson(arguments.getAsJsonObject("goal"));
            }
            String requestKey = nullableString(arguments, "request_key");
            ClientActorBoundary.AutomationRequest control =
                    ClientRuntime.requestAutomationControl(player);
            IntentTaskRecord record;
            try {
                record = intents.execute(player, goal, planId, requestKey);
            } catch (RuntimeException failure) {
                ClientRuntime.rollbackAutomationControl(control);
                throw failure;
            }
            JsonObject result = new JsonObject();
            result.addProperty("task_id", record.externalId().toString());
            result.addProperty("status", publicState(record));
            result.addProperty("accepted", true);
            result.addProperty("outcome", record.goal().outcome());
            if (requestKey != null) result.addProperty("request_key", requestKey);
            result.addProperty("control_status", "takeover_requested");
            return result;
        });
    }

    @Override
    public CompletionStage<JsonElement> task(JsonObject arguments) {
        return onClient(() -> taskOnClient(arguments));
    }

    @Override
    public CompletionStage<JsonElement> readAttention() {
        return onClient(() -> {
            Minecraft minecraft = requireWorld();
            intents.bindForRequest(minecraft, minecraft.player);
            return intents.attention(0, 20);
        });
    }

    @Override
    public AutoCloseable subscribeAttention(Consumer<JsonElement> listener) {
        return intents.subscribeAttention(listener);
    }

    private JsonElement perceiveOnClient(JsonObject arguments) {
        Minecraft minecraft = requireWorld();
        LocalPlayer player = minecraft.player;
        intents.bindForRequest(minecraft, player);
        String view = arguments.get("view").getAsString();
        return switch (view) {
            case "situation" -> {
                JsonObject situation = situation(player);
                if ("maicraft:navigation".equals(nullableString(arguments, "focus"))) {
                    var gson = new com.google.gson.Gson();
                    situation.add("actor", gson.toJsonTree(ClientRuntime.actor().diagnosticState()));
                    situation.addProperty("tick_stage", ClientRuntime.lastTickStage());
                    situation.addProperty("controlling_task", CompanionTickDispatcher.controllingTask());
                    situation.add("navigation", gson.toJsonTree(
                            org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime.diagnosticState()));
                    situation.add("collision_geometry", NearbyCollisionPerception.observe(player));
                }
                yield situation;
            }
            case "surroundings" -> surroundings(player, nullableString(arguments, "focus"),
                    arguments.has("limit") ? arguments.get("limit").getAsInt() : 16);
            case "abilities" -> abilities(nullableString(arguments, "focus"));
            case "tasks" -> {
                String rawTaskId = nullableString(arguments, "task_id");
                if (rawTaskId != null) {
                    yield taskSnapshot(requireTask(UUID.fromString(rawTaskId)));
                }
                JsonArray tasks = new JsonArray();
                for (IntentTaskRecord record : intents.tasks(arguments.get("limit").getAsInt())) {
                    tasks.add(taskSummary(record));
                }
                JsonObject result = new JsonObject();
                result.add("tasks", tasks);
                yield result;
            }
            case "attention" -> intents.attention(
                    arguments.get("after_cursor").getAsLong(),
                    arguments.get("limit").getAsInt());
            case "landmarks" -> landmarks(player);
            case "machines" -> org.maiwithu.maicraft.core.integration.machine.MachineSnapshots.summaries(player);
            case "machine_menu" -> org.maiwithu.maicraft.core.integration.machine.MachineMenu.inspect(player);
            default -> throw new IllegalArgumentException("unknown perceive view: " + view);
        };
    }

    private JsonElement taskOnClient(JsonObject arguments) {
        Minecraft minecraft = requireWorld();
        LocalPlayer player = minecraft.player;
        intents.bindForRequest(minecraft, player);
        String action = arguments.get("action").getAsString();
        if ("list".equals(action)) {
            JsonArray tasks = new JsonArray();
            String requestKey = nullableString(arguments, "request_key");
            if (requestKey != null) {
                IntentTaskRecord record = intents.taskForRequestKey(requestKey);
                if (record != null) tasks.add(taskSnapshot(record));
            } else {
                for (IntentTaskRecord record : intents.tasks(arguments.get("limit").getAsInt())) {
                    tasks.add(taskSnapshot(record));
                }
            }
            JsonObject result = new JsonObject();
            if (requestKey != null) result.addProperty("request_key", requestKey);
            result.add("tasks", tasks);
            return result;
        }

        UUID id = UUID.fromString(arguments.get("task_id").getAsString());
        IntentTaskRecord record = requireTask(id);
        long now = Minecraft.getInstance().level.getGameTime();
        switch (action) {
            case "get" -> {
            }
            case "pause" -> {
                if (!record.pause(now, "paused_by_mcp")) {
                    throw new IllegalStateException("task is already terminal");
                }
                intents.paused(record, "Paused by MCP request");
            }
            case "resume" -> {
                intents.requireCurrentBinding(record);
                boolean restoredDetached = record.restoredDetached();
                if (!record.resume()) {
                    throw new IllegalStateException(record.decisionSnapshot() != null
                            ? "task needs maicraft_task action=answer"
                            : "task cannot be resumed");
                }
                if (restoredDetached) {
                    if (CompanionTickDispatcher.find(record.publicId()) != record) {
                        CompanionTickDispatcher.submitCurrent(player, record);
                    }
                    intents.restoredTaskAttached(record);
                }
                intents.resumed(record);
            }
            case "cancel" -> {
                if (record.getState().isTerminal()) {
                    throw new IllegalStateException("task is already terminal");
                }
                if (!CompanionTickDispatcher.cancel(record.publicId())) {
                    throw new IllegalStateException("task is no longer in the active scheduler slot");
                }
            }
            case "answer" -> {
                if (record.restoredDetached()) {
                    intents.requireCurrentBinding(record);
                }
                JsonObject answer = arguments.getAsJsonObject("answer");
                UUID decisionId = UUID.fromString(answer.get("decision_id").getAsString());
                String choice = answer.get("choice").getAsString();
                IntentTaskRecord.DecisionSnapshot pendingDecision = record.decisionSnapshot();
                GameplayAttentionMonitor.DeathDecisionEffect deathEffect =
                        GameplayAttentionMonitor.classifyDeathDecision(
                                player, pendingDecision, choice);
                boolean deathDecision = pendingDecision != null
                        && "death_recovery".equals(nullableString(
                                pendingDecision.context(), "decision_kind"));
                if (deathDecision && ("respawn".equals(choice) || "spectate".equals(choice))
                        && deathEffect == GameplayAttentionMonitor.DeathDecisionEffect.NONE) {
                    throw new IllegalStateException(
                            "the native death action is currently unavailable; the decision remains pending");
                }
                JsonObject details = answer.has("details") && answer.get("details").isJsonObject()
                        ? answer.getAsJsonObject("details")
                        : new JsonObject();
                intents.validateDecisionAnswer(record, choice, details);
                if (!record.answer(decisionId, choice, details)) {
                    throw new IllegalArgumentException("decision_id or choice does not match the pending decision");
                }
                if (record.restoredDetached()) {
                    if (CompanionTickDispatcher.find(record.publicId()) != record) {
                        CompanionTickDispatcher.submitCurrent(player, record);
                    }
                    intents.restoredTaskAttached(record);
                }
                if (deathEffect == GameplayAttentionMonitor.DeathDecisionEffect.NONE) {
                    intents.resumed(record);
                } else {
                    GameplayAttentionMonitor.applyDeathDecision(deathEffect, player, record);
                }
            }
            default -> throw new IllegalArgumentException("unknown task action: " + action);
        }
        return taskSnapshot(record);
    }

    private JsonObject situation(LocalPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("dimension", player.level().dimension().location().toString());
        result.add("position", position(player));
        result.addProperty("health", player.getHealth());
        result.addProperty("max_health", player.getMaxHealth());
        result.addProperty("food", player.getFoodData().getFoodLevel());
        result.addProperty("air", player.getAirSupply());
        result.addProperty("in_water", player.isInWater());
        result.addProperty("underwater", player.isEyeInFluid(FluidTags.WATER));
        result.addProperty("swimming", player.isSwimming());
        result.addProperty("sprinting", player.isSprinting());
        WorldTimeSemantics.Phase timePhase = WorldTimeSemantics.phase(player.level());
        result.addProperty("day", WorldTimeSemantics.isDaytime(player.level()));
        result.addProperty("is_daytime", WorldTimeSemantics.isDaytime(player.level()));
        result.addProperty("time_phase", timePhase.id());
        result.addProperty("time_of_day", WorldTimeSemantics.timeOfDay(player.level()));
        result.addProperty("day_index", WorldTimeSemantics.dayIndex(player.level()));
        result.addProperty("weather", player.level().isThundering()
                ? "thunder" : player.level().isRaining() ? "rain" : "clear");
        result.addProperty("game_time", player.level().getGameTime());
        result.add("inventory", inventorySummary(player));
        result.add("equipment", equipmentSummary(player));
        if (player.getVehicle() != null) {
            result.addProperty("vehicle_type", BuiltInRegistries.ENTITY_TYPE
                    .getKey(player.getVehicle().getType()).toString());
        }
        IntentTaskRecord current = currentIntent();
        if (current != null) result.add("task", taskSummary(current));
        return result;
    }

    private JsonObject surroundings(LocalPlayer player, String focus, int limit) {
        JsonObject result = new JsonObject();
        result.add("position", position(player));
        result.addProperty("dimension", player.level().dimension().location().toString());
        result.addProperty("sky_light", player.level().getMaxLocalRawBrightness(player.blockPosition()));
        player.level().getBiome(player.blockPosition()).unwrapKey()
                .ifPresent(key -> result.addProperty("biome", key.location().toString()));

        JsonArray entities = new JsonArray();
        AABB area = player.getBoundingBox().inflate(16.0);
        List<Entity> nearby = player.level().getEntities(player, area, entity -> true)
                .stream().sorted(java.util.Comparator.comparingDouble(player::distanceToSqr)).toList();
        int hostileCount = (int) nearby.stream().filter(entity -> entity instanceof Enemy).count();
        for (Entity entity : nearby.stream().limit(16).toList()) {
            JsonObject item = new JsonObject();
            item.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            item.addProperty("distance", Math.round(player.distanceTo(entity) * 10.0) / 10.0);
            entities.add(item);
        }
        result.add("nearby_entities", entities);
        JsonObject signs = NearbySignPerception.observe(player, focus, limit);
        result.add("nearby_signs", signs.remove("signs"));
        result.add("sign_observation", signs);
        result.add("local_decision_summary", localDecisionSummary(player, hostileCount));
        return result;
    }

    /**
     * Aggregate local geometry into decision references. This deliberately
     * reports regions and risks, not a raw block dump.
     */
    private JsonObject localDecisionSummary(LocalPlayer player, int hostileCount) {
        JsonObject summary = org.maiwithu.maicraft.core.tools.perception.LocalFloorSense.describe(player);
        if (hostileCount > 0) {
            JsonObject hostile = new JsonObject();
            hostile.addProperty("kind", "hostile_entities");
            hostile.addProperty("samples", hostileCount);
            summary.getAsJsonArray("hazards").add(hostile);
        }
        return summary;
    }
    private JsonObject abilities(String focus) {
        JsonArray abilities = new JsonArray();
        for (String ability : IntentRuntime.KNOWN_ABILITIES.stream().sorted().toList()) {
            if (focus != null && !focus.equals(ability)) continue;
            JsonObject item = new JsonObject();
            item.addProperty("ability", ability);
            item.addProperty("available", true);
            item.addProperty("mode", abilityMode(ability));
            item.add("contract", SemanticAbilityCatalog.describe(ability));
            abilities.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("semantic_abilities", abilities);
        result.addProperty(
                "boundary",
                "Use fields declared by each ability. Machine design may include components, intended connections, style, constraints and observed menu references. MaiCraft owns exact layouts, states, routes, gestures, retries and confirmation; never submit per-block blueprints or click scripts.");
        return result;
    }

    private JsonObject landmarks(LocalPlayer player) {
        JsonArray entries = new JsonArray();
        String currentDimension = player.level().dimension().location().toString();
        for (IntentRuntime.Landmark landmark : intents.landmarks()) {
            JsonObject item = new JsonObject();
            item.addProperty("label", landmark.label());
            item.addProperty("area_role", landmark.areaRole().id());
            String dimension = landmark.position().dimension();
            if (dimension != null && !dimension.isBlank()) {
                item.addProperty("dimension", dimension);
            }
            item.addProperty("available_here",
                    dimension == null || dimension.isBlank()
                            || dimension.equals(currentDimension));
            entries.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("landmarks", entries);
        result.addProperty("location_boundary",
                "Use landmark labels as semantic targets; exact stored coordinates remain inside MaiCraft.");
        return result;
    }

    private JsonObject taskSnapshot(IntentTaskRecord record) {
        JsonObject result = new JsonObject();
        result.addProperty("task_id", record.externalId().toString());
        if (record.planId() != null) result.addProperty("plan_id", record.planId().toString());
        result.addProperty("state", publicState(record));
        result.addProperty("internal_state", record.getState().name().toLowerCase());
        result.addProperty("step_index", record.stepIndex());
        result.addProperty("step_count", record.steps().size());
        result.add("goal", record.goal().toJson());

        JsonArray steps = new JsonArray();
        for (IntentTaskRecord.StepSnapshot step : record.stepResults()) {
            JsonObject item = new JsonObject();
            item.addProperty("index", step.index());
            item.addProperty("ability", step.ability());
            item.addProperty("success", step.success());
            item.addProperty("message", step.message());
            item.add("result", step.result());
            steps.add(item);
        }
        result.add("completed_steps", steps);

        JsonArray attempts = new JsonArray();
        for (IntentTaskRecord.AttemptSnapshot attempt : record.attempts()) {
            JsonObject item = new JsonObject();
            item.addProperty("step_index", attempt.stepIndex());
            item.addProperty("ability", attempt.goal().ability());
            item.addProperty("outcome", attempt.goal().outcome());
            item.addProperty("state", attempt.state().name().toLowerCase());
            item.addProperty("message", attempt.message());
            item.addProperty("game_time", attempt.gameTime());
            item.add("result", attempt.result());
            attempts.add(item);
        }
        result.add("attempts", attempts);

        if (record.pauseSnapshot() != null) {
            JsonObject pause = new JsonObject();
            pause.addProperty("reason", record.pauseSnapshot().reason());
            pause.addProperty("game_time", record.pauseSnapshot().gameTime());
            result.add("pause", pause);
        }
        if (record.decisionSnapshot() != null) {
            JsonObject decision = decision(record.decisionSnapshot());
            result.add("decision", decision);
            copyEffectLedger(result, decision.getAsJsonObject("context"));
        }
        if (record.terminalSnapshot() != null) {
            JsonObject terminal = new JsonObject();
            terminal.addProperty("state", record.terminalSnapshot().state().name().toLowerCase());
            terminal.addProperty("game_time", record.terminalSnapshot().gameTime());
            terminal.add("result", record.terminalSnapshot().result());
            result.add("terminal", terminal);
        }
        return result;
    }

    /** Surface the current failed-step ledger directly while retaining it in decision.context. */
    private static void copyEffectLedger(JsonObject target, JsonObject decisionContext) {
        if (decisionContext == null || !decisionContext.has("failure")
                || !decisionContext.get("failure").isJsonObject()) return;
        JsonObject failure = decisionContext.getAsJsonObject("failure");
        if (!failure.has("data") || !failure.get("data").isJsonObject()) return;
        JsonObject data = failure.getAsJsonObject("data");
        for (String key : List.of("completed_effects", "remaining_effects")) {
            if (data.has(key) && data.get(key).isJsonArray()) {
                target.add(key, data.get(key).deepCopy());
            }
        }
    }

    private static JsonObject taskSummary(IntentTaskRecord record) {
        JsonObject result = new JsonObject();
        result.addProperty("task_id", record.externalId().toString());
        result.addProperty("state", publicState(record));
        result.addProperty("outcome", record.goal().outcome());
        result.addProperty("step_index", record.stepIndex());
        result.addProperty("step_count", record.steps().size());
        return result;
    }

    private static JsonObject decision(IntentTaskRecord.DecisionSnapshot decision) {
        JsonObject result = new JsonObject();
        result.addProperty("decision_id", decision.id().toString());
        result.addProperty("question", decision.question());
        JsonArray options = new JsonArray();
        for (IntentTaskRecord.DecisionOption option : decision.options()) {
            JsonObject item = new JsonObject();
            item.addProperty("choice", option.choice());
            item.addProperty("description", option.description());
            options.add(item);
        }
        result.add("options", options);
        result.add("context", decision.context());
        return result;
    }

    private static JsonArray inventorySummary(LocalPlayer player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(id, stack.getCount(), Integer::sum);
        }
        JsonArray result = new JsonArray();
        counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("item_id", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    result.add(item);
                });
        return result;
    }

    private static JsonObject equipmentSummary(LocalPlayer player) {
        JsonObject result = new JsonObject();
        addStack(result, "main_hand", player.getMainHandItem());
        addStack(result, "off_hand", player.getOffhandItem());
        String[] armorSlots = {"feet", "legs", "chest", "head"};
        for (int index = 0;
             index < Math.min(armorSlots.length, player.getInventory().armor.size());
             index++) {
            addStack(result, armorSlots[index], player.getInventory().armor.get(index));
        }
        return result;
    }

    private static void addStack(JsonObject target, String key, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        JsonObject value = new JsonObject();
        value.addProperty("item_id",
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        value.addProperty("count", stack.getCount());
        value.addProperty("damage", stack.getDamageValue());
        value.addProperty("max_damage", stack.getMaxDamage());
        target.add(key, value);
    }

    private static JsonObject position(LocalPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("x", player.getX());
        result.addProperty("y", player.getY());
        result.addProperty("z", player.getZ());
        return result;
    }

    private IntentTaskRecord currentIntent() {
        return CompanionTickDispatcher.current() instanceof IntentTaskRecord record ? record : null;
    }

    private IntentTaskRecord requireTask(UUID id) {
        IntentTaskRecord record = intents.task(id);
        if (record == null) throw new IllegalArgumentException("unknown task_id: " + id);
        return record;
    }

    private static String publicState(IntentTaskRecord record) {
        if (record.decisionSnapshot() != null) return "waiting_for_decision";
        if (record.pauseSnapshot() != null) return "paused";
        return record.getState().name().toLowerCase();
    }

    private static String abilityMode(String ability) {
        return switch (ability) {
            case "maicraft:remember_place" -> "local_memory";
            case "maicraft:inspect_machine", "maicraft:design_machine" -> "bounded_machine_evidence_review";
            case "maicraft:wait_for_condition" -> "client_clock_or_predicate";
            case "maicraft:sequence" -> "sequential_children";
            default -> "compiled_to_internal_task";
        };
    }

    private static String nullableString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private static Minecraft requireWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            throw new IllegalStateException("no active local player world");
        }
        return minecraft;
    }

    /**
     * Wait for semantic attention without holding the client thread. The listener
     * only snapshots the in-memory feed and completes a future; transport I/O is
     * owned by the MCP handler. Resource notifications remain advisory and never
     * initiate model sampling from inside the Mod.
     */
    private CompletionStage<JsonElement> waitForAttention(JsonObject arguments) {
        long afterCursor = arguments.get("after_cursor").getAsLong();
        int limit = arguments.get("limit").getAsInt();
        int waitMs = arguments.get("wait_ms").getAsInt();
        ClientCallFuture future = new ClientCallFuture();
        AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
        future.whenComplete((ignored, failure) -> closeQuietly(subscription.getAndSet(null)));

        Runnable setup = () -> {
            if (!future.begin()) return;
            try {
                Minecraft minecraft = requireWorld();
                intents.bindForRequest(minecraft, minecraft.player);
                JsonObject initial = intents.attention(afterCursor, limit);
                if (hasAttentionEvents(initial)) {
                    future.completeCall(initial);
                    return;
                }

                Consumer<JsonElement> listener = ignored -> {
                    if (future.isDone()) return;
                    JsonObject snapshot = intents.attention(afterCursor, limit);
                    if (hasAttentionEvents(snapshot)) future.completeCall(snapshot);
                };
                AutoCloseable handle = intents.subscribeAttention(listener);
                subscription.set(handle);
                if (future.isDone()) {
                    closeQuietly(subscription.getAndSet(null));
                    return;
                }

                // Subscribe before the second read so an event can land on neither side
                // of the check only if the listener itself already completed the future.
                JsonObject raced = intents.attention(afterCursor, limit);
                if (hasAttentionEvents(raced)) {
                    future.completeCall(raced);
                    return;
                }
                if (!future.markWaiting()) return;
                JsonObject timeoutSnapshot = raced.deepCopy();
                CompletableFuture.delayedExecutor(waitMs, TimeUnit.MILLISECONDS)
                        .execute(() -> future.completeCall(timeoutSnapshot));
            } catch (Throwable failure) {
                future.failCall(failure);
            }
        };
        scheduleClient(future, setup);
        return future;
    }

    private static boolean hasAttentionEvents(JsonObject snapshot) {
        return snapshot.has("events") && snapshot.get("events").isJsonArray()
                && snapshot.getAsJsonArray("events").size() > 0;
    }

    private static CompletionStage<JsonElement> onClient(Supplier<? extends JsonElement> operation) {
        ClientCallFuture future = new ClientCallFuture();
        Runnable work = () -> {
            if (!future.begin()) return;
            try {
                future.completeCall(operation.get());
            } catch (Throwable throwable) {
                future.failCall(throwable);
            }
        };
        scheduleClient(future, work);
        return future;
    }

    private static void scheduleClient(ClientCallFuture future, Runnable work) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (minecraft.isSameThread()) work.run(); else minecraft.execute(work);
        } catch (Throwable failure) {
            future.rejectBeforeStart(failure);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static final class ClientCallFuture extends CompletableFuture<JsonElement>
            implements RuntimeFacade.ManagedCall {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int WAITING = 2;
        private static final int SETTLED = 3;
        private static final int CANCELLED = 4;

        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private boolean begin() {
            return state.compareAndSet(QUEUED, RUNNING);
        }

        private boolean markWaiting() {
            return state.compareAndSet(RUNNING, WAITING);
        }

        private boolean completeCall(JsonElement value) {
            if (!settle()) return false;
            return super.complete(value);
        }

        private boolean failCall(Throwable failure) {
            if (!settle()) return false;
            return super.completeExceptionally(failure);
        }

        private void rejectBeforeStart(Throwable failure) {
            if (state.compareAndSet(QUEUED, SETTLED)) {
                super.completeExceptionally(failure);
            }
        }

        private boolean settle() {
            while (true) {
                int current = state.get();
                if (current != RUNNING && current != WAITING) return false;
                if (state.compareAndSet(current, SETTLED)) return true;
            }
        }

        @Override
        public RuntimeFacade.CancellationDisposition cancelCall() {
            while (true) {
                int current = state.get();
                if (current == QUEUED && state.compareAndSet(QUEUED, CANCELLED)) {
                    super.cancel(false);
                    return RuntimeFacade.CancellationDisposition.CANCELLED_BEFORE_START;
                }
                if (current == WAITING && state.compareAndSet(WAITING, CANCELLED)) {
                    super.cancel(false);
                    return RuntimeFacade.CancellationDisposition.CANCELLED_WHILE_WAITING;
                }
                if (current == RUNNING) {
                    return RuntimeFacade.CancellationDisposition.ALREADY_STARTED;
                }
                if (current == SETTLED || current == CANCELLED) {
                    return RuntimeFacade.CancellationDisposition.SETTLED;
                }
            }
        }
    }

}
