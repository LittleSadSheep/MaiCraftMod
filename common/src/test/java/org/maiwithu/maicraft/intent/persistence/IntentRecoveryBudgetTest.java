// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.build.BuildingBudgetReport;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.intent.Plan;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;

/** 模拟重启后收紧建筑预算：保留旧世界的完整任务，恢复之前不接新施工，提高预算后恢复原编号和地标。 */
public final class IntentRecoveryBudgetTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path workspace = Path.of("").toAbsolutePath().normalize();
        Path directory = Files.createTempDirectory(workspace, "intent-recovery-budget-");
        try {
            for (String property : List.of("maxTargets", "maxObjects", "maxRadius", "maxConnections", "maxVoxelWork", "maxIntentStateBytes"))
                preservesCheckpointUntilRestored(directory.resolve(property), property);
            incompatibleContentStaysPreserved(directory.resolve("incompatible"));
            malformedJsonStillQuarantines(directory.resolve("malformed"));
            System.out.println("IntentRecoveryBudgetTest: passed");
        } finally {
            // 回归只写自己的配置与状态文件；先恢复启动默认，再检查真实路径后清理临时世界。
            BuildingBudgets.initialize(directory.resolve("restore-defaults"));
            if (!directory.toRealPath().startsWith(workspace.toRealPath())) throw new AssertionError("recovery fixture escaped workspace");
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }

    private static void preservesCheckpointUntilRestored(Path directory, String property) throws Exception {
        configure(directory, "");
        Goal goal = property.equals("maxTargets") ? blueprintGoal() : sceneGoal();
        StateIdentity identity = identity(directory); Plan plan = Plan.compile(goal, 10);
        var task = new IntentTaskRecord(UUID.randomUUID(), plan.id(), goal);
        var landmark = new IntentRuntime.Landmark("原仓库", new Goal.WorldPosition(3, 64, 5, "minecraft:overworld"));
        JsonObject root = IntentStateCodec.encode(identity.key(), List.of(plan), List.of(task), Map.of("原请求", task.externalId()), List.of(landmark));
        new IntentStateStore(Runnable::run).saveAsync(identity, root).get(5, TimeUnit.SECONDS);
        Path file = stateFile(identity); byte[] original = Files.readAllBytes(file);
        // 文件仍在字节限额内时也要覆盖内容校验的失败；只有最后一组专门模拟文件本身超过新限额。
        configure(directory, property + "=1\n");
        check(property.equals("maxIntentStateBytes") || original.length < IntentStateStore.maxBytes(), "内容预算夹具不能意外走文件字节分支");
        IntentStateStore store = new IntentStateStore(Runnable::run); IntentRuntime runtime = runtime(store, identity);
        restore(runtime);
        assertBlocked(runtime, store, identity, original, property.equals("maxIntentStateBytes") ? "over_budget" : "recovery_blocked");
        configure(directory, "");
        rejected(runtime::requireRecoveredState);
        rejectSave(store, identity);
        // 改回数字还不够，必须重新读回并验证旧任务；之后继续使用原任务编号，不生成替代任务。
        restore(runtime);
        runtime.requireRecoveredState();
        check(runtime.task(task.externalId()) != null && runtime.task(task.externalId()).goal().toJson().equals(goal.toJson())
                        && runtime.taskForRequestKey("原请求").externalId().equals(task.externalId())
                        && runtime.plan(plan.id()) != null && runtime.landmarks().equals(List.of(landmark)),
                "提高预算后应找回原计划、任务、去重请求和地标");
        check(runtime.task(task.externalId()).restoredDetached(), "恢复旧工程后仍须等待明确继续，不能自动接管身体");
        check(Arrays.equals(Files.readAllBytes(file), original), "恢复读取不能重写原文件");
        check(runtime.checkpointDeath(), "完整恢复之后应重新允许保存死亡交接快照");
        var restored = IntentStateCodec.decode(new IntentStateStore().load(identity).root());
        check(restored.tasks().getFirst().id().equals(task.externalId()) && restored.landmarks().equals(List.of(landmark)),
                "解除保护后保存的仍是原任务和地标");
    }

    private static void assertBlocked(IntentRuntime runtime, IntentStateStore store, StateIdentity identity,
                                      byte[] original, String expectedStatus) throws Exception {
        // 不初始化游戏客户端，也不给玩家对象；只要代码试图进入角色调度或预览发布，这个测试就不能成功。
        var brain = field(CompanionTickDispatcher.class, "brain"); Object priorBrain = brain.get(null);
        Goal construction = new Goal("maicraft:build", "恢复前禁止施工", null, "{}", "{}", List.of(), List.of());
        rejected(() -> runtime.execute(null, construction, null, "新施工"));
        rejected(() -> runtime.execute(null, sceneGoal(), null, "新设计"));
        rejected(() -> runtime.compile(construction, 20));
        rejected(() -> runtime.remember("临时位置", new Goal.WorldPosition(1, 64, 1, "minecraft:overworld")));
        check(brain.get(null) == priorBrain && runtime.tasks(20).isEmpty() && runtime.landmarks().isEmpty(),
                "受阻接单不能碰身体调度，也不能生成会丢失的新任务或地标");
        check(runtime.attentionAvailable() && BuildingBudgetReport.current().has("configuration"), "恢复受阻后仍允许查询注意事件与实际预算");
        var events = runtime.attention(0, 20).getAsJsonArray("events");
        check(events.size() == 1, "被拒绝的新执行不能发布 started 或伪造完成事件");
        var event = events.get(0).getAsJsonObject(); var data = event.getAsJsonObject("data");
        check(data.get("status").getAsString().equals(expectedStatus)
                        && data.get("checkpoint_preserved").getAsBoolean() && data.get("new_tasks_blocked").getAsBoolean()
                        && event.get("message").getAsString().contains(BuildingBudgets.CONFIG_PATH),
                "恢复结果必须明确报告保留状态、接单阻塞与配置位置");
        rejectSave(store, identity);
        check(!runtime.checkpointDeath() && !runtime.prepareRespawnHandoff(), "未恢复的状态不能冒称已有死亡或重生交接快照");
        runtime.shutdownPersistence();
        check(!store.hasSnapshot(identity) && Arrays.equals(Files.readAllBytes(stateFile(identity)), original),
                "保存、重生和退出都不能用空任务覆盖仍未加载的旧世界进度");
        try (var files = Files.list(identity.directory())) { check(files.count() == 1, "预算收紧不能生成 corrupt 隔离副本"); }
    }

    private static void incompatibleContentStaysPreserved(Path directory) throws Exception {
        configure(directory, ""); StateIdentity identity = identity(directory);
        var task = new IntentTaskRecord(UUID.randomUUID(), null, sceneGoal());
        JsonObject root = IntentStateCodec.encode(identity.key(), List.of(), List.of(task), Map.of(), List.of());
        // 任务模型无法按当前规则恢复时也保留原证据；不能依赖某条预算错误的英文文字来决定文件是否安全。
        root.getAsJsonArray("tasks").get(0).getAsJsonObject().addProperty("step_index", -1);
        new IntentStateStore(Runnable::run).saveAsync(identity, root).get(5, TimeUnit.SECONDS);
        byte[] original = Files.readAllBytes(stateFile(identity));
        var store = new IntentStateStore(Runnable::run); var runtime = runtime(store, identity); restore(runtime);
        assertBlocked(runtime, store, identity, original, "recovery_blocked");
    }

    private static void malformedJsonStillQuarantines(Path directory) throws Exception {
        configure(directory, ""); StateIdentity identity = identity(directory); Files.createDirectories(identity.directory());
        Files.writeString(stateFile(identity), "{\"tasks\":[");
        // 截断的 JSON 仍按原来的损坏文件流程隔离，与已经通过 JSON 和世界身份验证的内容不兼容区分。
        check(new IntentStateStore().load(identity).status() == IntentStateStore.Status.CORRUPT && !Files.exists(stateFile(identity)),
                "真正无法解析的 JSON 仍应隔离");
        try (var files = Files.list(identity.directory())) { check(files.allMatch(path -> path.getFileName().toString().endsWith(".corrupt")), "隔离文件应保留排错证据"); }
    }

    private static Goal sceneGoal() {
        // 两个实心对象与两个独立切割体共同覆盖对象数、半径、连接数和体素采样额度。
        JsonObject parameters = JsonParser.parseString("""
                {"operation":"create_scene","scene":{"schema_version":1,"coordinate_system":"minecraft_y_up",
                "materials":{"stone":{"block_id":"minecraft:stone"}},"objects":[
                {"name":"大厅","type":"cube","material":"stone","location":[1,0.5,0.5],"dimensions":[2,1,1],
                 "modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"门洞"},{"type":"BOOLEAN","operation":"DIFFERENCE","object":"天窗"}]},
                {"name":"立柱","type":"cube","material":"stone","location":[4.5,0.5,0.5],"dimensions":[1,1,1]},
                {"name":"门洞","type":"cube","role":"cutter","location":[0.5,0.5,0.5],"dimensions":[1,1,1]},
                {"name":"天窗","type":"cube","role":"cutter","location":[1.5,0.5,0.5],"dimensions":[1,1,1]}]}}
                """).getAsJsonObject();
        return new Goal("maicraft:design_build", "保存大厅与立柱", null, parameters.toString(), "{}", List.of(), List.of());
    }

    private static Goal blueprintGoal() {
        // 两个逐格目标直接覆盖 maxTargets，避免只测作者模型的体积预检而遗漏蓝图恢复入口。
        String parameters = "{\"blueprint\":{\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:stone\"},{\"offset\":[1,0,0],\"block_id\":\"minecraft:air\"}]}}";
        return new Goal("maicraft:design_build", "保留墙体与空气", null, parameters, "{}", List.of(), List.of());
    }

    private static IntentRuntime runtime(IntentStateStore store, StateIdentity identity) throws Exception {
        // 为一个临时世界建立真正的运行时，只替换存储器；角色和图形客户端均不参与恢复测试。
        var constructor = IntentRuntime.class.getDeclaredConstructor(); constructor.setAccessible(true);
        var runtime = constructor.newInstance(); field(IntentRuntime.class, "stateStore").set(runtime, store);
        field(IntentRuntime.class, "stateIdentity").set(runtime, identity); return runtime;
    }
    private static void restore(IntentRuntime runtime) throws Exception {
        // 模拟 tick 的换世界顺序：清空旧身体记录、恢复任务，再开放本世界的只读观察通道。
        var clear = IntentRuntime.class.getDeclaredMethod("clearSemanticState"); clear.setAccessible(true); clear.invoke(runtime);
        var restore = IntentRuntime.class.getDeclaredMethod("restoreBound", long.class); restore.setAccessible(true); restore.invoke(runtime, 30L);
        field(IntentRuntime.class, "bodyAttached").set(runtime, true);
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void configure(Path directory, String text) throws Exception {
        Path game = directory.resolve("game"), config = game.resolve(BuildingBudgets.CONFIG_PATH);
        Files.createDirectories(config.getParent()); Files.writeString(config, text); BuildingBudgets.initialize(game);
    }
    private static StateIdentity identity(Path directory) { return new StateIdentity("1".repeat(64), directory.resolve("state")); }
    private static Path stateFile(StateIdentity identity) { return identity.directory().resolve(identity.key() + ".json"); }
    private static void rejectSave(IntentStateStore store, StateIdentity identity) throws Exception {
        try { store.saveAsync(identity, new JsonObject()); } catch (IOException expected) { return; }
        throw new AssertionError("未恢复的旧检查点不能接受覆盖保存");
    }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("recovery is blocked") && expected.getMessage().contains(BuildingBudgets.CONFIG_PATH), "接单应返回明确恢复原因"); return;
        }
        throw new AssertionError("恢复受阻时不能接单或记录新状态");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
