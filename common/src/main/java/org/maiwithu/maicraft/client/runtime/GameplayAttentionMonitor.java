// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * Reduces noisy client state into decision-relevant MCP attention events.
 *
 * <p>This sampler never asks the LLM to poll raw blocks. It reports transitions only. A hit from
 * another player is deliberately treated as an ambiguous social signal: the semantic task pauses
 * and the LLM may narrate or ask what the player meant; the Mod does not infer retaliation or
 * following.</p>
 */
public final class GameplayAttentionMonitor {

    private static final int MAX_CHAT_CHARS = 512;
    private static final int MAX_CHAT_EVENTS_PER_WINDOW = 8;
    private static final int MAX_CHAT_FINGERPRINTS = 64;
    private static final long CHAT_WINDOW_NANOS = 10_000_000_000L;
    private static final long CHAT_DUPLICATE_NANOS = 5_000_000_000L;
    private static final float HEALTH_EPSILON = 0.001F;

    private static final ArrayDeque<Long> recentChatEvents = new ArrayDeque<>();
    private static final LinkedHashMap<String, Long> recentChatFingerprints = new LinkedHashMap<>();
    private static final Map<String, ReflexEpisode> activeReflexes = new HashMap<>();

    private static ClientLevel previousLevel;
    private static String previousDimension;
    private static String previousTimePhase;
    private static String previousWeather;
    private static float previousEffectiveHealth = Float.NaN;
    private static int previousHurtByMobTimestamp;
    private static int suppressedChatMessages;
    private static LifeState lifeState = LifeState.ALIVE;
    private static DeathSnapshot lastDeath;

    private GameplayAttentionMonitor() {}

    /** Sample once per END_CLIENT_TICK before the task scheduler chooses its body owner. */
    public static void tick(LocalPlayer player) {
        ClientLevel level = player.clientLevel;
        String dimension = level.dimension().location().toString();
        String phase = WorldTimeSemantics.phase(level).id();
        String weather = weather(level);
        float effectiveHealth = player.getHealth() + player.getAbsorptionAmount();
        int hurtByMobTimestamp = player.getLastHurtByMobTimestamp();
        boolean dead = player.isDeadOrDying() || player.getHealth() <= 0.0F;

        if (dead) {
            if (lifeState == LifeState.ALIVE || lifeState == LifeState.RESPAWN_OBSERVED) {
                deathDetected(player);
            }
            remember(level, dimension, phase, weather, effectiveHealth, hurtByMobTimestamp);
            return;
        }

        if (lifeState == LifeState.DEAD_REPORTED || lifeState == LifeState.RESPAWN_REQUESTED) {
            // This is a fresh LocalPlayer body. Arm same-identity restore before
            // CompanionTickDispatcher observes and retires the dead body.
            IntentRuntime.get().prepareRespawnHandoff();
            lifeState = LifeState.RESPAWN_OBSERVED;
            remember(level, dimension, phase, weather, effectiveHealth, hurtByMobTimestamp);
            return;
        }

        if (previousLevel == null) {
            remember(level, dimension, phase, weather, effectiveHealth, hurtByMobTimestamp);
            return;
        }

        if (previousLevel != level || !dimension.equals(previousDimension)) {
            JsonObject data = new JsonObject();
            data.addProperty("from_dimension", previousDimension);
            data.addProperty("to_dimension", dimension);
            data.add("position", position(player));
            publish("agent.dimension_changed", "Entered dimension " + dimension, data);
            remember(level, dimension, phase, weather, effectiveHealth, hurtByMobTimestamp);
            return;
        }

        if (!phase.equals(previousTimePhase)) {
            JsonObject data = new JsonObject();
            data.addProperty("phase", phase);
            data.addProperty("day", WorldTimeSemantics.dayIndex(level));
            data.addProperty("time_of_day", WorldTimeSemantics.timeOfDay(level));
            publish("world.time_phase_changed", "Time phase changed to " + phase, data);
        }
        if (!weather.equals(previousWeather)) {
            JsonObject data = new JsonObject();
            data.addProperty("weather", weather);
            publish("world.weather_changed", "Weather changed to " + weather, data);
        }
        if (effectiveHealth + HEALTH_EPSILON < previousEffectiveHealth) {
            damaged(player, previousEffectiveHealth, effectiveHealth,
                    hurtByMobTimestamp != previousHurtByMobTimestamp);
        }

        remember(level, dimension, phase, weather, effectiveHealth, hurtByMobTimestamp);
    }

    /** Loader chat hooks call this with plain text; component trees are intentionally discarded. */
    public static synchronized void chat(
            String senderName, UUID senderId, String message, boolean system) {
        if (message == null) return;
        String text = message.strip();
        if (text.isEmpty()) return;
        if (text.length() > MAX_CHAT_CHARS) text = text.substring(0, MAX_CHAT_CHARS);

        long now = System.nanoTime();
        while (!recentChatEvents.isEmpty()
                && now - recentChatEvents.peekFirst() >= CHAT_WINDOW_NANOS) {
            recentChatEvents.removeFirst();
        }
        recentChatFingerprints.entrySet().removeIf(
                entry -> now - entry.getValue() >= CHAT_DUPLICATE_NANOS);
        String safeSender = safeText(senderName, 64);
        String fingerprint = system + "\n" + safeSender + "\n" + text;
        if (recentChatEvents.size() >= MAX_CHAT_EVENTS_PER_WINDOW
                || recentChatFingerprints.containsKey(fingerprint)) {
            suppressedChatMessages++;
            return;
        }
        recentChatEvents.addLast(now);
        recentChatFingerprints.put(fingerprint, now);
        while (recentChatFingerprints.size() > MAX_CHAT_FINGERPRINTS) {
            recentChatFingerprints.remove(recentChatFingerprints.keySet().iterator().next());
        }

        JsonObject data = new JsonObject();
        if (!safeSender.isEmpty()) data.addProperty("sender_name", safeSender);
        data.addProperty("message", text);
        data.addProperty("system", system);
        data.addProperty("untrusted_external_text", true);
        if (suppressedChatMessages > 0) {
            data.addProperty("suppressed_similar_or_rate_limited_messages", suppressedChatMessages);
            suppressedChatMessages = 0;
        }
        publish(
                system ? "game.message_received" : "player.chat_received",
                system
                        ? "Received an untrusted external game message."
                        : "Received untrusted external player chat.",
                data);
    }

    public static synchronized void reset() {
        reset(false);
    }

    public static synchronized void reset(boolean preserveDeathRecovery) {
        previousLevel = null;
        previousDimension = null;
        previousTimePhase = null;
        previousWeather = null;
        previousEffectiveHealth = Float.NaN;
        previousHurtByMobTimestamp = 0;
        recentChatEvents.clear();
        recentChatFingerprints.clear();
        activeReflexes.clear();
        suppressedChatMessages = 0;
        if (!preserveDeathRecovery || lifeState == LifeState.ALIVE) {
            lifeState = LifeState.ALIVE;
            lastDeath = null;
        }
    }

    /** Report a safety reflex once, without exposing movement, target or inventory internals. */
    public static synchronized void reflexStarted(
            String reflex, String reason, String actionCategory,
            String consumptionRisk, String damageRisk) {
        String key = safeText(reflex, 48);
        if (key.isEmpty() || activeReflexes.containsKey(key)) return;
        activeReflexes.put(key, new ReflexEpisode(System.nanoTime()));
        JsonObject data = reflexEnvelope(key, "started");
        data.addProperty("reason", safeText(reason, 160));
        data.addProperty("action_category", safeText(actionCategory, 64));
        data.addProperty("consumption_risk", safeText(consumptionRisk, 128));
        data.addProperty("damage_risk", safeText(damageRisk, 128));
        data.addProperty("emergency_override", true);
        data.addProperty("requires_prior_approval", false);
        publish("agent.reflex", "An emergency reflex took control.", data);
    }

    public static synchronized void reflexEscalated(
            String reflex, String reason, String riskSummary) {
        String key = safeText(reflex, 48);
        ReflexEpisode episode = activeReflexes.get(key);
        if (episode == null || episode.escalated) return;
        episode.escalated = true;
        JsonObject data = reflexEnvelope(key, "escalated");
        data.addProperty("reason", safeText(reason, 160));
        data.addProperty("risk_summary", safeText(riskSummary, 160));
        publish("agent.reflex", "An emergency reflex escalated.", data);
    }

    public static synchronized void reflexFinished(
            String reflex, String outcome, int actionCount,
            String resourceEffect, String damageEffect) {
        String key = safeText(reflex, 48);
        ReflexEpisode episode = activeReflexes.remove(key);
        if (episode == null) return;
        JsonObject data = reflexEnvelope(key, "finished");
        data.addProperty("outcome", safeText(outcome, 96));
        data.addProperty("action_count", Math.max(0, actionCount));
        data.addProperty("resource_effect", safeText(resourceEffect, 128));
        data.addProperty("damage_effect", safeText(damageEffect, 128));
        data.addProperty("elapsed_ms", Math.max(0L,
                (System.nanoTime() - episode.startedNanos) / 1_000_000L));
        publish("agent.reflex", "An emergency reflex finished.", data);
    }

    /** Dead and just-rebound bodies may be observed, but semantic work must not advance yet. */
    public static synchronized boolean blocksAutomation(LocalPlayer player) {
        return player == null || player.isDeadOrDying() || player.getHealth() <= 0.0F
                || lifeState != LifeState.ALIVE;
    }

    /** Publish the conservative post-respawn checkpoint after IntentRuntime has restored it. */
    public static synchronized void afterSemanticBind(LocalPlayer player) {
        if (player == null || lifeState != LifeState.RESPAWN_OBSERVED || lastDeath == null) return;
        Map<String, Integer> currentInventory = inventoryCounts(player);
        IntentTaskRecord restored = lastDeath.taskId() == null
                ? null : IntentRuntime.get().task(lastDeath.taskId());
        JsonObject data = new JsonObject();
        data.addProperty("task_checkpoint_preserved", restored != null);
        data.addProperty("task_paused_for_reassessment",
                restored != null && restored.pauseSnapshot() != null);
        if (restored != null) {
            data.addProperty("task_id", restored.externalId().toString());
            data.addProperty("task_outcome", safeText(restored.goal().outcome(), 160));
        }
        data.addProperty("inventory_before_death_total", lastDeath.inventoryTotal());
        data.addProperty("inventory_after_respawn_total", totalItems(currentInventory));
        data.addProperty("inventory_missing_count",
                missingCount(lastDeath.inventory(), currentInventory));
        data.addProperty("recover_after_death_requested", lastDeath.recoverAfterDeath());
        data.addProperty("item_recovery_started", false);
        data.addProperty("item_recovery_claimed", false);
        data.addProperty("next_required_step",
                "reassess safety and prove fresh owned drops before any recovery attempt");
        publish("agent.respawned", "Respawn completed; semantic work remains paused for reassessment.", data);
        lifeState = LifeState.ALIVE;
    }

    public enum DeathDecisionEffect {
        NONE,
        REQUEST_NATIVE_RESPAWN,
        REQUEST_NATIVE_SPECTATE,
        CANCEL_TASK
    }

    /** Recognise only our structured death decision; ordinary task answers remain untouched. */
    public static DeathDecisionEffect classifyDeathDecision(
            LocalPlayer player, IntentTaskRecord.DecisionSnapshot decision, String choice) {
        if (decision == null || choice == null
                || !"death_recovery".equals(string(decision.context(), "decision_kind"))) {
            return DeathDecisionEffect.NONE;
        }
        return switch (choice) {
            case "respawn" -> nativeRespawnAvailable(player)
                    ? DeathDecisionEffect.REQUEST_NATIVE_RESPAWN : DeathDecisionEffect.NONE;
            case "spectate" -> connectionAlive()
                    ? DeathDecisionEffect.REQUEST_NATIVE_SPECTATE : DeathDecisionEffect.NONE;
            case "cancel_task" -> DeathDecisionEffect.CANCEL_TASK;
            default -> DeathDecisionEffect.NONE;
        };
    }

    public static synchronized void applyDeathDecision(
            DeathDecisionEffect effect, LocalPlayer player, IntentTaskRecord record) {
        if (effect == null || effect == DeathDecisionEffect.NONE) return;
        if (effect == DeathDecisionEffect.CANCEL_TASK) {
            boolean cancelled = record != null
                    && CompanionTickDispatcher.cancel(record.publicId());
            JsonObject data = new JsonObject();
            data.addProperty("task_cancelled", cancelled);
            data.addProperty("respawn_requested", false);
            publish("agent.death_decision_applied",
                    "The semantic task was cancelled; respawn remains a separate player decision.", data);
            return;
        }
        requestNativeRespawn(player, effect == DeathDecisionEffect.REQUEST_NATIVE_SPECTATE, false);
    }

    private static synchronized void deathDetected(LocalPlayer player) {
        IntentTaskRecord active = currentIntent();
        boolean hardcore = player.level().getLevelData().isHardcore();
        boolean spectator = player.isSpectator();
        boolean autoRequested = active != null && semanticBoolean(active, "auto_respawn");
        boolean recoverRequested = active != null && semanticBoolean(active, "recover_after_death");
        IntentRuntime runtime = IntentRuntime.get();
        boolean checkpointSaved = active != null && runtime.checkpointDeath();
        Map<String, Integer> inventory = inventoryCounts(player);
        lastDeath = new DeathSnapshot(
                active == null ? null : active.externalId(),
                player.level().dimension().location().toString(),
                player.position(), player.level().getGameTime(), inventory,
                totalItems(inventory), recoverRequested);
        lifeState = LifeState.DEAD_REPORTED;
        activeReflexes.clear();

        boolean autoAllowed = autoRequested && nativeRespawnAvailable(player);
        JsonObject data = new JsonObject();
        data.addProperty("hardcore", hardcore);
        data.addProperty("spectator", spectator);
        data.addProperty("auto_respawn_requested", autoRequested);
        data.addProperty("auto_respawn_allowed", autoAllowed);
        data.addProperty("recover_after_death_requested", recoverRequested);
        data.addProperty("semantic_task_checkpointed", checkpointSaved);
        data.addProperty("checkpoint_save_failed", active != null && !checkpointSaved);
        data.addProperty("requires_llm_decision", active != null && !autoAllowed);
        data.addProperty("manual_respawn_required", active == null);
        data.addProperty("inventory_total_at_death", lastDeath.inventoryTotal());
        data.add("inventory_summary_at_death", inventorySummary(inventory));
        data.addProperty("item_recovery_started", false);
        data.addProperty("item_recovery_claimed", false);
        if (active != null) {
            data.addProperty("task_id", active.externalId().toString());
            data.addProperty("task_outcome", safeText(active.goal().outcome(), 160));
        }
        publish("agent.died", "The agent died; semantic recovery state was captured for review.", data);

        if (active == null) return;
        if (autoAllowed) {
            if (active.pauseSnapshot() == null
                    && active.pause(player.level().getGameTime(), "death_respawning")) {
                runtime.paused(active, "Task paused while an authorised native respawn is requested.");
            }
            runtime.checkpointDeath();
            if (!requestNativeRespawn(player, false, true)) {
                runtime.requestDeathDecision(active, hardcore, spectator, deathDecisionContext());
            }
            return;
        }
        runtime.requestDeathDecision(active, hardcore, spectator, deathDecisionContext());
        runtime.checkpointDeath();
    }

    private static boolean requestNativeRespawn(
            LocalPlayer player, boolean explicitSpectate, boolean automatic) {
        if (player == null || !connectionAlive()
                || (!explicitSpectate && !nativeRespawnAvailable(player))) {
            JsonObject data = new JsonObject();
            data.addProperty("automatic", automatic);
            data.addProperty("explicit_spectate", explicitSpectate);
            data.addProperty("outcome_known", true);
            data.addProperty("requested", false);
            publish("agent.respawn_request_failed",
                    "Native respawn could not be requested; the agent remains on the death screen.", data);
            return false;
        }
        try {
            if (!IntentRuntime.get().prepareRespawnHandoff()) {
                JsonObject data = new JsonObject();
                data.addProperty("automatic", automatic);
                data.addProperty("requested", false);
                data.addProperty("checkpoint_saved", false);
                publish("agent.respawn_request_failed",
                        "Respawn was not requested because the semantic checkpoint could not be saved.", data);
                return false;
            }
            lifeState = LifeState.RESPAWN_REQUESTED;
            JsonObject data = new JsonObject();
            data.addProperty("automatic", automatic);
            data.addProperty("explicit_spectate", explicitSpectate);
            data.addProperty("native_request_sent", true);
            data.addProperty("item_recovery_started", false);
            publish("agent.respawn_requested", explicitSpectate
                    ? "Native spectate was explicitly requested."
                    : "Native respawn was requested.", data);
            player.respawn();
            return true;
        } catch (RuntimeException failure) {
            lifeState = LifeState.DEAD_REPORTED;
            JsonObject data = new JsonObject();
            data.addProperty("automatic", automatic);
            data.addProperty("outcome_known", false);
            data.addProperty("requested", false);
            data.addProperty("recheck_death_state", true);
            publish("agent.respawn_request_failed",
                    "The native respawn request had an unknown outcome; recheck before retrying.", data);
            return false;
        }
    }

    private static JsonObject deathDecisionContext() {
        JsonObject context = new JsonObject();
        context.addProperty("decision_kind", "death_recovery");
        context.addProperty("item_recovery_started", false);
        context.addProperty("item_recovery_claimed", false);
        context.addProperty("recovery_requires_fresh_owned_drop_evidence", true);
        return context;
    }

    private static IntentTaskRecord currentIntent() {
        TaskRecord current = CompanionTickDispatcher.current();
        return current instanceof IntentTaskRecord intent && !intent.getState().isTerminal()
                ? intent : null;
    }

    private static boolean semanticBoolean(IntentTaskRecord record, String key) {
        if (record == null) return false;
        if (record.stepIndex() >= 0 && record.stepIndex() < record.steps().size()
                && goalBoolean(record.steps().get(record.stepIndex()), key)) {
            return true;
        }
        return goalBoolean(record.goal(), key);
    }

    private static boolean goalBoolean(org.maiwithu.maicraft.intent.Goal goal, String key) {
        if (goal == null) return false;
        try {
            if (goal.parameters().has(key)) return goal.parameters().get(key).getAsBoolean();
            if (goal.preferences().has(key)) return goal.preferences().get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private static boolean nativeRespawnAvailable(LocalPlayer player) {
        return player != null && !player.level().getLevelData().isHardcore()
                && !player.isSpectator() && connectionAlive();
    }

    private static boolean connectionAlive() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getConnection() != null
                && minecraft.getConnection().getConnection().isConnected();
    }

    private static Map<String, Integer> inventoryCounts(LocalPlayer player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (player == null) return counts;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static JsonArray inventorySummary(Map<String, Integer> counts) {
        JsonArray result = new JsonArray();
        counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(12)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("item_id", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    result.add(item);
                });
        return result;
    }

    private static int totalItems(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int missingCount(Map<String, Integer> before, Map<String, Integer> after) {
        int missing = 0;
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            missing += Math.max(0, entry.getValue() - after.getOrDefault(entry.getKey(), 0));
        }
        return missing;
    }

    private static String string(JsonObject source, String key) {
        return source != null && source.has(key) && !source.get(key).isJsonNull()
                ? source.get(key).getAsString() : null;
    }

    private static void damaged(
            LocalPlayer player, float before, float after, boolean freshMobAttack) {
        LivingEntity attacker = freshMobAttack ? player.getLastHurtByMob() : null;
        Player attackingPlayer = attacker instanceof Player value ? value : null;

        JsonObject cause = new JsonObject();
        if (attacker != null) {
            cause.addProperty(
                    "causing_entity_type_id",
                    BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString());
        }
        if (attackingPlayer != null) {
            cause.addProperty("causing_player_name", attackingPlayer.getGameProfile().getName());
            ItemStack held = attackingPlayer.getMainHandItem();
            if (!held.isEmpty()) {
                cause.addProperty(
                        "causing_item_id",
                        BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
            }
            cause.addProperty("interpretation", "possible_stop_or_follow_request");
            cause.addProperty("requires_llm_decision", true);
        }

        JsonObject data = new JsonObject();
        data.addProperty("effective_health_before", before);
        data.addProperty("effective_health_after", after);
        data.addProperty("fatal", player.getHealth() <= 0.0F);
        data.add("position", position(player));
        data.add("cause", cause);

        if (attackingPlayer != null && player.getHealth() > 0.0F) {
            TaskRecord active = CompanionTickDispatcher.current();
            if (active instanceof IntentTaskRecord intent
                    && intent.pause(player.level().getGameTime(), "player_attention")) {
                data.addProperty("paused_task_id", intent.externalId().toString());
                IntentRuntime.get().paused(
                        intent,
                        "Another player hit the agent; the task paused for the LLM to interpret a possible stop or follow request.");
            }
        }

        String message = attackingPlayer == null
                ? "The agent took damage"
                : attackingPlayer.getGameProfile().getName()
                        + " hit the agent; a possible stop or follow request must not be guessed automatically.";
        publish("agent.damaged", message, data);
    }

    private static void remember(ClientLevel level, String dimension, String phase,
                                 String weather, float health, int hurtByMobTimestamp) {
        previousLevel = level;
        previousDimension = dimension;
        previousTimePhase = phase;
        previousWeather = weather;
        previousEffectiveHealth = health;
        previousHurtByMobTimestamp = hurtByMobTimestamp;
    }

    private static void publish(String type, String message, JsonObject data) {
        IntentRuntime.get().gameEvent(type, message, data);
    }

    private static JsonObject position(LocalPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("x", player.getX());
        result.addProperty("y", player.getY());
        result.addProperty("z", player.getZ());
        result.addProperty("dimension", player.level().dimension().location().toString());
        return result;
    }

    private static JsonObject reflexEnvelope(String reflex, String phase) {
        JsonObject data = new JsonObject();
        data.addProperty("reflex", reflex);
        data.addProperty("phase", phase);
        data.addProperty("contains_internal_target_handles", false);
        return data;
    }

    private static String safeText(String value, int maxChars) {
        if (value == null) return "";
        String clean = value.strip();
        return clean.length() <= maxChars ? clean : clean.substring(0, maxChars);
    }

    private static final class ReflexEpisode {
        private final long startedNanos;
        private boolean escalated;

        private ReflexEpisode(long startedNanos) {
            this.startedNanos = startedNanos;
        }
    }

    private enum LifeState {
        ALIVE,
        DEAD_REPORTED,
        RESPAWN_REQUESTED,
        RESPAWN_OBSERVED
    }

    private record DeathSnapshot(
            UUID taskId,
            String dimension,
            Vec3 position,
            long gameTime,
            Map<String, Integer> inventory,
            int inventoryTotal,
            boolean recoverAfterDeath) {
        private DeathSnapshot {
            inventory = Map.copyOf(inventory);
        }
    }

    private static String weather(ClientLevel level) {
        if (level.isThundering()) return "thunder";
        if (level.isRaining()) return "rain";
        return "clear";
    }

}
