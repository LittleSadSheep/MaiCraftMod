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
        String phase = timePhase(level.getDayTime());
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
            data.addProperty("day", Math.floorDiv(level.getDayTime(), 24_000L));
            data.addProperty("time_of_day", Math.floorMod(level.getDayTime(), 24_000L));
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
