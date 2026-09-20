// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import java.util.Arrays;

/** 请求准入和真实检查点文件共用启动预算；验证超过旧四 MiB 的记录可保存，中文按 UTF-8 字节计费。 */
public final class IntentPersistenceBudgetTest {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of("").toAbsolutePath().normalize();
        Path directory = Files.createTempDirectory(workspace, "intent-building-budget-");
        try {
            BuildingBudgets.initialize(directory.resolve("defaults"));
            check(IntentStateStore.maxBytes() == 256 * 1024 * 1024, "默认语义状态容量应为二百五十六 MiB");
            largerThanTheOldLimitRoundTrips(directory);
            utf8BytesRemainBounded(directory);
            goalAndFileUseTheSameEffectiveBudget(directory);
            System.out.println("IntentPersistenceBudgetTest: passed");
        } finally {
            // 仅临时配置参与回归；恢复默认后再删除已验证属于本测试的目录，避免影响后续套件。
            BuildingBudgets.initialize(directory.resolve("restore-defaults"));
            if (!directory.toRealPath().startsWith(workspace.toRealPath())) throw new AssertionError("intent budget fixture escaped workspace");
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }

    private static void largerThanTheOldLimitRoundTrips(Path directory) throws Exception {
        var store = new IntentStateStore(Runnable::run); // 先加载类和对象，再安装配置，旧常量不能锁住默认值。
        configure(directory, 8 * 1024 * 1024);
        check(IntentStateStore.maxBytes() == 8 * 1024 * 1024, "已加载的存储类仍读取有效预算");
        StateIdentity identity = identity(directory.resolve("large-state"), 1);
        JsonObject root = root(identity); root.addProperty("retained_model_evidence", "x".repeat(5 * 1024 * 1024));
        store.saveAsync(identity, root).get(5, TimeUnit.SECONDS);
        Path file = identity.directory().resolve(identity.key() + ".json");
        check(Files.size(file) > 4 * 1024 * 1024 && new IntentStateStore().load(identity).root().equals(root),
                "新实例必须从磁盘完整读回超过旧四 MiB 的状态，不能靠进程内缓存冒充恢复");
        configure(directory, 4 * 1024 * 1024);
        var blocked = new IntentStateStore(Runnable::run);
        check(blocked.load(identity).status() == IntentStateStore.Status.OVER_BUDGET && Files.exists(file)
                        && Arrays.equals(Files.readAllBytes(file),root.toString().getBytes(StandardCharsets.UTF_8)),
                "调低限额只标记容量不足，不能移动或改写仍然合法的旧进度文件");
        rejectSave(blocked,identity,root(identity));
        configure(directory, 8 * 1024 * 1024);
        rejectSave(blocked,identity,root(identity));
        check(blocked.load(identity).root().equals(root), "提高预算后先成功读回完整原状态，不能直接用空任务覆盖");
        blocked.saveAsync(identity,root).get(5,TimeUnit.SECONDS);
        check(new IntentStateStore().load(identity).root().equals(root), "解除容量阻塞后可以继续原任务的正常检查点保存");
    }

    private static void utf8BytesRemainBounded(Path directory) throws Exception {
        configure(directory, 1024); StateIdentity identity = identity(directory.resolve("unicode-state"), 2);
        JsonObject root = root(identity); root.addProperty("说明", "建".repeat(500));
        check(root.toString().length() < 1024 && bytes(root) > 1024, "夹具应在字符数范围内但超过实际 UTF-8 字节预算");
        var store = new IntentStateStore(Runnable::run); rejectSave(store, identity, root);
        check(!store.hasSnapshot(identity) && !Files.exists(identity.directory()), "超限中文记录不能先进入邮箱或创建状态文件");
    }

    private static void goalAndFileUseTheSameEffectiveBudget(Path directory) throws Exception {
        JsonObject parameters = new JsonObject(); parameters.addProperty("operation", "create_scene");
        parameters.add("scene", JsonParser.parseString("{\"schema_version\":1,\"coordinate_system\":\"minecraft_y_up\","
                + "\"name\":\"大厅设计保存测试\",\"materials\":{\"stone\":{\"block_id\":\"minecraft:stone\"}},"
                + "\"objects\":[{\"name\":\"墙体\",\"type\":\"cube\",\"material\":\"stone\",\"location\":[0.5,0.5,0.5],\"dimensions\":[1,1,1]}]}"));
        Goal goal = new Goal("maicraft:design_build", "保留建筑模型", null, parameters.toString(), "{}", List.of(), List.of());
        int goalBytes = bytes(goal.toJson());
        configure(directory, goalBytes);
        IntentStateCodec.requirePersistableGoal(goal);
        configure(directory, goalBytes - 1);
        try { IntentStateCodec.requirePersistableGoal(goal); throw new AssertionError("目标超过有效字节预算时应拒绝准入"); }
        catch (IllegalArgumentException expected) { }
        configure(directory, goalBytes);
        var task = new IntentTaskRecord(UUID.randomUUID(), null, goal); StateIdentity identity = identity(directory.resolve("goal-state"), 3);
        JsonObject encoded = IntentStateCodec.encode(identity.key(), List.of(), List.of(task), Map.of(), List.of());
        check(bytes(encoded) > goalBytes, "完整状态包含原目标与步骤，文件预算要核对实际整体大小");
        var store = new IntentStateStore(Runnable::run); rejectSave(store, identity, encoded);
        configure(directory, bytes(encoded));
        store.saveAsync(identity, encoded).get(5, TimeUnit.SECONDS);
        var restored = IntentStateCodec.decode(new IntentStateStore().load(identity).root());
        check(restored.tasks().size() == 1 && restored.tasks().getFirst().goal().toJson().equals(goal.toJson()),
                "放大整体预算后恢复完整建筑目标，不删 scene、不截断已经接受的步骤");
    }

    private static void configure(Path directory, int bytes) throws Exception {
        Path game = directory.resolve("configured"), config = game.resolve(BuildingBudgets.CONFIG_PATH);
        Files.createDirectories(config.getParent()); Files.writeString(config, "maxIntentStateBytes=" + bytes + "\n"); BuildingBudgets.initialize(game);
    }
    private static StateIdentity identity(Path directory, int number) { return new StateIdentity(String.format("%064x",number),directory); }
    private static JsonObject root(StateIdentity identity) {
        JsonObject root = new JsonObject(); root.addProperty("version",IntentStateStore.VERSION); root.addProperty("identity_key",identity.key()); return root;
    }
    private static int bytes(JsonObject root) { return root.toString().getBytes(StandardCharsets.UTF_8).length; }
    private static void rejectSave(IntentStateStore store, StateIdentity identity, JsonObject root) throws Exception {
        try { store.saveAsync(identity,root); } catch (IOException expected) { return; }
        throw new AssertionError("超过实际文件预算的状态不应被接受");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
