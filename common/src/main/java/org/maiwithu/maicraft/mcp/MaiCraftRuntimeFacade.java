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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 把 MCP 请求接到当前游戏玩家：读世界、登记目标、控制任务、整理对外回复；不另外运行一套游戏逻辑。 */
public final class MaiCraftRuntimeFacade implements RuntimeFacade {

    private static final MaiCraftRuntimeFacade INSTANCE = new MaiCraftRuntimeFacade();

    private final IntentRuntime intents;
    private final NavigationOverview navigationOverview=new NavigationOverview();
    private final org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary knowledge =
            new org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary(new org.maiwithu.maicraft.mcp.knowledge.MinecraftKnowledgeSource());

    private MaiCraftRuntimeFacade() {
        this.intents = IntentRuntime.get();
    }

    /** Stable loader entry point; construction also idempotently registers IntentTaskRecord. */
    public static MaiCraftRuntimeFacade instance() {
        return INSTANCE;
    }

    @Override
    public CompletionStage<JsonElement> perceive(JsonObject arguments) {
        // 文档可以离线读，周边地形要分几刻采样，等任务消息则挂起回复；其他查询交给游戏线程当次处理。
        if ("knowledge".equals(arguments.get("view").getAsString())) return knowledge(
                org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary.perceptionRequest(arguments));
        if("surroundings".equals(arguments.get("view").getAsString())) return observeSurroundings(arguments);
        if ("attention".equals(arguments.get("view").getAsString())
                && arguments.get("wait_ms").getAsInt() > 0) {
            return waitForAttention(arguments);
        }
        return onClient(() -> perceiveOnClient(arguments));
    }
    private CompletionStage<JsonElement> observeSurroundings(JsonObject arguments) {
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
            Goal goal = Goal.fromJson(arguments.getAsJsonObject("goal"));
            Plan plan = intents.compile(goal, minecraft.level.getGameTime());
            JsonObject result = plan.toJson();
            result.addProperty("status", "compiled");
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
            IntentTaskRecord record = dispatchExecution(goal, player,
                    () -> intents.execute(player, goal, selectedPlanId, requestKey));
            JsonObject result = new JsonObject();
            result.addProperty("task_id", record.externalId().toString());
            result.addProperty("status", publicState(record));
            result.addProperty("accepted", true);
            result.addProperty("outcome", record.goal().outcome());
            if (requestKey != null) result.addProperty("request_key", requestKey);
            result.addProperty("control_status", IntentRuntime.isReadOnlyDesign(goal)
                    ? "not_required" : "takeover_requested");
            result.add("next_attention", AttentionSnapshot.continuation(
                    intents.attentionCheckpoint(), record.externalId().toString()));
            return result;
        });
    }

    /** 普通执行先请求接管玩家，创建任务失败时撤回新请求；只展示房屋设计时不用接管。 */
    static IntentTaskRecord dispatchExecution(Goal goal, LocalPlayer player, Supplier<IntentTaskRecord> execute) {
        if (IntentRuntime.isReadOnlyDesign(goal)) return execute.get();
        ClientActorBoundary.AutomationRequest control = ClientRuntime.requestAutomationControl(player);
        // 重复 request_key 的查重在 execute.get() 内部发生，因此这一步可能在发现“旧任务已存在”前执行。
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
        args.addProperty("limit", 20);
        return onClient(() -> attentionOnClient(args));
    }

    @Override public CompletionStage<JsonElement> knowledge(JsonObject arguments) {
        return onClient(() -> knowledge.request(arguments));
    }

    @Override
    public AutoCloseable subscribeAttention(Consumer<JsonElement> listener) {
        return intents.subscribeAttention(listener);
    }

    private JsonElement perceiveOnClient(JsonObject arguments) {
        // 状态、能力、任务和地标都在这里分流；除 Attention 外，这一路先要求玩家处于一个可用世界。
        if ("attention".equals(arguments.get("view").getAsString())) return attentionOnClient(arguments);
        Minecraft minecraft = requireWorld();
        LocalPlayer player = minecraft.player;
        intents.bindForRequest(minecraft, player);
        String view = arguments.get("view").getAsString();
        return switch (view) {
            case "situation" -> {
                JsonObject situation = situation(player);
                String focus = nullableString(arguments, "focus");
                if("maicraft:travel".equals(focus) || "maicraft:elevators".equals(focus))
                    situation.add("elevators",new com.google.gson.Gson().toJsonTree(
                            org.maiwithu.maicraft.core.integration.create.elevator.ElevatorFloors.overview(player)));
                if ("maicraft:navigation".equals(focus) || "maicraft:transport".equals(focus)) {
                    var gson = new com.google.gson.Gson();
                    situation.add("actor", gson.toJsonTree(ClientRuntime.actor().diagnosticState()));
                    situation.addProperty("tick_stage", ClientRuntime.lastTickStage());
                    situation.addProperty("controlling_task", CompanionTickDispatcher.controllingTask());
                    situation.add("navigation", gson.toJsonTree(
                            org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime.diagnosticState()));
                    situation.add("collision_geometry", NearbyCollisionPerception.observe(player));
                    situation.add("transport", gson.toJsonTree(
                            org.maiwithu.maicraft.core.pathing.transport.TransportRuntime.diagnosticState()));
                    situation.add("landing_assist", gson.toJsonTree(
                            org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy.diagnosticState()));
                    situation.add("jetpack", gson.toJsonTree(
                            org.maiwithu.maicraft.core.integration.jetpack.JetpackFlightSession.inspect(player)));
                    situation.add("elevators", gson.toJsonTree(
                            org.maiwithu.maicraft.core.integration.create.elevator.CreateElevatorTravel.inspect(player)));
                }
                if ("maicraft:physical_structures".equals(focus) || "maicraft:navigation".equals(focus)
                        || "maicraft:transport".equals(focus))
                    situation.add("physical_structures", PhysicalStructurePerception.observe(player));
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
            case "landmarks" -> landmarks(player);
            case "machines" -> org.maiwithu.maicraft.core.integration.machine.MachineSnapshots.summaries(player);
            case "machine_menu" -> org.maiwithu.maicraft.core.integration.machine.MachineMenu.inspect(player);
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
                // 当前只会取消仍在调度器里的任务；尚未 resume 的恢复记录在这里会被判为不能取消。
                if (record.getState().isTerminal()) {
                    throw new IllegalStateException("task is already terminal");
                }
                if (!CompanionTickDispatcher.cancel(record.publicId())) {
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
                    GameplayAttentionMonitor.applyDeathDecision(deathEffect, player, record);
                }
            }
            default -> throw new IllegalArgumentException("unknown task action: " + action);
        }
        JsonObject result = taskSnapshot(record);
        result.add("next_attention", AttentionSnapshot.continuation(
                intents.attentionCheckpoint(), record.externalId().toString()));
        return result;
    }

    private JsonObject situation(LocalPlayer player) {
        // 直接读取当前身体状态和背包摘要；这些是观察结果，不表示某个目标已经完成。
        JsonObject result = new JsonObject();
        result.addProperty("dimension", player.level().dimension().location().toString());
        result.add("position", position(player));
        result.add("view", PhysicalStructurePerception.view(player));
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
        // 汇总附近实体、告示牌、可走区域和船／电梯；大范围地形来自此前分刻准备的采样。
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
        result.add("terrain_overview",navigationOverview.describe(player));
        result.add("elevators",new com.google.gson.Gson().toJsonTree(
                org.maiwithu.maicraft.core.integration.create.elevator.ElevatorFloors.overview(player)));
        result.add("view", PhysicalStructurePerception.view(player));
        result.add("physical_structures", PhysicalStructurePerception.observe(player));
        return result;
    }

    /** 把脚下附近的可走区域和危险汇总出来，方便调用者判断下一步，不逐格列出全部方块。 */
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
        // 当前 available=true 只表示这个能力名已注册，不检查是否装了对应模组、带了材料或具备实际执行条件。
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
                "Use fields declared by each ability. Machine design/build accept a semantic design, an explicit blueprint with block offsets and states, or an exported Ponder blueprint_uri. modify_machine applies blueprint changes; operate_machine performs native use and checks its effects separately. MaiCraft owns routes, gestures, retries and confirmation; never submit click scripts.");
        return result;
    }

    private JsonObject landmarks(LocalPlayer player) {
        // 列出已记住的地点名及是否在当前维度；精确坐标留在 Mod 里，调用者用地点名引用。
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
        result.add("goal", record.goal().toJson());
        Goal current = currentGoal(record);
        if (current != null) result.add("current_goal", current.toJson());

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
        if (!terminalState && record.decisionSnapshot() != null) {
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
        for (String key : List.of("completed_effects", "remaining_effects")) {
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
        Goal current = currentGoal(record);
        if (current != null) result.addProperty("current_outcome", current.outcome());
        result.addProperty("step_index", record.stepIndex());
        result.addProperty("step_count", record.steps().size());
        return result;
    }

    /** The request stays immutable; replacements and recovery steps live in the execution plan. */
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
        // 按物品类型合并主背包数量，多的排前面；装备和副手在另一个字段单独显示。
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
        // 分开列出主手、副手和四件护甲，保留耐久信息，避免混在普通背包总数里看不清。
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
                && intents.attentionAvailable();
        return AttentionSnapshot.read(intents, arguments, available);
    }

    private static CompletionStage<JsonElement> onClient(Supplier<? extends JsonElement> operation) {
        // 网络线程不直接碰游戏对象，排到客户端线程处理；请求还没开始就被取消时，排队的代码不会再执行。
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

    private static final class ClientCallFuture extends CompletableFuture<JsonElement>
            implements RuntimeFacade.ManagedCall {
        // 记录“还在排队”还是“已经开始”，网络超时时才知道能否保证这次请求没改变任何事情。
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int WAITING = 2;
        private static final int SETTLED = 3;
        private static final int CANCELLED = 4;

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
            while (true) {
                int current = state.get();
                if (current != RUNNING && current != WAITING) return false;
                if (state.compareAndSet(current, SETTLED)) return true;
            }
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
