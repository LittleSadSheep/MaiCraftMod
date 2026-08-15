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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
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
            case "situation" -> situation(player);
            case "surroundings" -> surroundings(player);
            case "abilities" -> abilities(nullableString(arguments, "focus"));
            case "tasks" -> {
                UUID taskId = UUID.fromString(arguments.get("task_id").getAsString());
                IntentTaskRecord record = requireTask(taskId);
                yield taskSnapshot(record);
            }
            case "attention" -> intents.attention(
                    arguments.get("after_cursor").getAsLong(),
                    arguments.get("limit").getAsInt());
            case "landmarks" -> landmarks();
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
        result.addProperty("day", player.level().isDay());
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

    private JsonObject surroundings(LocalPlayer player) {
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
