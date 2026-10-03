package org.maiwithu.maicraft.intent;
import org.maiwithu.maicraft.core.task.explore.ClientExplorationMemory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.preview.PreviewController;
import org.maiwithu.maicraft.client.preview.PreviewSession;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneStore;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.task.container.ContainerMemory;

/**
 * 保存客户端业务任务的计划、公开 ID、进度、决策和持久化状态，供 MCP 查询。
 *
 * <p>这里负责登记任务和发布状态；身体动作由 {@link CompanionTickDispatcher} 调度，
 * 普通业务任务的父子步骤由 IntentTask 推进。
 */
public final class IntentRuntime {

    // 内存接单表与检查点共用容量，不能接到一件保存时会被截掉的任务。
    private static final int MAX_PLANS = IntentStateCodec.MAX_PLANS;
    private static final int MAX_TASKS = IntentStateCodec.MAX_TASKS;
    private static final int MAX_REQUEST_KEYS = IntentStateCodec.MAX_REQUEST_KEYS;
    private static final int MAX_LANDMARKS = IntentStateCodec.MAX_LANDMARKS;
    private static final long SAVE_INTERVAL_NANOS = 5_000_000_000L;

    private static final Set<String> CORE_ABILITIES = Set.of(
            PhysicsAbilityAdapter.ABILITY,
            QuestAbilityAdapter.ABILITY,
            ChatAbilityAdapter.ABILITY,
            SuicideAbilityAdapter.ABILITY, // 让模型通过正式能力发现与执行入口提交主动寻死。
            "maicraft:remember_place",
            "maicraft:sleep",
            "maicraft:travel",
            "maicraft:travel_dimension",
            "maicraft:prepare_portal",
            "maicraft:find_structure",
            ExplorationIntent.ABILITY,
            "maicraft:reach_milestone",
            "maicraft:defeat_ender_dragon",
            "maicraft:obtain_elytra",
            "maicraft:craft",
            "maicraft:cook",
            "maicraft:enchant",
        "maicraft:stonecut",
            "maicraft:trade",
            "maicraft:build",
            BuildDesignAdapter.ABILITY,
            "maicraft:light_area",
            AutomaticLightingAdapter.ABILITY,
            "maicraft:connect_mechanical_power",
            "maicraft:inspect_machine",
            "maicraft:design_machine",
            "maicraft:operate_machine",
            "maicraft:modify_machine",
            "maicraft:build_machine",
            "maicraft:acquire_items",
            WaitAbilityAdapter.ABILITY,
            "maicraft:sequence");

    /** 启动时固定公开能力名单，避免对外发布后与核心、通用适配器的登记范围不一致。 */
    public static final Set<String> KNOWN_ABILITIES = Stream.concat(
                    CORE_ABILITIES.stream(), GeneralAbilityAdapter.abilities().stream())
            .collect(Collectors.toUnmodifiableSet());

    private static final IntentRuntime INSTANCE = new IntentRuntime();
    private static final Gson GSON = new Gson();
    private static boolean registered;

    private final LinkedHashMap<UUID, Plan> plans = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, IntentTaskRecord> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<String, UUID> requestKeys = new LinkedHashMap<>();
    private final LinkedHashMap<String, Landmark> landmarks = new LinkedHashMap<>();
    // 死亡瞬间没有任务承接决策时，恢复问题挂在这份独立承载记录上：不进任务注册表也不进检查点，
    // 它的目标不是可执行能力，持久化会让恢复校验把整份旧进度判成无法恢复。
    private IntentTaskRecord deathRecovery;
    // 自主开箱形成的标识与库存随同一世界检查点保存，断线重连后继续作为历史线索使用。
    private final ContainerMemory containers = new ContainerMemory(this::markDirty);

    public ContainerMemory containerMemory() { return containers; }
    /** 进度事件的门卫：记分牌变化驱动 + 地板间隔 + 无键静默，契约见 task-progress-contract。 */
    private final ProgressGate progressGate = new ProgressGate();
    private final AttentionFeed attention = new AttentionFeed();
    private final ChatFlow chatFlow = new ChatFlow();
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
        // 原检查点尚未恢复时不能登记新计划，否则玩家会得到重连后消失的计划编号。
        requireRecoveredState();
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
                PreviewController::showDesign);
    }

    public static boolean isReadOnlyDesign(Goal goal) {
        return BuildDesignAdapter.ABILITY.equals(goal.ability()) || BuildingSceneContract.noConstruction(goal);
    }

    /** 配置随行照明与只读设计都不申请身体，也不替换正在运行的任务。 */
    public static boolean isIndependentRequest(Goal goal) {
        return isReadOnlyDesign(goal) || AutomaticLightingAdapter.ABILITY.equals(goal.ability());
    }

    IntentTaskRecord execute(LocalPlayer player, Goal goal, UUID planId, String requestKey,
                            Predicate<PreviewSession> publishDesign) {
        return execute(player,goal,planId,requestKey,publishDesign,BuildingSceneStore::current);
    }

    // 设计保存仍由同一只读分支推进；注入世界场景仓库便于验证并行设计绝不会替换角色当前施工槽。
    IntentTaskRecord execute(LocalPlayer player, Goal goal, UUID planId, String requestKey,
                            Predicate<PreviewSession> publishDesign,
                            Supplier<BuildingSceneStore> sceneStores) {
        // 先确认旧进度可继续保存，再校验并接单；受阻时不能发布 started、写新文件或把任务交给身体。
        requireRecoveredState();
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
        reserveTaskCapacity();
        UUID taskId = UUID.randomUUID();
        IntentTaskRecord record = new IntentTaskRecord(
                taskId, planId, goal, stateIdentity.key());
        record.bindDirty(this::markDirty);
        tasks.put(taskId, record);
        if (requestKey != null && !requestKey.isBlank()) {
            requestKeys.put(requestKey, taskId);
            trimOldest(requestKeys, MAX_REQUEST_KEYS);
        }
        markDirty();
        publish("started", record, "Started: " + goal.outcome(), new JsonObject());
        if (isIndependentRequest(goal)) {
            TaskResult result;
            try {
                var action = AutomaticLightingAdapter.ABILITY.equals(goal.ability())
                        ? AutomaticLightingAdapter.adapt(goal, player) : BuildingSceneContract.supports(goal)
                        ? BuildingSceneAdapter.adapt(goal, player, this, publishDesign,sceneStores)
                        : BuildDesignAdapter.design(goal, player, this, publishDesign);
                if (!(action instanceof IntentAction.Report report))
                    throw new IllegalStateException("independent request returned a body action");
                result = report.result();
            } catch (RuntimeException failure) {
                result = TaskResult.fail("Independent request failed: " + failure.getMessage(),
                        Map.of("failure_code", isReadOnlyDesign(goal) ? "preview_design_failed" : "lighting_configuration_failed", "construction_started", false));
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
        if (id != null && deathRecovery != null && deathRecovery.externalId().equals(id)) {
            return deathRecovery;
        }
        return tasks.get(id);
    }

    /** 用调用方保留的请求编号找回原任务，网络重试无需猜测任务 UUID。 */
    public IntentTaskRecord taskForRequestKey(String requestKey) {
        if (requestKey == null || requestKey.isBlank()) return null;
        UUID id = requestKeys.get(requestKey);
        return id == null ? null : tasks.get(id);
    }

    public List<IntentTaskRecord> tasks(int limit) {
        List<IntentTaskRecord> all = new ArrayList<>(tasks.values());
        if (deathRecovery != null) all.add(deathRecovery);
        int from = Math.max(0, all.size() - Math.max(1, limit));
        List<IntentTaskRecord> result = new ArrayList<>(all.subList(from, all.size()));
        Collections.reverse(result);
        return List.copyOf(result);
    }

    public void remember(String label, Goal.WorldPosition position) {
        remember(label, position, LandmarkAreaRole.ORDINARY);
    }

    public void remember(
            String label, Goal.WorldPosition position, LandmarkAreaRole areaRole) {
        // 地标同样属于世界检查点，恢复受阻时拒绝新增，避免让玩家误以为位置已经被记住。
        requireRecoveredState();
        String key = normalizeLabel(label);
        landmarks.put(key, new Landmark(label, position, areaRole));
        trimOldest(landmarks, MAX_LANDMARKS);
        markDirty();
    }

    public Landmark landmark(String label) {
        if (label == null) return null;
        Landmark remembered = landmarks.get(normalizeLabel(label));
        if (remembered != null) return remembered;
        // 跑图查询返回的稳定标签按需从独立记忆分区解析，不挤占手工地标容量。
        if (label.startsWith("exploration:")) {
            var found = ClientExplorationMemory.resolveLabel(label);
            return found == null ? null : new Landmark(label, found, LandmarkAreaRole.ORDINARY);
        }
        var minecraft = Minecraft.getInstance();
        var location = minecraft == null ? null : ClientMachineCatalog.resolveLabel(minecraft.player,label);
        return location == null ? null : new Landmark(label,location,LandmarkAreaRole.ORDINARY);
    }

    /** 角色取料或绕行后再看同一工地时沿用原锚点；显式记忆地点仍可另行修改地标。 */
    public Goal.WorldPosition constructionAnchor(String label, Goal.WorldPosition observed) {
        Landmark existing = landmark(label);
        if (existing != null) {
            if (!existing.position().dimension().equals(observed.dimension()))
                throw new IllegalArgumentException("construction_site_dimension_mismatch: the named site belongs to another dimension");
            return existing.position();
        }
        remember(label, observed);
        return observed;
    }

    public List<Landmark> landmarks() {
        return List.copyOf(landmarks.values());
    }

    /**
     * 调度器处理完玩家与世界交接后，再绑定当前世界的任务记忆。
     * 世界身份不同就重新恢复；身份相同时，只复用调度器确实保留下来的传送父任务。
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

    /** MCP 接单前核对世界记忆，正在交接玩家或世界时先拒绝请求，避免任务落到旧身体上。 */
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
     * 发现新的世界身份时先截取旧进度，再标记身体已脱离。
     * 随后取消旧身体任务产生的状态变化，不能覆盖用于恢复的交接快照。
     */
    public void beforeBodyTick(Minecraft minecraft) {
        StateIdentity next = StateIdentity.resolve(minecraft).orElse(null);
        if (next != null && stateIdentity != null && bodyAttached
                && !stateIdentity.key().equals(next.key()) && captureCheckpoint(true)) {
            bodyAttached = false;
        }
    }

    /** 断线或玩家替换时，先截取任务进度，再让身体调度器取消旧任务。 */
    public void bodyUnavailable() {
        captureCheckpoint(true);
        bodyAttached = false;
        gameEvent("runtime.unavailable", "The controlled body disconnected; task state must be resynchronized.", new JsonObject());
    }

    /** 死亡清理前保留当刻任务进度，磁盘写入仍由后台完成。 */
    public boolean checkpointDeath() {
        return captureCheckpoint(true);
    }

    /** 原生复活替换玩家对象前先保留进度并脱离旧身体；下次绑定时以暂停状态恢复未完成任务。 */
    public boolean prepareRespawnHandoff() {
        boolean saved = captureCheckpoint(true);
        bodyAttached = false;
        return saved;
    }

    /** 为当前总任务记录死亡后要回答的问题，继续使用既有任务答复入口。 */
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
        long gameTime = minecraft == null || minecraft.level == null
                ? 0L : minecraft.level.getGameTime();
        record.requestDecision(snapshot, gameTime);
        decision(record, snapshot);
        captureCheckpoint(true);
    }

    private static final Goal DEATH_RECOVERY_GOAL = new Goal(
            "maicraft:death_recovery",
            "Recover from the agent's death: answer the pending death decision to respawn, spectate or cancel.",
            null, "{}", "{}", List.of(), List.of());

    /**
     * 死亡瞬间没有任务承接决策时，把恢复问题挂到独立的承载记录上。
     *
     * <p>承载记录不占用身体调度槽、不进任务注册表与检查点，但经 {@link #task(UUID)} 与
     * {@link #tasks(int)} 原样可达：task list 看得到问题，task answer 能直接答复，
     * 身体不在场（死亡屏幕）也成立。同一时刻只有一份；再次死亡时替换旧记录。
     */
    public IntentTaskRecord openDeathRecoveryDecision(
            boolean hardcore, boolean spectator, JsonObject suppliedContext) {
        IntentTaskRecord host = new IntentTaskRecord(UUID.randomUUID(), null, DEATH_RECOVERY_GOAL);
        host.bindDirty(this::markDirty);
        deathRecovery = host;
        requestDeathDecision(host, hardcore, spectator, suppliedContext);
        return host;
    }

    public boolean isDeathRecoveryHost(IntentTaskRecord record) {
        return record != null && deathRecovery == record;
    }

    /** 死亡恢复承载记录收尾：终态化并发布结算事件；引用保留供 task get 查阅，换世界或下一次死亡时清除。 */
    public void finishDeathRecovery(
            IntentTaskRecord record, TaskState state, TaskResult result, long gameTime) {
        if (deathRecovery != record) return;
        record.clearPendingDecision();
        record.terminal(state, result, gameTime);
        terminal(record, state, result);
        markDirty();
    }

    /** 死亡已由任务外的途径解决（人工点击重生）：作废遗留恢复问题并结算承载记录；没有待答记录时静默。 */
    public void finishSupersededDeathRecovery(long gameTime) {
        if (deathRecovery == null || deathRecovery.decisionSnapshot() == null) return;
        TaskResult result = TaskResult.cancelled(
                "respawn completed without answering the death decision", "respawned_by_player");
        finishDeathRecovery(deathRecovery, TaskState.CANCELLED, result, gameTime);
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
        // 旧任务恢复未完成时不能通过继续任务入口重新请求角色动作。
        requireRecoveredState();
        if (record == null || stateIdentity == null || !bodyAttached
                || record.bindingKey() == null
                || !stateIdentity.key().equals(record.bindingKey())) {
            throw new IllegalStateException(
                    "semantic task belongs to another connection or world");
        }
    }

    /** 仅在准备登记或执行任务时检查恢复状态；世界观察、能力查询和注意事件仍可用于定位问题。 */
    public void requireRecoveredState() {
        String problem = stateIdentity == null ? null : stateStore.recoveryProblem(stateIdentity);
        if (problem != null) throw new IllegalStateException(problem);
    }

    // 一次消费的独立日志与当前总任务使用同一世界身份，不能从公开参数指定别的存档路径。
    StateIdentity requiredStateIdentity() {
        requireRecoveredState();
        if (stateIdentity == null) throw new IllegalStateException("a durable operation requires the currently bound world");
        return stateIdentity;
    }

    CompletableFuture<Void> checkpointBeforeSubmission(IntentTaskRecord parent) {
        requireSubmissionParent(parent);
        try {
            // 绕过普通五秒保存间隔，完整保留稳定任务身份、request_key和当前步骤；返回实际磁盘写入凭据给消费屏障等待。
            var completion = stateStore.saveAsync(stateIdentity, encodeCheckpoint());
            dirty = false; nextSaveNanos = System.nanoTime() + SAVE_INTERVAL_NANOS;
            return completion;
        } catch (IOException | RuntimeException failure) {
            dirty = true;
            throw new IllegalStateException("enchantment parent checkpoint could not be scheduled", failure);
        }
    }

    CompletableFuture<Void> followSubmissionCheckpoint(IntentTaskRecord parent) {
        requireSubmissionParent(parent);
        // 本运行时的合并快照仍包含已登记父任务；跟随最新回执，若它失败则让屏障停止，不能自动改写再试。
        var latest = stateStore.latestSaveCompletion(stateIdentity);
        return latest == null || latest.isCancelled() ? checkpointBeforeSubmission(parent) : latest;
    }

    private void requireSubmissionParent(IntentTaskRecord parent) {
        requireCurrentBinding(parent);
        if (tasks.get(parent.externalId()) != parent || parent.stepIndex() >= parent.steps().size())
            throw new IllegalStateException("submission parent is not the registered current task");
    }

    public void restoredTaskAttached(IntentTaskRecord record) {
        requireCurrentBinding(record);
        record.markAttached();
        markDirty();
    }

    /** 玩家不想继续恢复的旧任务时，直接结算记录，不能为取消而启动它或替换当前身体任务。 */
    public void cancelRestored(IntentTaskRecord record, long gameTime) {
        requireCurrentBinding(record);
        if (tasks.get(record.externalId()) != record || !record.restoredDetached()
                || record.getState().isTerminal()) {
            throw new IllegalStateException("task is not an unfinished detached restoration");
        }
        // 保留已完成步骤和失败证据，只结束后续工作；终态仍走普通通知与检查点保存路径。
        TaskResult result = TaskResult.cancelled("semantic task cancelled before resuming", "resume_cancelled");
        record.terminal(TaskState.CANCELLED, result, gameTime);
        terminal(record, TaskState.CANCELLED, result);
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
                containers.restore(loaded.root().getAsJsonArray("containers"));
                for (Plan plan : decoded.plans()) {
                    validateRestoredGoal(plan.goal());
                    if (plans.putIfAbsent(plan.id(), plan) != null) {
                        throw new IllegalArgumentException("duplicate persisted plan id");
                    }
                }
                for (IntentStateCodec.TaskSnapshot snapshot : decoded.tasks()) {
                    validateRestoredGoal(snapshot.goal());
                    for (Goal step : snapshot.steps()) validateRestoredGoal(step);
                    for (IntentTaskRecord.AttemptSnapshot attempt : snapshot.attempts()) {
                        validateRestoredGoal(attempt.goal());
                    }
                    IntentTaskRecord record = IntentTaskRecord.restored(
                            snapshot.id(), snapshot.planId(), snapshot.goal(),
                            stateIdentity.key(), snapshot.steps(), snapshot.stepIndex(),
                            snapshot.completed(), snapshot.internalPositions(),
                            snapshot.internalAreaProtections(),
                            snapshot.attempts(), snapshot.decision(),
                            snapshot.pendingAnswer(), snapshot.terminal(), gameTime);
                    record.restoreChatSubmissionTracking(snapshot.chatSubmissionTracked());
                    record.restoreContainerSearchScopes(snapshot.containerSearchScopes());
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
                // JSON 与世界身份已通过存储层检查；配置收紧或版本不兼容不能把整份旧任务当损坏文件移走。
                stateStore.preserveUnrestored(stateIdentity);
                clearSemanticState();
                restoredTasks = 0;
                restoredLandmarks = 0;
                restoredTerminal = 0;
                status = "recovery_blocked";
                Constants.LOG.warn(
                        "MaiCraft semantic state could not be restored; the checkpoint was preserved ({})",
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
        // 容量不足和内容无法恢复都明确提示旧文件已保留；查询方不能把零条已加载任务误认成一个新世界。
        String problem = stateStore.recoveryProblem(stateIdentity);
        if (problem != null) {
            data.addProperty("checkpoint_preserved", true);
            data.addProperty("new_tasks_blocked", true);
            data.addProperty("configuration", BuildingBudgets.CONFIG_PATH);
        }
        attention.publish(
                "state_restored",
                null,
                problem != null ? problem : "Restored " + restoredTasks + " semantic task(s) and "
                        + restoredLandmarks + " landmark(s); non-terminal work is paused.",
                data);
    }

    /** 返回是否已保留可供本进程交接的快照；磁盘是否写完仍以保存回执为准。 */
    private boolean captureCheckpoint(boolean force) {
        if (stateIdentity == null) return false;
        // 未恢复的旧任务不能被空状态覆盖，也不能把尚未接受的保存冒充为可供死亡或重连使用的交接快照。
        if (stateStore.recoveryProblem(stateIdentity) != null) return false;
        // 已经脱离身体的交接快照必须保留；旧任务后续被取消或客户端退出，不能把它改写为取消后的状态。
        if (!bodyAttached) return stateStore.hasSnapshot(stateIdentity);
        if (!force && !dirty && !stateStore.hasFailedSave(stateIdentity)) return true;
        try {
            JsonObject root = encodeCheckpoint();
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

    private JsonObject encodeCheckpoint() {
        // 普通保存和原生提交前的检查点共用完整记忆，避免其中一条保存路径把箱子记录覆盖掉。
        JsonObject root = IntentStateCodec.encode(stateIdentity.key(), plans.values(), tasks.values(), requestKeys, landmarks.values());
        root.add("containers", containers.snapshot());
        return root;
    }

    private void clearSemanticState() {
        bodyAttached = false;
        plans.clear();
        tasks.clear();
        requestKeys.clear();
        landmarks.clear();
        containers.clear();
        // 死亡恢复承载记录属于上一条命与上一个连接；换世界后旧决策不再可答，防止迟到答复触达新身体。
        deathRecovery = null;
        attention.clear();
        // 换了世界或连接，聊天区消息也属于上一轮，随任务语义一起作废。
        chatFlow.clear();
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

    void stepProcessed(IntentTaskRecord record) {
        JsonObject data = new JsonObject();
        data.addProperty("completed_step_count", record.stepIndex());
        data.addProperty("step_count", record.steps().size());
        boolean skipped = record.stepResults().getLast().skipped();
        // 完成与跳过分别通知，避免调用者将“已处理”误认成“已经产生游戏效果”。
        data.addProperty("skipped", skipped);
        publish(skipped ? "step_skipped" : "step_completed", record, skipped
                ? "A semantic step was skipped by explicit decision; its outcome was not confirmed."
                : "A semantic step finished; the parent task may still be running.", data);
    }

    /** 玩家收回第一人称控制时，只暂停仍在执行的语义父任务，保留已有暂停或待答问题。 */
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

    /** 控制权归还后只解除 {@link #controlUnavailable} 设置的暂停，其他暂停仍等明确继续。 */
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

    /** 恢复历史不等于重新批准执行；仍保留结构、容量与内部动作边界，避免旧记录锁住整个世界的任务。 */
    private void validateRestoredGoal(Goal goal) {
        SemanticGoalContract.validateRestored(goal, KNOWN_ABILITIES);
        IntentStateCodec.requirePersistableGoal(goal);
        rejectMicroInstructions(goal);
    }

    /** 先校验答复的目标与参数，再允许它解除暂停或修改当前任务。 */
    public void validateDecisionAnswer(
            IntentTaskRecord record, String choice, JsonObject details) {
        JsonObject supplied = details == null ? new JsonObject() : details;
        IntentTaskRecord.DecisionSnapshot pending = record.decisionSnapshot();
        // 死亡恢复决策由 GameplayAttentionMonitor 按选项同步应用（重生/观战/取消），不走语义改写；
        // 承载记录的"步骤"是恢复目标本身（executableSteps 返回自身），不是已登记能力，
        // 落到下面的语义步骤校验会把合法答复拒成 unknown_ability（实机复验 013 断裂点）。
        if (IntentTaskRecord.deathRecovery(pending)) {
            if (!supplied.isEmpty()) {
                throw new SemanticContractException(
                        "decision_details_not_allowed", "answer.details", null,
                        "death_recovery answers accept no details; the native action is applied directly.");
            }
            return;
        }
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
        progressGate.forget(record.externalId());
        String type = switch (state) {
            case SUCCESS -> "completed";
            case CANCELLED -> "cancelled";
            default -> "failed";
        };
        JsonObject data = result == null ? new JsonObject() : compactAttentionResult(resultJson(result));
        String message = result == null ? state.name().toLowerCase() : result.message();
        publish(type, record, message, data);
    }

    /**
     * 长任务的进度事件：记分牌（done/total、remaining/initial、phase）变了才发布，
     * 过 40 刻地板；词汇契约与触发规则见 docs/architecture/07-attention.md。
     * 无键观察保持沉默；超 2 分钟无键改记一次日志提醒开发者，不进事件流。
     */
    void publishProgress(IntentTaskRecord record, Map<String, Object> observation, long gameTime) {
        ProgressGate.Decision decision = progressGate.consider(record.externalId(), observation, gameTime);
        if (decision == null) return;
        if (decision.keylessWarn()) {
            Constants.LOG.warn("task {} ran long without any scoreboard field (done/total/phase/remaining/initial)",
                    record.goal().ability());
            return;
        }
        JsonObject data = new JsonObject();
        observation.forEach((key, value) ->
                data.add(key, GSON.toJsonTree(value == null ? "" : value)));
        publish("task_progress", record, decision.message(), data);
    }

    public JsonObject attention(long afterCursor, int limit) {
        return attention(afterCursor, limit, null, null);
    }

    public JsonObject attention(long afterCursor, int limit, String streamId, UUID taskId) {
        return attention.read(afterCursor, limit, streamId, taskId);
    }

    /** 调试面板的只读事件条目：时间、类型、优先级与一句话内容。 */
    public record AttentionItem(java.time.Instant timestamp, String type, String priority, String message) {}

    /** 最近 limit 条事件按时间升序；纯读尾窗，不碰长轮询与订阅者自己的游标。 */
    public List<AttentionItem> recentAttention(int limit) {
        return attention.tail(limit);
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

    /** 收到的聊天进入独立聊天流，任务 Attention 继续只报告任务和身体事件。 */
    public void chatEvent(String type, String message, JsonObject data) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("chat event type is required");
        }
        chatFlow.publish(type, message, data == null ? new JsonObject() : data);
    }

    public JsonObject chat(long afterCursor, int limit, String streamId) {
        return chatFlow.read(afterCursor, limit, streamId);
    }

    public AutoCloseable subscribeChat(Consumer<JsonElement> listener) {
        return chatFlow.subscribe(listener);
    }

    /** 发布可能需要调用方处理的游戏事实，例如受伤、死亡或控制权变化。 */
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
        // 任务结束时完整交付材料、菜单、现场差异和取消来源，模型才能区分执行失败与玩家接管。
        JsonObject result = new JsonObject();
        for (String key : List.of("success", "message", "timed_out", "interrupted", "cancel_source", "data")) {
            if (source.has(key)) result.add(key, sanitizeAttentionValue(source.get(key)));
        }
        return result;
    }

    private static JsonElement sanitizeAttentionValue(JsonElement value) {
        // 通知与任务详情复用同一套语义证据规则；只清理内部动作脚本，不按字数、数组长度或字段白名单删事实。
        return new Gson().toJsonTree(SemanticResultView.jsonValue(value));
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

    private void reserveTaskCapacity() {
        // 接单前先移走最旧的终态记录；暂停或待决定的旧事仍要保留，不能为了新任务悄悄遗忘。
        var iterator = tasks.entrySet().iterator();
        while (tasks.size() >= MAX_TASKS && iterator.hasNext()) {
            var candidate = iterator.next();
            if (!candidate.getValue().getState().isTerminal()) continue;
            UUID id = candidate.getKey();
            iterator.remove();
            requestKeys.values().removeIf(id::equals);
        }
        if (tasks.size() >= MAX_TASKS) {
            throw new IllegalStateException("Too many unfinished semantic tasks; cancel an unwanted restored task before starting another.");
        }
    }

    private static <K, V> void trimOldest(LinkedHashMap<K, V> map, int max) {
        while (map.size() > max) {
            K first = map.keySet().iterator().next();
            map.remove(first);
        }
    }

    /** 区域用途由明确类型决定，不能只凭玩家起的地标名字推断保护规则。 */
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

        /** 旧存档缺失、不认识或损坏的区域类型按普通地点恢复，不能凭空授予保护含义。 */
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
