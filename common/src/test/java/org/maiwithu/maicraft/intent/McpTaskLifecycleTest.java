package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.server.ClientRequestRouter;
import org.maiwithu.maicraft.client.server.ServerSessionRuntime;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;
import org.maiwithu.maicraft.mcp.RuntimeFacade.CancellationDisposition;
import org.maiwithu.maicraft.mcp.RuntimeFacade.ManagedCall;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 从 MCP 运行入口验证重试和恢复取消，确认查询旧任务不会重新抢占玩家或启动旧动作。 */
public final class McpTaskLifecycleTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        repeatedExecutionLeavesHumanControlAlone();
        resumedRestoredTaskRetakesBody();
        restoredCancellationPreservesCurrentWork();
        fullHistoryRejectsBeforeAcceptingMoreWork();
        networkCancellationRespectsClientDispatch();
        progressEventsFollowScoreboard();
        bodyHazardHeartbeatFollowsPosition();
        System.out.println("McpTaskLifecycleTest: passed");
    }

    private static void progressEventsFollowScoreboard() throws Exception {
        try (var f = new Fixture()) {
            // 进度事件只跟记分牌走：变了才说，过 40 刻地板；无键沉默，绝不编 "still working"。
            // 套件共享同一个事件流，计数与取最新都按本任务编号过滤。
            var record = f.record();
            f.tasks().put(record.externalId(), record);
            f.runtime.publishProgress(record, Map.of("phase", "acquiring", "done", 17, "total", 64), 1_000);
            var events = f.runtime.attention(0, 256).getAsJsonArray("events");
            var first = lastProgressFor(events, record);
            check(countProgressFor(events, record) == 1, "首个记分牌观察应立即发布");
            check(first != null && first.get("message").getAsString().equals("17/64 · acquiring"),
                    "摘要应由框架按记分牌渲染");
            // 记分牌没变（专有字段变了）不发事件：细节随下次发布的 data 携带。
            f.runtime.publishProgress(record,
                    Map.of("phase", "acquiring", "done", 17, "total", 64, "source", "mine"), 1_010);
            events = f.runtime.attention(0, 256).getAsJsonArray("events");
            check(countProgressFor(events, record) == 1, "记分牌未变化不应发布事件");
            // 地板窗口内的变化进待发区合并，只报最新值；地板过后由下一次观察触发补发。
            f.runtime.publishProgress(record, Map.of("phase", "acquiring", "done", 18, "total", 64), 1_020);
            f.runtime.publishProgress(record, Map.of("phase", "acquiring", "done", 19, "total", 64), 1_030);
            events = f.runtime.attention(0, 256).getAsJsonArray("events");
            check(countProgressFor(events, record) == 1, "地板窗口内的变化不应立即发布");
            f.runtime.publishProgress(record, Map.of("phase", "acquiring", "done", 19, "total", 64), 1_041);
            events = f.runtime.attention(0, 256).getAsJsonArray("events");
            var merged = lastProgressFor(events, record);
            check(countProgressFor(events, record) == 2, "地板过后应发布合并后的进度");
            check(merged != null && merged.get("message").getAsString().equals("19/64 · acquiring")
                    && merged.getAsJsonObject("data").get("done").getAsInt() == 19,
                    "合并事件应携带最新记分牌与观察数据");
            // 一个标准键都没有的观察：沉默——没有信息量的话不发。
            f.runtime.publishProgress(record, Map.of("task", "move_to"), 1_050);
            events = f.runtime.attention(0, 256).getAsJsonArray("events");
            check(countProgressFor(events, record) == 2, "无标准键的观察应保持静默");
            // 终态清掉门卫记账；同任务再次发进度不被旧状态吞掉。
            f.runtime.terminal(record, TaskState.SUCCESS, TaskResult.ok("done"));
            f.runtime.publishProgress(record, Map.of("phase", "acquiring", "done", 20, "total", 64), 1_060);
            events = f.runtime.attention(0, 256).getAsJsonArray("events");
            check(countProgressFor(events, record) == 3, "终态清空记账后新进度应立即发送");
        }
    }

    /**
     * 身体安全心跳（issue 168）：body 是标准键，值含当前位置——滞水随浪浮沉坐标不断变化，
     * 受胁期每过地板间隔仍有一条事件，观察者不必等终态才发现角色在溺水边缘。
     */
    private static void bodyHazardHeartbeatFollowsPosition() throws Exception {
        try (var f = new Fixture()) {
            var record = f.record();
            f.tasks().put(record.externalId(), record);
            f.runtime.publishProgress(record,
                    Map.of("phase", "querying_sources", "body", "滞水@260,58,120"), 2_000);
            var events = f.runtime.attention(0, 256).getAsJsonArray("events");
            check(countProgressFor(events, record) == 1, "滞水观察带 body 标准键应立即发布");
            check(lastProgressFor(events, record).get("message").getAsString().contains("滞水@260,58,120"),
                    "摘要应携带滞水位置");
            // 位置没变不重复发布；浪况浮沉改变坐标后重新武装签名，地板过后照常发布。
            f.runtime.publishProgress(record,
                    Map.of("phase", "querying_sources", "body", "滞水@260,58,120"), 2_010);
            events = f.runtime.attention(0, 256).getAsJsonArray("events");
            check(countProgressFor(events, record) == 1, "滞水位置未变不应重复发布");
            f.runtime.publishProgress(record,
                    Map.of("phase", "querying_sources", "body", "滞水@260,59,120"), 2_020);
            f.runtime.publishProgress(record,
                    Map.of("phase", "querying_sources", "body", "滞水@260,60,120"), 2_030);
            events = f.runtime.attention(0, 256).getAsJsonArray("events");
            check(countProgressFor(events, record) == 1, "地板窗口内的坐标变化合并待发");
            f.runtime.publishProgress(record,
                    Map.of("phase", "querying_sources", "body", "滞水@260,60,120"), 2_041);
            events = f.runtime.attention(0, 256).getAsJsonArray("events");
            check(countProgressFor(events, record) == 2, "地板过后应发布最新滞水位置");
            check(lastProgressFor(events, record).get("message").getAsString().contains("滞水@260,60,120"),
                    "事件应携带最新的滞水坐标");
        }
    }

    private static int countProgressFor(JsonArray events, IntentTaskRecord record) {
        String taskId = record.externalId().toString();
        int count = 0;
        for (var element : events) {
            JsonObject event = element.getAsJsonObject();
            if ("task_progress".equals(event.get("type").getAsString())
                    && taskId.equals(event.get("task_id").getAsString())) count++;
        }
        return count;
    }

    private static JsonObject lastProgressFor(JsonArray events, IntentTaskRecord record) {
        String taskId = record.externalId().toString();
        JsonObject found = null;
        for (var element : events) {
            JsonObject event = element.getAsJsonObject();
            if ("task_progress".equals(event.get("type").getAsString())
                    && taskId.equals(event.get("task_id").getAsString())) found = event;
        }
        return found;
    }

    private static int countType(JsonArray events, String type) {
        int count = 0;
        for (var element : events) {
            if (type.equals(element.getAsJsonObject().get("type").getAsString())) count++;
        }
        return count;
    }

    private static JsonObject lastOf(JsonArray events, String type) {
        JsonObject found = null;
        for (var element : events) {
            JsonObject event = element.getAsJsonObject();
            if (type.equals(event.get("type").getAsString())) found = event;
        }
        return found;
    }

    private static void repeatedExecutionLeavesHumanControlAlone() throws Exception {
        try (var f = new Fixture()) {
            // 原请求已经结束，玩家重新接手后网络再次提交相同请求，不应产生新的接管请求。
            var original = f.record();
            original.terminal(TaskState.SUCCESS, TaskResult.ok("已等到条件"), 1);
            f.tasks().put(original.externalId(), original);
            f.requestKeys().put("same-request", original.externalId());
            var args = new JsonObject();
            args.add("goal", f.goal.toJson());
            args.addProperty("request_key", "same-request");
            var result = f.facade.execute(args).toCompletableFuture().join().getAsJsonObject();
            check(result.get("task_id").getAsString().equals(original.externalId().toString()), "重试应返回原任务编号");
            check(result.get("deduplicated").getAsBoolean(), "去重命中应显式标注 deduplicated");
            check(result.get("message").getAsString().contains("identical request_key"),
                    "去重回执应注明返回的是既有任务");
            check(!ClientRuntime.actor().automationControlRequested(), "查询原请求不能重新接管玩家");
            check(result.get("control_status").getAsString().equals("not_requested"), "重试回复不能声称已请求接管");
            check(f.tasks().size() == 1, "重试不能登记第二件任务");
            // 去重命中必须写上任务单：走 attention/task 轮询终态的调用链看不到 execute 即时响应的
            // deduplicated 标注，重放与新执行在任务视图上必须可分辨（147 勘察同参重提逐字同回执的教训）。
            check(original.deduplicatedRequestHits() == 1, "去重命中应在任务单登记重放计数");
            var viewArgs = new JsonObject();
            viewArgs.addProperty("action", "get");
            viewArgs.addProperty("task_id", original.externalId().toString());
            var view = f.facade.task(viewArgs).toCompletableFuture().join().getAsJsonObject();
            check(view.get("deduplicated_request_hits").getAsInt() == 1,
                    "任务视图应交付重放计数");
            f.facade.execute(args).toCompletableFuture().join();
            check(original.deduplicatedRequestHits() == 2, "再次重提应累计重放计数");

            // 保留原有目标校验：使用旧请求编号不能绕过能力参数检查，也不能因此申请身体。
            args.getAsJsonObject("goal").getAsJsonObject("parameters").addProperty("slot", 2);
            try {
                f.facade.execute(args).toCompletableFuture().join();
                throw new AssertionError("旧请求编号绕过了目标校验");
            } catch (CompletionException expected) {
                check(expected.getCause() instanceof IllegalArgumentException, "应报告参数错误");
            }
            check(!ClientRuntime.actor().automationControlRequested(), "无效重试也不能接管玩家");
        }
    }

    private static void resumedRestoredTaskRetakesBody() throws Exception {
        try (var f = new Fixture()) {
            // 重启后恢复出的暂停任务没有接管请求（它不随检查点存盘）；resume 即表达继续，
            // 必须重新登记身体接管，否则任务每刻都会因无人持有身体再次暂停（114 修复点）。
            var restored = IntentTaskRecord.restored(UUID.randomUUID(), null, f.goal, f.identity.key(),
                    List.of(f.goal), 0, List.of(), Map.of(), Map.of(), List.of(), null, null, null, 2);
            f.tasks().put(restored.externalId(), restored);
            var args = new JsonObject();
            args.addProperty("action", "resume");
            args.addProperty("task_id", restored.externalId().toString());
            var result = f.facade.task(args).toCompletableFuture().join().getAsJsonObject();
            check(!restored.paused(), "resume 应解除恢复态任务的暂停");
            check(ClientRuntime.actor().automationControlRequested(), "恢复态任务的 resume 必须重新登记接管请求");
            check(result.get("control_status").getAsString().equals("takeover_requested"),
                    "resume 回执应声明已请求接管");
            check(CompanionTickDispatcher.find(restored.publicId()) == restored,
                    "恢复态任务 resume 后应重新进入调度器");

            // 接管请求已存在时 resume 幂等：不再新建请求，回执如实说明身体已由该请求覆盖。
            result = f.facade.task(args).toCompletableFuture().join().getAsJsonObject();
            check(ClientRuntime.actor().automationControlRequested()
                    && result.get("control_status").getAsString().equals("already_held_or_requested"),
                    "已持接管请求的 resume 不能重复申请，回执应如实说明");
        }
    }

    private static void restoredCancellationPreservesCurrentWork() throws Exception {
        try (var f = new Fixture()) {
            // 世界里已有另一件当前任务；从磁盘恢复的旧任务只是一张暂停记录，不占身体槽。
            var active = new TaskRecord("test_active", "", TaskRecord.NO_DEADLINE) { };
            CompanionTickDispatcher.submitCurrent(f.world.player, active);
            var restored = IntentTaskRecord.restored(UUID.randomUUID(), null, f.goal, f.identity.key(),
                    List.of(f.goal), 0, List.of(), Map.of(), Map.of(), List.of(), null, null, null, 2);
            f.tasks().put(restored.externalId(), restored);
            var args = new JsonObject();
            args.addProperty("action", "cancel");
            args.addProperty("task_id", restored.externalId().toString());
            var result = f.facade.task(args).toCompletableFuture().join().getAsJsonObject();
            check(result.get("state").getAsString().equals("cancelled"), "恢复记录应可直接取消");
            check(restored.terminalSnapshot() != null && !restored.paused(), "取消应清除暂停并留下终态");
            check(CompanionTickDispatcher.current() == active && active.getState() == TaskState.RUNNING,
                    "取消旧恢复记录不能替换或停止当前任务");
            check(!ClientRuntime.actor().automationControlRequested(), "取消记录不需要身体接管");
            check(f.world.blockUses() == 0 && f.world.itemUses() == 0, "取消不能启动旧的原生动作");

            // 重启检查点必须保存取消结果，而不能下次又把这张单子恢复成待继续状态。
            var saved = IntentStateCodec.encode(f.identity.key(), List.of(), f.tasks().values(), Map.of(), List.of());
            check(saved.getAsJsonArray("tasks").get(0).getAsJsonObject().getAsJsonObject("terminal")
                    .get("state").getAsString().equals("cancelled"), "检查点应保存已取消的终态");
            long cursor = f.runtime.attentionCheckpoint().get("cursor").getAsLong();
            try {
                f.facade.task(args).toCompletableFuture().join();
                throw new AssertionError("结束的任务再次被取消");
            } catch (CompletionException expected) {
                check(expected.getCause() instanceof IllegalStateException, "重复取消应报告已经结束");
            }
            check(f.runtime.attentionCheckpoint().get("cursor").getAsLong() == cursor, "重复取消不能发布第二次终态");
        }
    }

    private static void fullHistoryRejectsBeforeAcceptingMoreWork() throws Exception {
        try (var f = new Fixture()) {
            // 旧世界恢复了满额未完成任务时，不能再接一件随后会被检查点截掉的新工作。
            IntentTaskRecord first = null;
            for (int i = 0; i < IntentStateCodec.MAX_TASKS; i++) {
                var record = IntentTaskRecord.restored(UUID.randomUUID(), null, f.goal, f.identity.key(),
                        List.of(f.goal), 0, List.of(), Map.of(), Map.of(), List.of(), null, null, null, 1);
                if (first == null) first = record;
                f.tasks().put(record.externalId(), record);
            }
            long cursor = f.runtime.attentionCheckpoint().get("cursor").getAsLong();
            var request = new JsonObject();
            request.add("goal", f.goal.toJson());
            request.addProperty("request_key", "new-at-capacity");
            try {
                f.facade.execute(request).toCompletableFuture().join();
                throw new AssertionError("已经无法完整保存时仍然接收了新任务");
            } catch (CompletionException expected) {
                check(expected.getCause() instanceof IllegalStateException, "任务表已满应说明无法接单");
            }
            check(f.tasks().size() == IntentStateCodec.MAX_TASKS && f.requestKeys().isEmpty(),
                    "拒绝接单不能留下新任务或请求编号");
            check(f.runtime.attentionCheckpoint().get("cursor").getAsLong() == cursor,
                    "未接收的任务不能发布已经开始的通知");
            check(!ClientRuntime.actor().automationControlRequested() && CompanionTickDispatcher.current() == null,
                    "拒绝接单应撤回新接管请求，不占玩家身体");

            // 明确取消一件旧事后，只淘汰这条已结束历史，新任务和它的请求编号必须一起保存。
            f.runtime.cancelRestored(first, 2);
            var accepted = f.facade.execute(request).toCompletableFuture().join().getAsJsonObject();
            UUID id = UUID.fromString(accepted.get("task_id").getAsString());
            check(f.tasks().size() == IntentStateCodec.MAX_TASKS && f.runtime.task(id) != null,
                    "空出记录后才能接到一件可保存的新任务");
            var saved = IntentStateCodec.encode(f.identity.key(), List.of(), f.tasks().values(), f.requestKeys(), List.of());
            var decoded = IntentStateCodec.decode(saved);
            check(decoded.tasks().stream().anyMatch(task -> task.id().equals(id))
                    && id.equals(decoded.requestKeys().get("new-at-capacity")), "检查点不能丢掉刚接受的任务身份");
            // 即使未来调用者绕过接单检查，编码器也必须拒绝残缺快照，不能静默丢弃最后一条记录。
            var overflow = new ArrayList<>(f.tasks().values());
            overflow.add(f.record());
            try {
                IntentStateCodec.encode(f.identity.key(), List.of(), overflow, Map.of(), List.of());
                throw new AssertionError("编码器截断超量任务后仍然返回成功");
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static void networkCancellationRespectsClientDispatch() throws Exception {
        try (var f = new Fixture()) {
            // 模拟网络线程排队到游戏线程：尚未执行的请求可撤回，撤回后不能接管或登记任务。
            var queue = new ConcurrentLinkedQueue<Runnable>();
            field(Minecraft.class, "pendingRunnables").set(Minecraft.getInstance(), queue);
            var request = new JsonObject();
            request.add("goal", f.goal.toJson());
            var pending = fromNetwork(() -> f.facade.execute(request));
            check(((ManagedCall) pending).cancelCall() == CancellationDisposition.CANCELLED_BEFORE_START,
                    "还在游戏线程队列中的请求应能撤回");
            queue.remove().run();
            check(pending.toCompletableFuture().isCancelled() && f.tasks().isEmpty()
                    && !ClientRuntime.actor().automationControlRequested(), "被撤回的排队请求不能开始游戏工作");

            // 已经开始接单时，网络取消只能报告已经开始；真正停止任务要用 task(cancel)。
            var running = new AtomicReference<CompletionStage<JsonElement>>();
            var cancellation = new AtomicReference<CancellationDisposition>();
            try (var subscription = f.runtime.subscribeAttention(event -> cancellation.compareAndSet(null,
                    ((ManagedCall) running.get()).cancelCall()))) {
                running.set(fromNetwork(() -> f.facade.execute(request)));
                queue.remove().run();
            }
            check(cancellation.get() == CancellationDisposition.ALREADY_STARTED,
                    "发布开始事件时不能把执行中的请求说成已经撤回");
            check(running.get().toCompletableFuture().join().getAsJsonObject().get("accepted").getAsBoolean()
                    && f.tasks().size() == 1, "已经开始的请求仍交付真实接单结果");
            check(((ManagedCall) running.get()).cancelCall() == CancellationDisposition.SETTLED,
                    "请求交付完成后不再撤回任何游戏工作");
        }
    }

    private static CompletionStage<JsonElement> fromNetwork(Supplier<CompletionStage<JsonElement>> request)
            throws InterruptedException {
        var result = new AtomicReference<CompletionStage<JsonElement>>();
        Thread network = new Thread(() -> result.set(request.get()), "mcp-request-test");
        network.start();
        network.join();
        return result.get();
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final Goal goal = new Goal("maicraft:wait_for_condition", "等到条件满足", null,
                "{}", "{}", List.of(), List.of());
        final IntentRuntime runtime;
        final MaiCraftRuntimeFacade facade;
        final StateIdentity identity;
        final Map<Field, Object> dispatcher = new LinkedHashMap<>();
        final Field routerSlot = field(ServerSessionRuntime.class, "router"),
                installedSlot = field(ServerSessionRuntime.class, "installed"),
                sessionConnectionSlot = field(ServerSessionRuntime.class, "connection"),
                listenerConnectionSlot = field(world.player.connection.getClass(), "connection");
        final Object priorRouter, priorInstalled, priorSessionConnection, priorListenerConnection;

        Fixture() throws Exception {
            // 只构造协议入口所需的多人身份，不连接服务器，也不读取玩家真实存档。
            Minecraft client = Minecraft.getInstance();
            field(client.getClass(), "gameDirectory").set(client, Files.createTempDirectory("mcp-task-lifecycle-").toFile());
            field(world.player.connection.getClass(), "serverData").set(world.player.connection,
                    new ServerData("test", "lifecycle.invalid", ServerData.Type.OTHER));
            // 正式入口在服务端握手确认前拒绝一切游戏调用；夹具与真实入服等价地补齐确认状态，
            // 请求由内存路由器承接，收尾恢复全局会话字段，不把测试状态带给后面的场景。
            priorRouter = routerSlot.get(null);
            priorInstalled = installedSlot.get(null);
            priorSessionConnection = sessionConnectionSlot.get(null);
            priorListenerConnection = listenerConnectionSlot.get(world.player.connection);
            var link = new Connection(PacketFlow.CLIENTBOUND);
            field(Connection.class, "channel").set(link, new EmbeddedChannel());
            listenerConnectionSlot.set(world.player.connection, link);
            // Minecraft.getConnection() 即 player.connection，无需另设客户端字段。
            sessionConnectionSlot.set(null, world.player.connection);
            installedSlot.setBoolean(null, true);
            List<JsonObject> sent = new ArrayList<>();
            var router = new ClientRequestRouter(() -> true,
                    envelope -> { sent.add(envelope.deepCopy()); return true; },
                    () -> {}, Runnable::run, (receipt, send) -> { send.run(); return true; });
            router.bind(1, 1, "minecraft:overworld", 1, true, 0);
            JsonObject welcome = new JsonObject();
            welcome.addProperty("kind", "welcome"); welcome.addProperty("bootstrap", 1);
            welcome.addProperty("status", "succeeded"); welcome.add("clientNonce", sent.getFirst().get("clientNonce"));
            welcome.addProperty("sessionId", "mcp-task-lifecycle"); welcome.addProperty("dimension", "minecraft:overworld");
            welcome.add("features", new JsonObject());
            router.receive(welcome, 1);
            JsonObject control = sent.getLast().deepCopy(); control.addProperty("status", "succeeded");
            router.receive(control, 1);
            routerSlot.set(null, router);
            identity = StateIdentity.resolve(client).orElseThrow();
            var constructor = IntentRuntime.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            runtime = constructor.newInstance();
            field(IntentRuntime.class, "stateIdentity").set(runtime, identity);
            field(IntentRuntime.class, "bodyAttached").set(runtime, true);
            var facadeConstructor = MaiCraftRuntimeFacade.class.getDeclaredConstructor();
            facadeConstructor.setAccessible(true);
            facade = facadeConstructor.newInstance();
            field(MaiCraftRuntimeFacade.class, "intents").set(facade, runtime);
            Object body = ClientRuntime.requireContext(world.player).body();
            // 通过真实交还流程还原玩家输入，使“失败后撤回新接管请求”与正常游戏中的身体状态一致。
            var release = body.getClass().getDeclaredMethod("shutdown");
            release.setAccessible(true);
            release.invoke(body);
            for (String name : List.of("brain", "boundPlayer", "boundLevel", "expectedHandoff", "pendingHandoff")) {
                Field slot = field(CompanionTickDispatcher.class, name);
                dispatcher.put(slot, slot.get(null));
                slot.set(null, null);
            }
        }

        IntentTaskRecord record() { return new IntentTaskRecord(UUID.randomUUID(), null, goal, identity.key()); }
        @SuppressWarnings("unchecked") Map<UUID, IntentTaskRecord> tasks() throws Exception {
            return (Map<UUID, IntentTaskRecord>) field(IntentRuntime.class, "tasks").get(runtime);
        }
        @SuppressWarnings("unchecked") Map<String, UUID> requestKeys() throws Exception {
            return (Map<String, UUID>) field(IntentRuntime.class, "requestKeys").get(runtime);
        }
        @Override public void close() throws Exception {
            // 恢复此前的调度器、会话与连接字段，避免这组测试的握手状态影响后面的游戏场景。
            for (var saved : dispatcher.entrySet()) saved.getKey().set(null, saved.getValue());
            routerSlot.set(null, priorRouter);
            installedSlot.set(null, priorInstalled);
            sessionConnectionSlot.set(null, priorSessionConnection);
            listenerConnectionSlot.set(world.player.connection, priorListenerConnection);
            world.close();
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
