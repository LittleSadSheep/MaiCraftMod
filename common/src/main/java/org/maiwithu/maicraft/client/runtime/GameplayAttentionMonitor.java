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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.core.combat.CombatThreats;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.intent.Goal;

/**
 * 把天气、时间、受伤和死亡变化整理成 Attention 事件；聊天区消息单独送进 ChatFlow。
 * 除观察外，这个类也负责暂停被玩家攻击的任务、保存死亡进度和请求原版重生，
 * 所以改这里可能同时影响通知和角色行为。
 * 另一个玩家的攻击只被解释成需要确认意图的信号，不自动决定还击或跟随。
 */
public final class GameplayAttentionMonitor {

    private static final int MAX_CHAT_CHARS = 512;
    private static final int MAX_CHAT_EVENTS_PER_WINDOW = 8;
    private static final int MAX_CHAT_FINGERPRINTS = 64;
    private static final long CHAT_WINDOW_NANOS = 10_000_000_000L;
    private static final long CHAT_DUPLICATE_NANOS = 5_000_000_000L;
    private static final float HEALTH_EPSILON = 0.001F;
    // 同一攻击者的连续命中共用一个伤害片段：开始报一次，窗口内只累计不打扰，窗口过后再收尾一次。
    // 不合并会让连挨三口变成三次上报、三次上层唤醒，而它们说的是同一件事。
    private static final int DAMAGE_EPISODE_TICKS = 100;

    private static final ArrayDeque<Long> recentChatEvents = new ArrayDeque<>();
    private static final LinkedHashMap<String, Long> recentChatFingerprints = new LinkedHashMap<>();
    private static final Map<String, ReflexEpisode> activeReflexes = new HashMap<>();
    private static final Map<String, DamageEpisode> activeDamage = new HashMap<>();

    private static ClientLevel previousLevel;
    private static String previousDimension;
    private static String previousTimePhase;
    private static String previousWeather;
    private static float previousEffectiveHealth = Float.NaN;
    private static int suppressedChatMessages;
    private static LifeState lifeState = LifeState.ALIVE;
    private static DeathSnapshot lastDeath;

    private GameplayAttentionMonitor() {}

    /**
     * 在每次客户端游戏更新结束、选任务控制身体之前观察一次。首次观察和换世界时只建立基准，不补发旧变化。
     */
    public static void tick(LocalPlayer player) {
        ClientLevel level = player.clientLevel;
        String dimension = level.dimension().location().toString();
        String phase = WorldTimeSemantics.phase(level).id();
        String weather = weather(level);
        // 把普通血量和吸收黄心相加观察。黄心到期也会使这个数下降，因此单靠它变小不能证明受到了伤害。
        float effectiveHealth = player.getHealth() + player.getAbsorptionAmount();
        boolean dead = player.isDeadOrDying() || player.getHealth() <= 0.0F;

        if (dead) {
            if (lifeState == LifeState.ALIVE || lifeState == LifeState.RESPAWN_OBSERVED) {
                deathDetected(player);
            }
            remember(level, dimension, phase, weather, effectiveHealth);
            return;
        }

        // 再次看到活着的身体时先准备重新绑定；任务记录恢复并完成 afterSemanticBind 之前，自动行为仍暂停。
        if (lifeState == LifeState.DEAD_REPORTED || lifeState == LifeState.RESPAWN_REQUESTED) {
            // 检测到重生后的新玩家对象时，先登记同一身份的进度恢复，
            // 再让任务调度器观察并清理已死亡的旧身体。
            IntentRuntime.get().prepareRespawnHandoff();
            lifeState = LifeState.RESPAWN_OBSERVED;
            remember(level, dimension, phase, weather, effectiveHealth);
            return;
        }

        boolean receivedDamage = observeDamagePackets(player,
                previousLevel == level && Float.isFinite(previousEffectiveHealth) ? previousEffectiveHealth : effectiveHealth,
                effectiveHealth);
        if (previousLevel == null) {
            remember(level, dimension, phase, weather, effectiveHealth);
            return;
        }

        if (previousLevel != level || !dimension.equals(previousDimension)) {
            JsonObject data = new JsonObject();
            data.addProperty("from_dimension", previousDimension);
            data.addProperty("to_dimension", dimension);
            data.add("position", position(player));
            publish("agent.dimension_changed", "Entered dimension " + dimension, data);
            remember(level, dimension, phase, weather, effectiveHealth);
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
        if (!receivedDamage && effectiveHealth + HEALTH_EPSILON < previousEffectiveHealth) {
            // 没收到伤害包却掉了血：仍然上报，但证据口径写明是"血量下降"而不是确认的命中。
            observeHealthDrop(player, previousEffectiveHealth, effectiveHealth);
        }

        // 挨打停下之后及时收尾：没有这一步，汇总要等下一次受击或换怪才发得出来。
        settleDamageEpisodes(player);

        remember(level, dimension, phase, weather, effectiveHealth);
    }

    /**
     * 加载器把收到的聊天转成纯文本交进来。最长保留 512 字符，十秒最多八条，同来源和内容五秒内去重。
     * 正文标记为外部不可信文本；被压掉的数量附在下一条真正发出的事件上。
     * 这些消息进独立的 ChatFlow 供主播 Agent 订阅，不进任务 Attention 流。
     */
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
        IntentRuntime.get().chatEvent(
                system ? "game.message_received" : "player.chat_received",
                system
                        ? "Received an untrusted external game message."
                        : "Received untrusted external player chat.",
                data);
    }

    public static synchronized void reset() {
        reset(false);
    }

    // 清掉世界观察基准、聊天限流和反射动作记录；跨重生重绑时可保留死亡快照，避免丢掉恢复所需信息。
    public static synchronized void reset(boolean preserveDeathRecovery) {
        previousLevel = null;
        previousDimension = null;
        previousTimePhase = null;
        previousWeather = null;
        previousEffectiveHealth = Float.NaN;
        recentChatEvents.clear();
        recentChatFingerprints.clear();
        activeReflexes.clear();
        // 换世界/断线时丢掉未收尾的伤害片段：那些命中属于上一个身体与上一个世界。
        activeDamage.clear();
        suppressedChatMessages = 0;
        if (!preserveDeathRecovery || lifeState == LifeState.ALIVE) {
            lifeState = LifeState.ALIVE;
            lastDeath = null;
        }
    }

    /** 每次自救触发只报告一次，通知中不携带移动目标或背包内部细节。 */
    // 同一种紧急反应只记一次开始；它还在进行时重复请求不会重复发通知。
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

    // 只有已开始且尚未报告升级的反应会发出这一事件，每次反应最多升级通知一次。
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

    // 结束时取走该次反应记录，并报告动作数量、资源影响和持续时间；没有对应开始记录就忽略。
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

    /** 死亡或刚重新绑定的玩家仍可被观察，但语义任务须等待恢复流程完成。 */
    public static synchronized boolean blocksAutomation(LocalPlayer player) {
        return player == null || player.isDeadOrDying() || player.getHealth() <= 0.0F
                || lifeState != LifeState.ALIVE;
    }

    /** IntentRuntime 完成重生恢复后，再发布保守的任务检查点。 */
    // 重生后的任务绑定完成才报告库存对比，并解除死亡阶段的自动行为锁；没有自动开始捡回遗物。
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
        // 这里只解除死亡阶段锁，没有清除原任务里的 death_recovery 决定；手动重生后仍可能被旧决定挡住 resume。
        lifeState = LifeState.ALIVE;
    }

    public enum DeathDecisionEffect {
        NONE,
        REQUEST_NATIVE_RESPAWN,
        REQUEST_NATIVE_SPECTATE,
        CANCEL_TASK
    }

    /**
     * 只解析程序自己生成的 death_recovery 决定：可重生时允许请求重生，连接仍在时允许显式观战，或取消任务。
     * 这里仅决定下一步类型，真正发动作在 applyDeathDecision。
     */
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

    // 先保存当前任务和此刻可见库存，再报告死亡；只有目标带自动重生要求且模式允许时才尝试自动请求。
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
        // 死亡时未收尾的伤害片段直接丢弃：死亡本身另发 agent.died，补一条"刚才挨了几下"只会重复。
        activeDamage.clear();

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

    // 发重生请求之前必须连接正常并完成任务进度交接；显式观战复用原版 respawn 请求，由服务端模式决定结果。
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

    // 当前步骤为 true 或整任务为 true 都算请求；步骤明确填 false 不能覆盖整任务的 true。
    private static boolean semanticBoolean(IntentTaskRecord record, String key) {
        if (record == null) return false;
        if (record.stepIndex() >= 0 && record.stepIndex() < record.steps().size()
                && goalBoolean(record.steps().get(record.stepIndex()), key)) {
            return true;
        }
        return goalBoolean(record.goal(), key);
    }

    private static boolean goalBoolean(Goal goal, String key) {
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

    // 按物品注册名合并普通槽位、盔甲和副手数量，不区分附魔、名字或其他组件，也不是死亡前提前保存的库存。
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

    // 逐种比较死亡时快照和重生后数量，只累计减少的件数；同名不同组件的物品在这里仍视为同一种。
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

    // 只有判断为新攻击且读到攻击者是玩家时才暂停语义任务；否则仍发受伤通知，但没有这一玩家交互分支。
    /** 在调度身体之前消费收到的伤害；玩家袭击不依赖血量包先后顺序，也不读取服务端 AI 字段。 */
    public static boolean observeDamagePackets(LocalPlayer player, float before, float after) {
        var notices = CombatThreats.consumeDamage(player);
        for (var notice : notices) {
            damaged(player, before, after, notice.attacker(), true, notice.damageType());
        }
        return !notices.isEmpty();
    }

    /**
     * 观察到有效血量下降、但没收到伤害包时上报一次。
     *
     * <p>与 {@link #observeDamagePackets} 分开命名，是因为两者的证据强度不同：这条只说明"血少了"，
     * 说不出谁打的，事件里也用 {@code evidence} 写明这一点。黄心到期同样会让有效血量下降，
     * 所以它不是伤害结算，只是一次需要上层的观察。
     */
    public static void observeHealthDrop(LocalPlayer player, float before, float after) {
        damaged(player, before, after, null, false, "");
    }

    // 收尾已经停止的伤害片段：窗口内没有新命中就发一次汇总，让上层知道"刚才一共被打了几下、掉了多少血"。
    // 由客户端 tick 调用；测试直接驱动本方法，不需要等真实帧。
    public static synchronized void settleDamageEpisodes(LocalPlayer player) {
        if (activeDamage.isEmpty() || player == null || player.level() == null) return;
        long tick = player.level().getGameTime();
        var iterator = activeDamage.entrySet().iterator();
        while (iterator.hasNext()) {
            DamageEpisode episode = iterator.next().getValue();
            // 换了身体或换了世界就不属于当前这条命：静默丢弃，不给上一个世界补汇总。
            if (episode.body != player || episode.level != player.level()) {
                iterator.remove();
                continue;
            }
            if (tick - episode.lastTick <= DAMAGE_EPISODE_TICKS) continue;
            iterator.remove();
            publishEpisode(player, episode, "finished", false);
        }
    }

    /** 按攻击者归并伤害：玩家袭击立即上报且不合并（它要求上层决策），生物与环境伤害进片段。 */
    private static void damaged(
            LocalPlayer player, float before, float after, LivingEntity attacker, boolean packetEvidence,
            String damageType) {
        Player attackingPlayer = attacker instanceof Player value && value != player ? value : null;
        float delta = Math.max(0F, before - after);
        boolean fatal = player.getHealth() <= 0.0F;

        JsonObject cause = new JsonObject();
        // 伤害类型先写：外部看不懂"谁打的"时（摔落/溺水/仙人掌），至少能看清"是什么伤"。
        if (damageType != null && !damageType.isEmpty()) {
            cause.addProperty("damage_type", damageType);
        }
        if (attacker != null) {
            cause.addProperty(
                    "causing_entity_type_id",
                    BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString());
            // 距离与目标字段只是补充事实：取不到也不能让整条伤害通知发不出去，写清原因照发。
            try {
                cause.addProperty("causing_entity_distance", Math.round(player.distanceTo(attacker) * 10.0) / 10.0);
                if (attacker instanceof Mob mob) {
                    // 目标字段只用来描述"它是否正盯着我"，不作为伤害证据（伤害证据只来自伤害包）。
                    cause.addProperty("causing_entity_alive", !mob.isRemoved() && mob.isAlive());
                    cause.addProperty("causing_entity_targeting_agent", mob.getTarget() == player);
                }
            } catch (RuntimeException unavailable) {
                cause.addProperty("cause_evidence_unavailable", String.valueOf(unavailable.getMessage()));
            }
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

        if (attackingPlayer != null && player.getHealth() > 0.0F) {
            TaskRecord active = CompanionTickDispatcher.current();
            if (active instanceof IntentTaskRecord intent
                    && intent.pause(player.level().getGameTime(), "player_attention")) {
                cause.addProperty("paused_task_id", intent.externalId().toString());
                IntentRuntime.get().paused(
                        intent,
                        "Another player hit the agent; the task paused for the LLM to interpret a possible stop or follow request.");
            }
        }

        long tick = player.level().getGameTime();
        if (attackingPlayer != null) {
            // 玩家袭击：立刻上报，不并入生物伤害片段——它要求上层解释意图，不能被合并掉。
            DamageEpisode attack = new DamageEpisode(
                    player, player.level(), tick, attacker, cause, packetEvidence);
            attack.record(tick, delta, before, after);
            publishEpisode(player, attack, "started", fatal);
            return;
        }

        String key = damageKey(attacker, damageType);
        DamageEpisode episode = activeDamage.get(key);
        // 换了身体或换了世界，旧片段就不属于这条命：丢弃而不是接着累计（否则新身体会继承上一具身体的伤）。
        if (episode != null && (episode.body != player || episode.level != player.level())) {
            activeDamage.remove(key);
            episode = null;
        }
        if (episode != null && tick - episode.lastTick > DAMAGE_EPISODE_TICKS) {
            activeDamage.remove(key);
            publishEpisode(player, episode, "finished", false);
            episode = null;
        }
        if (episode == null) {
            episode = new DamageEpisode(player, player.level(), tick, attacker, cause, packetEvidence);
            activeDamage.put(key, episode);
        }
        boolean first = episode.hits == 0;
        episode.record(tick, delta, before, after);
        if (first) {
            // 只有片段首次命中立即上报；后续同一攻击者的命中只累计，收尾时一次性交代。
            // 死亡不在这里另开一支：致死那一刻由 agent.died 负责，本条只如实带上 fatal 标记。
            publishEpisode(player, episode, "started", fatal);
        }
    }

    /** 片段的归并键：攻击者种类；没有攻击者时按伤害类型分（摔落与溺水不该并成一段）。 */
    private static String damageKey(LivingEntity attacker, String damageType) {
        if (attacker != null) {
            return BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString();
        }
        return "environment:" + (damageType == null || damageType.isEmpty() ? "unknown" : damageType);
    }

    private static void publishEpisode(
            LocalPlayer player, DamageEpisode episode, String phase, boolean fatal) {
        JsonObject data = new JsonObject();
        data.addProperty("phase", phase);
        // 证据口径必须写在事件里：伤害包确认的命中，与"只看到血量掉了"是两种可信度。
        data.addProperty("evidence", episode.packetEvidence ? "damage_packet" : "health_drop_without_packet");
        data.addProperty("effective_health_before", episode.firstBefore);
        data.addProperty("effective_health_after", episode.lastAfter);
        // 有效血量含吸收黄心，黄心到期也会让它下降，所以这个差值是"整个片段的掉血"，不是逐次伤害结算值。
        data.addProperty("effective_health_delta",
                Math.max(0F, episode.firstBefore - episode.lastAfter));
        data.addProperty("current_health", player.getHealth());
        data.addProperty("absorption", player.getAbsorptionAmount());
        data.addProperty("fatal", fatal);
        data.add("position", position(player));
        data.add("cause", episode.cause);
        data.add("defense", defenseEvidence(player, episode.attacker));
        JsonObject repeat = new JsonObject();
        repeat.addProperty("hits", episode.hits);
        repeat.addProperty("damage_total", episode.damage);
        repeat.addProperty("last_damage_taken", episode.lastDelta);
        repeat.addProperty("window_ticks", DAMAGE_EPISODE_TICKS);
        data.add("repeat", repeat);
        data.addProperty("contains_internal_target_handles", false);
        publish("agent.damaged", damageMessage(phase, episode), data);
    }

    /**
     * 本能接管状态的证据。
     *
     * <p>只报可验证的两件事：自卫链现在会不会接管、这个攻击者是否被判为威胁。
     * 不写"正在反击"——真的在挥剑、还是在撤退、还是已被打断，这里都没有证据，
     * 而一句没有证据的"正在反击"会让上层照着一件没发生的事去叙述。
     */
    private static JsonObject defenseEvidence(LocalPlayer player, LivingEntity attacker) {
        JsonObject defense = new JsonObject();
        defense.addProperty("policy", "instinct");
        defense.addProperty("evidence", "runtime_self_defense_chain");
        try {
            defense.addProperty("would_engage", new MobDefenseChain().canRun(player));
        } catch (RuntimeException unavailable) {
            defense.addProperty("would_engage", false);
            defense.addProperty("reason", String.valueOf(unavailable.getMessage()));
        }
        if (attacker != null) {
            try {
                defense.addProperty("threat_recognized", Menace.threatens(attacker, player));
            } catch (RuntimeException unavailable) {
                defense.addProperty("threat_recognized", false);
                defense.addProperty("threat_reason", String.valueOf(unavailable.getMessage()));
            }
        }
        return defense;
    }

    private static String damageMessage(String phase, DamageEpisode episode) {
        if ("finished".equals(phase)) {
            return "The agent stopped taking damage; " + episode.hits + " hits in total";
        }
        if (episode.attacker instanceof Player attacker) {
            return attacker.getGameProfile().getName()
                    + " hit the agent; a possible stop or follow request must not be guessed automatically.";
        }
        return "The agent took damage";
    }

    private static void remember(ClientLevel level, String dimension, String phase,
                                 String weather, float health) {
        previousLevel = level;
        previousDimension = dimension;
        previousTimePhase = phase;
        previousWeather = weather;
        previousEffectiveHealth = health;
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

    /** 一次伤害片段的累计：首次与最近一次的读数、命中次数与总掉血；供开始/收尾两次上报共用。 */
    private static final class DamageEpisode {
        private final LocalPlayer body;
        private final Level level;
        private final LivingEntity attacker;
        private final JsonObject cause;
        private final boolean packetEvidence;
        private long lastTick;
        private int hits;
        private float damage;
        private float lastDelta;
        private float firstBefore = Float.NaN;
        private float lastAfter = Float.NaN;

        private DamageEpisode(
                LocalPlayer body, Level level, long tick, LivingEntity attacker,
                JsonObject cause, boolean packetEvidence) {
            this.body = body;
            this.level = level;
            this.lastTick = tick;
            this.attacker = attacker;
            this.cause = cause;
            this.packetEvidence = packetEvidence;
        }

        private void record(long tick, float delta, float before, float after) {
            if (hits == 0) firstBefore = before;
            lastTick = tick;
            hits++;
            damage += delta;
            lastDelta = delta;
            lastAfter = after;
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
