package org.maiwithu.maicraft.mcp;

import org.maiwithu.maicraft.core.task.entity.SheepTraits;
import org.maiwithu.maicraft.core.task.explore.ClientExplorationMemory;

import org.maiwithu.maicraft.core.inventory.InventoryComponentFacts;

import org.maiwithu.maicraft.core.integration.create.transmission.KineticSourceQueries;

import org.maiwithu.maicraft.core.integration.machine.ConstructionSiteGeometry;
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshots;
import org.maiwithu.maicraft.intent.MachinePlanPreflight;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.integration.backpack.BackpackStock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.maiwithu.maicraft.core.scan.DroppedItemObservation;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.server.ServerSessionRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.client.runtime.GameplayReminders;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticAbilityCatalog;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.intent.Plan;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.google.gson.Gson;
import java.util.Comparator;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.client.server.ClientMachineWatches;
import org.maiwithu.maicraft.client.server.ServerAssistClient;
import org.maiwithu.maicraft.core.integration.create.elevator.CreateElevatorTravel;
import org.maiwithu.maicraft.core.integration.create.elevator.ElevatorFloors;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFlightSession;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.tools.perception.LocalFloorSense;
import org.maiwithu.maicraft.core.tools.perception.BodyEnvironmentObservation;
import org.maiwithu.maicraft.core.tools.perception.TickRateObservation;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.mcp.knowledge.MinecraftKnowledgeSource;
import org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeService;

/** 把 MCP 请求接到当前游戏玩家：读世界、登记目标、控制任务、整理对外回复；不另外运行一套游戏逻辑。 */
public final class MaiCraftRuntimeFacade implements RuntimeFacade {

    private static final MaiCraftRuntimeFacade INSTANCE = new MaiCraftRuntimeFacade();

    private final IntentRuntime intents;
    private final NavigationOverview navigationOverview=new NavigationOverview();
    private final KnowledgeLibrary knowledge =
            new KnowledgeLibrary(new MinecraftKnowledgeSource());

    private MaiCraftRuntimeFacade() {
        this.intents = IntentRuntime.get();
    }

    /** 加载器共用此入口；创建时也确保语义总任务的执行器已登记，重复取得入口不会重复登记。 */
    public static MaiCraftRuntimeFacade instance() {
        return INSTANCE;
    }

    @Override
    public CompletionStage<JsonElement> perceive(JsonObject arguments) {
        // 遇到陌生物品时在资料线程联网；只读启动时冻结的版本信息，不等待游戏刻、不申请身体。
        if (WebKnowledgeService.VIEW.equals(arguments.get("view").getAsString()))
            return WebKnowledgeService.instance().request(arguments);
        // 文档可以离线读，周边地形要分几刻采样，等任务消息则挂起回复；其他查询交给游戏线程当次处理。
        if ("knowledge".equals(arguments.get("view").getAsString())) return knowledge(
                KnowledgeLibrary.perceptionRequest(arguments));
        if("surroundings".equals(arguments.get("view").getAsString())) return observeSurroundings(arguments);
        if ("attention".equals(arguments.get("view").getAsString())
                && arguments.get("wait_ms").getAsInt() > 0) {
            return waitForAttention(arguments);
        }
        return onClient(() -> perceiveOnClient(arguments));
    }
    private CompletionStage<JsonElement> observeSurroundings(JsonObject arguments) {
        // 逐段声明里没要地形缩略图就不必等采样：采样是这次请求最贵的一步（分帧扫半径 128 格、
        // 向下 256 格），而只要电梯楼层这类窄查询与地形无关，不要就不做，立即返回。
        // 普通周边查询默认不取大范围地形；需要规划远路时显式点名 terrain_overview 再等待采样。
        if (!PerceiveSections.wants(PerceiveSections.requested(arguments), "terrain_overview")) {
            return onClient(() -> perceiveOnClient(arguments));
        }
        // 记住这次查看的是哪个玩家，等地形采样准备好后再整理周边信息；中途换玩家就拒绝旧结果。
        var result=new CompletableFuture<JsonElement>();
        onClient(()->{
            if(result.isDone()) return new JsonObject();
            LocalPlayer expected=requireWorld().player;
            navigationOverview.prepare(expected).whenComplete((ignored,failure)->{
                if(result.isDone()) return;
                if(failure!=null) { result.completeExceptionally(failure); return; }
                try {
                    if(requireWorld().player!=expected) throw new IllegalStateException("terrain observation body changed");
                    result.complete(perceiveOnClient(arguments));
                } catch(RuntimeException unavailable) { result.completeExceptionally(unavailable); }
            });
            return new JsonObject();
        }).whenComplete((ignored,failure)->{ if(failure!=null) result.completeExceptionally(failure); });
        return result;
    }
    public static void tickObservation(LocalPlayer player) { INSTANCE.navigationOverview.tick(player); }

    @Override
    public CompletionStage<JsonElement> plan(JsonObject arguments) {
        // 先确认任务记忆属于当前世界，再检查目标并登记计划；此时不接管玩家，也不开始走路。
        return onClient(() -> {
            Minecraft minecraft = requireWorld();
            intents.bindForRequest(minecraft, minecraft.player);
            // 找回旧计划只读冻结输入，不重新编译、不再次领取角色控制权，也不把旧验证当作现场新验证。
            String savedId = nullableString(arguments, "plan_id");
            if (savedId != null) {
                Plan saved = intents.plan(UUID.fromString(savedId));
                if (saved == null) throw new IllegalArgumentException("unknown or expired plan_id: " + savedId);
                return PlanView.read(saved, arguments);
            }
            Goal goal = Goal.fromJson(arguments.getAsJsonObject("goal"));
            // 模型提交蓝图后由 plan 运行原生编译检查；已知错误直接反馈，不要求先单独 design_machine。
            JsonObject validation = MachinePlanPreflight.review(goal, minecraft.player, intents);
            if (!validation.get("valid").getAsBoolean()) {
                JsonObject rejected = new JsonObject();
                rejected.addProperty("status", "needs_revision");
                rejected.addProperty("ready_to_execute", false);
                rejected.add("validation", validation);
                // 场地编号缺失的规划失败已读到当前工地，外层直接交付恢复绑定，避免模型再发一次 perceive。
                for (String field : List.of("latest_snapshot", "failure_code", "previous_snapshot_id", "next_action"))
                    if (validation.has(field)) rejected.add(field, validation.get(field).deepCopy());
                return rejected;
            }
            Plan plan = intents.compile(goal, minecraft.level.getGameTime());
            JsonObject result = PlanView.summary(plan, arguments.get("limit").getAsInt());
            result.addProperty("status", "compiled");
            result.addProperty("ready_to_execute", true);
            result.add("validation", validation);
            return result;
        });
    }

    @Override
    public CompletionStage<JsonElement> execute(JsonObject arguments) {
        // 回复表示已登记任务，不表示事情已经做完；调用者拿 next_attention 继续等进度和结果。
        return onClient(() -> {
            Minecraft minecraft = requireWorld();
            LocalPlayer player = minecraft.player;
            intents.bindForRequest(minecraft, player);
            // 旧检查点恢复受阻时先拒绝接单，不能等请求角色接管后才发现新进度无法保存。
            intents.requireRecoveredState();
            Goal goal;
            UUID planId = null;
            if (arguments.has("plan_id") && !arguments.get("plan_id").isJsonNull()) {
                // 可以直接提交目标，也可以引用先前存好的计划；不存在的计划不能凭编号重建。
                planId = UUID.fromString(arguments.get("plan_id").getAsString());
                Plan plan = intents.plan(planId);
                if (plan == null) throw new IllegalArgumentException("unknown or expired plan_id: " + planId);
                goal = plan.goal();
            } else {
                goal = Goal.fromJson(arguments.getAsJsonObject("goal"));
            }
            String requestKey = nullableString(arguments, "request_key");
            UUID selectedPlanId = planId;
            // 网络重试只复用原任务，不能趁玩家已经接手时再次申请控制权；原目标校验仍由 execute 完成。
            boolean repeatedRequest = intents.taskForRequestKey(requestKey) != null;
            Supplier<IntentTaskRecord> submit = () -> intents.execute(player, goal, selectedPlanId, requestKey);
            IntentTaskRecord record = repeatedRequest ? submit.get() : dispatchExecution(goal, player, submit);
            JsonObject result = new JsonObject();
            result.addProperty("task_id", record.externalId().toString());
            result.addProperty("status", publicState(record));
            result.addProperty("accepted", true);
            result.addProperty("outcome", record.goal().outcome());
            if (requestKey != null) result.addProperty("request_key", requestKey);
            result.addProperty("control_status", repeatedRequest ? "not_requested" : IntentRuntime.isIndependentRequest(goal)
                    ? "not_required" : "takeover_requested");
            result.add("next_attention", AttentionSnapshot.continuation(
                    intents.attentionCheckpoint(), record.externalId().toString()));
            return result;
        });
    }

    /** 普通执行先请求接管玩家，创建任务失败时撤回新请求；只展示房屋设计时不用接管。 */
    static IntentTaskRecord dispatchExecution(Goal goal, LocalPlayer player, Supplier<IntentTaskRecord> execute) {
        // 随行补光配置在旧任务执行中也可提交，不能为了开关照明重新接管身体。
        if (IntentRuntime.isIndependentRequest(goal)) return execute.get();
        ClientActorBoundary.AutomationRequest control = ClientRuntime.requestAutomationControl(player);
        // 此处只处理新提交；创建任务失败时撤回本次新请求，不能撤销此前已生效的控制权。
        try { return execute.get(); }
        catch (RuntimeException failure) {
            ClientRuntime.rollbackAutomationControl(control);
            throw failure;
        }
    }

    @Override
    public CompletionStage<JsonElement> task(JsonObject arguments) {
        return onClient(() -> taskOnClient(arguments));
    }

    @Override
    public CompletionStage<JsonElement> readAttention() {
        JsonObject args = new JsonObject();
        args.addProperty("after_cursor", 0);
        // 资源订阅重连也只交付少量近期消息，避免每次门铃读取重复列出二十份任务记录。
        args.addProperty("limit", 5);
        return onClient(() -> {
            JsonObject result = attentionOnClient(args);
            // 资源重连可能只读到旧事件页，仍把当前有效提醒直接交付，避免反复翻页才知道正在遇袭。
            if (result.get("runtime_available").getAsBoolean()) result.add("reminders", reminders());
            return result;
        });
    }

    @Override public JsonArray reminders() { return GameplayReminders.snapshot(); }

    @Override
    public CompletionStage<JsonElement> readChat() {
        // 快照读最近五十条聊天；聊天缓冲不依赖身体，没进世界也能读已收到的消息。
        return onClient(() -> intents.chat(0, 50, null));
    }

    @Override public CompletionStage<JsonElement> knowledge(JsonObject arguments) {
        return onClient(() -> knowledge.request(arguments));
    }

    @Override
    public AutoCloseable subscribeAttention(Consumer<JsonElement> listener) {
        return intents.subscribeAttention(listener);
    }

    @Override
    public AutoCloseable subscribeChat(Consumer<JsonElement> listener) {
        return intents.subscribeChat(listener);
    }

    private JsonElement perceiveOnClient(JsonObject arguments) {
        // 注意流与轻量能力搜索先分流；其余现场状态、完整能力和任务查询要求玩家处于可用世界。
        if ("attention".equals(arguments.get("view").getAsString())) return attentionOnClient(arguments);
        // 默认一次列全能力名，按需搜索或展开概要；选中后 focus 直接读取契约与真实可用性，无需经过概要层。
        if ("abilities".equals(arguments.get("view").getAsString()) && nullableString(arguments, "query") != null)
            return AbilitySearch.search(nullableString(arguments, "query"));
        if ("abilities".equals(arguments.get("view").getAsString()) && nullableString(arguments, "focus") == null)
            return AbilitySearch.index(arguments);
        Minecraft minecraft = requireWorld();
        LocalPlayer player = minecraft.player;
        intents.bindForRequest(minecraft, player);
        String view = arguments.get("view").getAsString();
        // 先确定显式段或默认段，再按同一清单投影；周边默认不准备大范围地形，避免无关采样和重复上下文。
        List<String> sections = PerceiveSections.requested(arguments);
        return switch (view) {
            case "situation" -> {
                JsonObject situation = situation(player);
                String focus = nullableString(arguments, "focus");
                if("maicraft:travel".equals(focus) || "maicraft:elevators".equals(focus))
                    situation.add("elevators",new Gson().toJsonTree(
                            ElevatorFloors.overview(player)));
                if ("maicraft:navigation".equals(focus) || "maicraft:transport".equals(focus)) {
                    var gson = new Gson();
                    situation.add("actor", gson.toJsonTree(ClientRuntime.actor().diagnosticState()));
                    situation.addProperty("tick_stage", ClientRuntime.lastTickStage());
                    situation.addProperty("controlling_task", CompanionTickDispatcher.controllingTask());
                    situation.add("navigation", gson.toJsonTree(
                            EmbeddedBaritoneRuntime.diagnosticState()));
                    situation.add("collision_geometry", NearbyCollisionPerception.observe(player));
                    situation.add("transport", gson.toJsonTree(
                            TransportRuntime.diagnosticState()));
                    situation.add("landing_assist", gson.toJsonTree(
                            LandingAssistPolicy.diagnosticState()));
                    situation.add("jetpack", gson.toJsonTree(
                            JetpackFlightSession.inspect(player)));
                    situation.add("elevators", gson.toJsonTree(
                            CreateElevatorTravel.inspect(player)));
                }
                if ("maicraft:physical_structures".equals(focus) || "maicraft:navigation".equals(focus)
                        || "maicraft:transport".equals(focus))
                    situation.add("physical_structures", PhysicalStructurePerception.observe(player));
                // 段已全部装完（含 focus 追加的诊断段）再裁剪：没点名的段不进响应，点名的段一定在。
                yield PerceiveSections.select(situation, sections);
            }
            case "surroundings" -> {
                JsonObject observed = surroundings(player, nullableString(arguments, "focus"), arguments.get("limit").getAsInt(),
                        PerceiveSections.wants(sections, "terrain_overview"), PerceiveSections.wants(sections, "nearby_facilities"));
                JsonObject selected = PerceiveSections.select(observed, sections);
                // 默认省略的段仍可发现；未看到地形不能推断外面没有可走平台，未扫设施不能推断附近没有可用设备。
                if (!arguments.has("sections") || arguments.get("sections").isJsonNull()) {
                    JsonArray extra = new JsonArray();
                    extra.add("terrain_overview"); extra.add("nearby_facilities");
                    selected.add("additional_sections", extra);
                }
                yield selected;
            }
            case "abilities" -> abilities(nullableString(arguments, "focus"));
            case "kinetic_sources" -> KineticSourceQueries.observe(player, arguments.get("radius").getAsInt(),
                    arguments.get("limit").getAsInt(), nullableString(arguments, "query"), nullableString(arguments, "focus"),
                    (label, at) -> intents.remember(label, new Goal.WorldPosition(at.getX(), at.getY(), at.getZ(), player.level().dimension().location().toString())));
            case "construction_site" -> {
                // 同一次只读感知保留场地锚点与完整结构；规划后可直接交给施工，不另派勘察任务。
                BlockPos anchor = player.blockPosition();
                String label = nullableString(arguments, "label");
                if (label == null) label = "site_" + Integer.toHexString(player.level().dimension().hashCode())
                        + "_" + anchor.getX() + "_" + anchor.getY() + "_" + anchor.getZ();
                // 工地观察更新现场而不跟随取料后的脚位；旧蓝图与新观察继续共用相同的相对坐标原点。
                var fixed = intents.constructionAnchor(label, new Goal.WorldPosition(anchor.getX(), anchor.getY(), anchor.getZ(),
                        player.level().dimension().location().toString()));
                anchor = new BlockPos(fixed.x(), fixed.y(), fixed.z());
                var snapshot = MachineSnapshots.constructionSite(player, label, anchor, arguments.get("radius").getAsInt());
                yield ConstructionSiteGeometry.describe(snapshot);
            }
            case "tasks" -> {
                String rawTaskId = nullableString(arguments, "task_id");
                if (rawTaskId != null) {
                    yield TaskView.status(requireTask(UUID.fromString(rawTaskId)));
                }
                yield TaskView.list(intents.tasks(256), arguments.get("offset").getAsInt(), arguments.get("limit").getAsInt());
            }
            case "landmarks" -> landmarks(player);
            // 注册目录按需分页读取，默认身体状态不会夹带整套模组群系和历史跑图成果。
            case "exploration" -> {
                String focus = nullableString(arguments, "focus");
                yield focus != null && List.of("biomes", "biome_tags", "structures").contains(focus)
                        ? ExplorationCatalog.read(player.registryAccess(), focus, nullableString(arguments, "query"),
                                arguments.get("offset").getAsInt(), arguments.get("limit").getAsInt())
                        : ExplorationMemoryView.read(ClientExplorationMemory.identity(), focus, nullableString(arguments, "query"),
                                arguments.get("offset").getAsInt(), arguments.get("limit").getAsInt());
            }
            case "machines" -> {
                var report = ClientMachineCatalog.view(player, nullableString(arguments,"focus"));
                report.add("production_watches",ClientMachineWatches.view(player));
                yield report;
            }
            case "machine_menu" -> MachineMenu.inspect(player);
            default -> throw new IllegalArgumentException("unknown perceive view: " + view);
        };
    }

    private JsonElement taskOnClient(JsonObject arguments) {
        // 查询或控制的是 MCP 的 UUID 总任务编号，不是内部 t 开头的动作编号。
        Minecraft minecraft = requireWorld();
        LocalPlayer player = minecraft.player;
        intents.bindForRequest(minecraft, player);
        String action = arguments.get("action").getAsString();
        if ("list".equals(action)) {
            // 带 request_key 时只找对应请求；否则按最近顺序列出任务，方便断线后找回上次接单结果。
            JsonArray tasks = new JsonArray();
            String requestKey = nullableString(arguments, "request_key");
            if (requestKey != null) {
                IntentTaskRecord record = intents.taskForRequestKey(requestKey);
                if (record != null) tasks.add(TaskView.status(record));
            } else {
                // 列表只提供任务身份与当前进度，完整证据由 get+path 分页读取。
                return TaskView.list(intents.tasks(256), arguments.get("offset").getAsInt(), arguments.get("limit").getAsInt());
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
                // 这里只写暂停标记并发消息；是否已安全停下，需要后面的身体调度器实际处理。
                if (!record.pause(now, "paused_by_mcp")) {
                    throw new IllegalStateException("task is already terminal");
                }
                intents.paused(record, "Paused by MCP request");
            }
            case "resume" -> {
                // 从磁盘恢复的任务只存在于任务记录里，继续前要重新放入调度器；这可能替换当前任务。
                intents.requireCurrentBinding(record);
                boolean restoredDetached = record.restoredDetached();
                if (!record.resume()) {
                    throw new IllegalStateException(record.decisionSnapshot() != null
                            ? "task needs task action=answer"
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
                // 恢复记录尚未占用身体，只结算它本身；已有身体任务则通过调度器停止实际动作。
                if (record.getState().isTerminal()) {
                    throw new IllegalStateException("task is already terminal");
                }
                if (record.restoredDetached()) {
                    intents.cancelRestored(record, now);
                } else if (!CompanionTickDispatcher.cancel(record.publicId())) {
                    throw new IllegalStateException("task is no longer in the active scheduler slot");
                }
            }
            case "answer" -> {
                // 先核对答复参数，再接受问题编号和选项；死亡后的复活／旁观有单独的游戏操作流程。
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
                    boolean applied = GameplayAttentionMonitor.applyDeathDecision(deathEffect, player, record);
                    if (intents.isDeathRecoveryHost(record)) {
                        if (applied) {
                            finishDeathRecoveryHost(deathEffect, record, now);
                        } else {
                            // 原生动作没能发出：换发新决策编号重新挂起，迟到答复不会落在已消费的问题上。
                            intents.requestDeathDecision(record,
                                    contextBoolean(pendingDecision, "hardcore"),
                                    contextBoolean(pendingDecision, "spectator"),
                                    pendingDecision == null ? new JsonObject() : pendingDecision.context());
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException("unknown task action: " + action);
        }
        JsonObject result = TaskView.read(record, arguments);
        result.add("next_attention", AttentionSnapshot.continuation(
                intents.attentionCheckpoint(), record.externalId().toString()));
        return result;
    }

    private JsonObject situation(LocalPlayer player) {
        // 直接读取当前身体状态和背包摘要；这些是观察结果，不表示某个目标已经完成。
        JsonObject result = BodyEnvironmentObservation.describe(player);
        result.add("view", PhysicalStructurePerception.view(player));
        result.addProperty("health", player.getHealth());
        result.addProperty("max_health", player.getMaxHealth());
        result.addProperty("food", player.getFoodData().getFoodLevel());
        result.addProperty("air", player.getAirSupply());
        WorldTimeSemantics.Phase timePhase = WorldTimeSemantics.phase(player.level());
        result.addProperty("day", WorldTimeSemantics.isDaytime(player.level()));
        result.addProperty("is_daytime", WorldTimeSemantics.isDaytime(player.level()));
        result.addProperty("time_phase", timePhase.id());
        result.addProperty("time_of_day", WorldTimeSemantics.timeOfDay(player.level()));
        result.addProperty("day_index", WorldTimeSemantics.dayIndex(player.level()));
        result.addProperty("weather", player.level().isThundering()
                ? "thunder" : player.level().isRaining() ? "rain" : "clear");
        // 近窗刻率：失焦限流把世界刻放慢时调用方一眼可辨，不用再靠双采样 game_time 差值排障。
        result.add("tick_rate", TickRateObservation.observe(
                System.currentTimeMillis(), player.level().getGameTime()));
        result.add("inventory", inventorySummary(player));
        // 随身储物与主背包分开显示：没观察过的包明确未知，不把未打开当成空包。
        result.add("carried_storage", new Gson().toJsonTree(BackpackStock.facts(player)));
        result.add("equipment", equipmentSummary(player));
        if (player.getVehicle() != null) {
            result.addProperty("vehicle_type", BuiltInRegistries.ENTITY_TYPE
                    .getKey(player.getVehicle().getType()).toString());
        }
        IntentTaskRecord current = currentIntent();
        if (current != null) result.add("task", taskSummary(current));
        // 死亡屏幕上血量为零不是唯一信号：待答的重生问题与持有者编号一并给出，观察完即可直接答复。
        IntentTaskRecord death = intents.deathDecisionHolder();
        if (death != null) result.add("death_decision", TaskView.deathDecision(death));
        return result;
    }

    private JsonObject surroundings(LocalPlayer player, String focus, int limit, boolean terrain, boolean facilities) {
        // 汇总附近实体、告示牌、可走区域和船／电梯；大范围地形与设施盘点来自点名后才执行的采样与扫描。
        // 角色先知道自己是否站稳、是否入水，再看周围候选地面，不能把区域内的水误认为身体所在位置。
        JsonObject result = BodyEnvironmentObservation.describe(player);
        result.addProperty("sky_light", player.level().getMaxLocalRawBrightness(player.blockPosition()));
        player.level().getBiome(player.blockPosition()).unwrapKey()
                .ifPresent(key -> result.addProperty("biome", key.location().toString()));

        JsonArray entities = new JsonArray();
        AABB area = player.getBoundingBox().inflate(16.0);
        List<Entity> nearby = player.level().getEntities(player, area, entity -> !entity.isRemoved()
                        && (!(entity instanceof ItemEntity drop) || !drop.getItem().isEmpty()))
                .stream().sorted(Comparator.comparingDouble(player::distanceToSqr)).toList();
        int hostileCount = (int) nearby.stream().filter(entity -> entity instanceof Enemy).count();
        // 模型按每堆的物品、数量和位置选择拾取目标；附近实体多时也必须保留完整候选。
        for (Entity entity : nearby) {
            JsonObject item = new JsonObject();
            item.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            item.addProperty("distance", Math.round(player.distanceTo(entity) * 10.0) / 10.0);
            // 附近有多只羊时直接交付各自颜色、年龄和剪毛状态，不要求为辨色另开动作任务。
            SheepTraits.observe(entity, item);
            if (entity instanceof ItemEntity drop) {
                DroppedItemObservation.describe(player, drop).entrySet()
                        .forEach(entry -> item.add(entry.getKey(), entry.getValue()));
            }
            entities.add(item);
        }
        result.add("nearby_entities", entities);
        JsonObject signs = NearbySignPerception.observe(player, focus, limit);
        result.add("nearby_signs", signs.remove("signs"));
        result.add("sign_observation", signs);
        result.add("local_decision_summary", localDecisionSummary(player, hostileCount));
        if (terrain) result.add("terrain_overview",navigationOverview.describe(player));
        if (facilities) result.add("nearby_facilities", NearbyFacilityPerception.observe(player));
        result.add("elevators",new Gson().toJsonTree(
                ElevatorFloors.overview(player)));
        result.add("view", PhysicalStructurePerception.view(player));
        result.add("physical_structures", PhysicalStructurePerception.observe(player));
        return result;
    }

    /** 把脚下附近的可走区域和危险汇总出来，方便调用者判断下一步，不逐格列出全部方块。 */
    private JsonObject localDecisionSummary(LocalPlayer player, int hostileCount) {
        JsonObject summary = LocalFloorSense.describe(player);
        if (hostileCount > 0) {
            JsonObject hostile = new JsonObject();
            hostile.addProperty("kind", "hostile_entities");
            hostile.addProperty("samples", hostileCount);
            summary.getAsJsonArray("hazards").add(hostile);
        }
        return summary;
    }
    private JsonObject abilities(String focus) {
        // 注册、已实现的后端和当前目标的执行条件分开报告；未知前置条件不能写成可执行。
        JsonObject serverAssistance = ServerAssistClient.capabilityReport();
        if ("maicraft:server_assistance".equals(focus)) return serverAssistance;
        if (!IntentRuntime.KNOWN_ABILITIES.contains(focus)) throw new IllegalArgumentException("Unknown ability; discover one with abilities query");
        JsonArray abilities = new JsonArray();
        for (String ability : IntentRuntime.KNOWN_ABILITIES.stream().sorted().toList()) {
            if (focus != null && !focus.equals(ability)) continue;
            // 旧附魔请求继续有效，但默认能力发现只展示统一机器入口；显式focus仍可读取兼容契约。
            if (focus == null && SemanticAbilityCatalog.compatibilityAlias(ability)) continue;
            JsonObject item = new JsonObject();
            item.addProperty("ability", ability);
            SemanticAbilityAvailability.describe(item, ability,
                    BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse("create:shaft")),
                    Minecraft.getInstance().player != null && Minecraft.getInstance().player.isAlive());
            SemanticAbilityAvailability.production(item, ability, serverAssistance);
            SemanticAbilityAvailability.physics(item, ability, serverAssistance);
            item.addProperty("mode", abilityMode(ability));
            item.add("contract", SemanticAbilityCatalog.describe(ability));
            abilities.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("semantic_abilities", abilities);
        // 能力契约只附全局控制限制；所有底层操作的诊断表由专用 focus 按需读取。
        JsonObject support = new JsonObject();
        for (String key : List.of("state", "control_allowed")) if (serverAssistance.has(key)) support.add(key, serverAssistance.get(key));
        if (serverAssistance.has("unresolved_mutations")) support.addProperty("unresolved_mutation_count", serverAssistance.getAsJsonArray("unresolved_mutations").size());
        result.add("server_assistance", support);
        result.addProperty(
                "boundary",
                "Submit declared semantic fields; MaiCraft owns routes, gestures and confirmation. Registration/support is not proof of current executability.");
        // 运行时级授权键不进各能力参数表：任何 goal.parameters 都可携带，由运行时读取（death_recovery 用）。
        JsonObject runtimeKeys = new JsonObject();
        runtimeKeys.addProperty("auto_respawn",
                "accepted in goal.parameters of any ability; the runtime immediately requests native respawn when the agent dies, without a decision round-trip");
        runtimeKeys.addProperty("recover_after_death",
                "accepted in goal.parameters of any ability; after respawn the task stays paused for safety reassessment and item recovery");
        result.add("runtime_goal_keys", runtimeKeys);
        return result;
    }

    private JsonObject landmarks(LocalPlayer player) {
        // 查询只列手工登记的名字和区域类型；精确坐标留在 Mod，跑图与机器的补充标签由各自视图提供。
        // available_here 仅比较维度，空维度也会匹配；它不证明区块已加载、角色已到达或这次登记已完成落盘。
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

    static JsonObject taskSnapshot(IntentTaskRecord record) {
        // 完整查询包含原始目标、当前步骤、已完成结果和失败尝试；最后再附暂停、待答问题或最终结果。
        JsonObject result = new JsonObject();
        result.addProperty("task_id", record.externalId().toString());
        if (record.planId() != null) result.addProperty("plan_id", record.planId().toString());
        result.addProperty("state", publicState(record));
        result.addProperty("internal_state", record.getState().name().toLowerCase());
        result.addProperty("step_index", record.stepIndex());
        result.addProperty("step_count", record.steps().size());
        result.addProperty("skipped_step_count", record.skippedStepCount());
        result.addProperty("all_steps_succeeded", record.allStepsSucceeded());
        result.addProperty("all_steps_scope", "current_steps_after_recovery_or_replacement");
        result.add("goal", record.goal().toJson());
        Goal current = currentGoal(record);
        if (current != null) result.add("current_goal", current.toJson());

        JsonArray steps = new JsonArray();
        for (IntentTaskRecord.StepSnapshot step : record.stepResults()) {
            JsonObject item = new JsonObject();
            item.addProperty("index", step.index());
            item.addProperty("ability", step.ability());
            item.addProperty("success", step.success());
            item.addProperty("skipped", step.skipped());
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

        boolean terminalState = record.getState().isTerminal() || record.terminalSnapshot() != null;
        // 已结束的任务只展示结束结果，不再带旧的“正在执行”“暂停”或“等回答”，避免状态互相矛盾。
        if (!terminalState && record.activeExecution() != null) {
            result.add("active_execution", record.activeExecution());
        }
        if (!terminalState && record.pauseSnapshot() != null) {
            JsonObject pause = new JsonObject();
            pause.addProperty("reason", record.pauseSnapshot().reason());
            pause.addProperty("game_time", record.pauseSnapshot().gameTime());
            result.add("pause", pause);
        }
        // 执行中途的自卫插曲按路径同样可读；结束后的完整账已在终态回执里，不再重复一份。
        if (!terminalState && !record.selfDefenseExcursions().isEmpty()) {
            result.add("self_defense_excursions", record.selfDefenseExcursions());
        }
        // 终态任务只保留死亡恢复问题；它在承接任务被取消后仍须可读、可按 /decision 路径找回。
        if (record.decisionSnapshot() != null && (!terminalState || record.deathDecisionPending())) {
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

    /** 失败时把“哪些已做、哪些未做”提到回复外层，调用者不必深入失败背景才能找到。 */
    private static void copyEffectLedger(JsonObject target, JsonObject decisionContext) {
        if (decisionContext == null || !decisionContext.has("failure")
                || !decisionContext.get("failure").isJsonObject()) return;
        JsonObject failure = decisionContext.getAsJsonObject("failure");
        if (!failure.has("data") || !failure.get("data").isJsonObject()) return;
        JsonObject data = failure.getAsJsonObject("data");
        for (String key : List.of("completed_effects", "remaining_effects", "skipped_steps")) {
            if (data.has(key) && data.get(key).isJsonArray()) {
                target.add(key, data.get(key).deepCopy());
            }
        }
    }

    static JsonObject taskSummary(IntentTaskRecord record) {
        JsonObject result = new JsonObject();
        result.addProperty("task_id", record.externalId().toString());
        result.addProperty("state", publicState(record));
        result.addProperty("outcome", record.goal().outcome());
        // 原来的“做出精密构件”等文字只作为请求标签；完成事实必须来自实际运行步骤的回执。
        result.addProperty("outcome_scope", "requested_intent");
        Goal current = currentGoal(record);
        if (current != null) result.addProperty("current_outcome", current.outcome());
        result.addProperty("step_index", record.stepIndex());
        result.addProperty("step_count", record.steps().size());
        result.addProperty("skipped_step_count", record.skippedStepCount());
        result.addProperty("all_steps_succeeded", record.allStepsSucceeded());
        result.addProperty("all_steps_scope", "current_steps_after_recovery_or_replacement");
        return result;
    }

    /** 保留原始请求，当前要做的事从实际步骤读取，包含后来替换或插入的恢复目标。 */
    private static Goal currentGoal(IntentTaskRecord record) {
        int index = record.stepIndex();
        return !record.getState().isTerminal() && record.terminalSnapshot() == null
                && index >= 0 && index < record.steps().size() ? record.steps().get(index) : null;
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
        // 随身总量包含副手与穿戴，并标明各处数量；换手不会被误报为材料消失，主手也不会重复计数。
        return InventoryComponentFacts.carried(player.getInventory(), player.registryAccess());
    }

    private static JsonObject equipmentSummary(LocalPlayer player) {
        // 装备段提供已计入 inventory 的手别、护甲位置与耐久明细，不是一份额外库存。
        JsonObject result = new JsonObject();
        addStack(result, "main_hand", player.getMainHandItem(), player);
        addStack(result, "off_hand", player.getOffhandItem(), player);
        String[] armorSlots = {"feet", "legs", "chest", "head"};
        for (int index = 0;
             index < Math.min(armorSlots.length, player.getInventory().armor.size());
             index++) {
            addStack(result, armorSlots[index], player.getInventory().armor.get(index), player);
        }
        return result;
    }

    private static void addStack(JsonObject target, String key, ItemStack stack, LocalPlayer player) {
        if (stack == null || stack.isEmpty()) return;
        JsonObject value = new JsonObject();
        value.addProperty("item_id",
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        value.addProperty("count", stack.getCount());
        value.addProperty("damage", stack.getDamageValue());
        value.addProperty("max_damage", stack.getMaxDamage());
        // 当前手里的装配进度直接可见，省去仅为看组件而额外取放物品的原生动作。
        InventoryComponentFacts.observe(stack, player.registryAccess()).entrySet().forEach(entry -> value.add(entry.getKey(), entry.getValue()));
        target.add(key, value);
    }

    private IntentTaskRecord currentIntent() {
        return CompanionTickDispatcher.current() instanceof IntentTaskRecord record ? record : null;
    }

    private IntentTaskRecord requireTask(UUID id) {
        IntentTaskRecord record = intents.task(id);
        if (record == null) throw new IllegalArgumentException("unknown task_id: " + id);
        return record;
    }

    /** 无宿主死亡恢复态：答复已同步应用，承载记录随之结算；task get 仍可按原编号查阅，不会凭空消失。 */
    private void finishDeathRecoveryHost(
            GameplayAttentionMonitor.DeathDecisionEffect effect, IntentTaskRecord record, long now) {
        if (effect == GameplayAttentionMonitor.DeathDecisionEffect.CANCEL_TASK) {
            intents.finishDeathRecovery(record, TaskState.CANCELLED,
                    TaskResult.cancelled("death recovery answered: cancel_task", "death_recovery_answer"), now);
            return;
        }
        boolean spectate = effect == GameplayAttentionMonitor.DeathDecisionEffect.REQUEST_NATIVE_SPECTATE;
        intents.finishDeathRecovery(record, TaskState.SUCCESS, new TaskResult(true,
                spectate ? "Native spectate was requested by answering the death decision."
                        : "Native respawn was requested by answering the death decision.",
                false, false, Map.of()), now);
    }

    private static boolean contextBoolean(IntentTaskRecord.DecisionSnapshot decision, String key) {
        JsonObject context = decision == null ? null : decision.context();
        return context != null && context.has(key) && context.get(key).getAsBoolean();
    }

    private static String publicState(IntentTaskRecord record) {
        // 结束结果优先；没结束时，等回答比普通暂停更具体；都没有才使用内部 RUNNING 等状态。
        if (record.terminalSnapshot() != null) return record.terminalSnapshot().state().name().toLowerCase();
        if (record.getState().isTerminal()) return record.getState().name().toLowerCase();
        if (record.decisionSnapshot() != null) return "waiting_for_decision";
        if (record.pauseSnapshot() != null) return "paused";
        return record.getState().name().toLowerCase();
    }

    private static String abilityMode(String ability) {
        return switch (ability) {
            case "maicraft:remember_place" -> "local_memory";
            case "maicraft:design_build" -> "read_only_build_preview";
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
        // 网络请求可能早于首个游戏刻到达；世界已加载也必须等服务端确认，才能感知或提交任务。
        ServerSessionRuntime.requireConfirmed(minecraft);
        return minecraft;
    }

    /** 订阅任务消息后等回复；游戏仍照常运行，是否因此唤醒模型由外部 MCP 客户端决定。 */
    private CompletionStage<JsonElement> waitForAttention(JsonObject arguments) {
        return AttentionWait.start(() -> attentionOnClient(arguments), intents::subscribeAttention,
                work -> Minecraft.getInstance().execute(work), arguments.get("wait_ms").getAsInt());
    }

    private JsonObject attentionOnClient(JsonObject arguments) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean available = minecraft.player != null && minecraft.level != null && minecraft.gameMode != null
                && ServerSessionRuntime.serverConfirmed(minecraft) && intents.attentionAvailable();
        return AttentionSnapshot.read(intents, arguments, available);
    }

    private static CompletionStage<JsonElement> onClient(Supplier<? extends JsonElement> operation) {
        // 网络线程不直接碰游戏对象，排到客户端线程处理；请求还没开始就被取消时，排队的代码不会再执行。
        ClientCallFuture future = new ClientCallFuture();
        Runnable work = () -> {
            if (!future.begin()) return;
            try {
                // 聊天、知识等不经过 requireWorld 的入口也不能在未获服主支持的世界中提前使用。
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.level != null) ServerSessionRuntime.requireConfirmed(minecraft);
                future.completeCall(operation.get());
            } catch (Throwable throwable) {
                // Error 系（LinkageError 等）不进 EmbeddedMcpService 对 Exception 的日志分支，
                // 曾把 cook 缺类事故在服务端日志里藏得零痕迹（084）；参数类拒绝无需 error 噪音。
                if (!(throwable instanceof IllegalArgumentException
                        || throwable instanceof IllegalStateException)) {
                    Constants.LOG.error("[maicraft-mcp] Client-thread runtime call failed", throwable);
                }
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

    private static final class ClientCallFuture extends CompletableFuture<JsonElement>
            implements RuntimeFacade.ManagedCall {
        // 记录“还在排队”还是“已经开始”，网络超时时才知道能否保证这次请求没改变任何事情。
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int SETTLED = 2;
        private static final int CANCELLED = 3;

        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private boolean begin() {
            return state.compareAndSet(QUEUED, RUNNING);
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
            // 普通客户端调用只从执行中进入已结算；等待任务消息的挂起由 AttentionWait 单独管理。
            return state.compareAndSet(RUNNING, SETTLED);
        }

        @Override
        public RuntimeFacade.CancellationDisposition cancelCall() {
            // 尚未开始可以撤回；已经开始则只报告无法撤回，不能把一个已开工请求说成什么都没做。
            while (true) {
                int current = state.get();
                if (current == QUEUED && state.compareAndSet(QUEUED, CANCELLED)) {
                    super.cancel(false);
                    return RuntimeFacade.CancellationDisposition.CANCELLED_BEFORE_START;
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
