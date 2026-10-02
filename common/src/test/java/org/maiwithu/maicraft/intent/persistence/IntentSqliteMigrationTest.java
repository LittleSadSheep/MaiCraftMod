// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/** 旧任务先完整迁移，再恢复原编号；数据库不可读时不能退回旧 JSON 重放已经完成的动作。 */
public final class IntentSqliteMigrationTest {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of("").toRealPath(), directory = Files.createTempDirectory(workspace, "intent-sqlite-");
        try {
            var identity = new StateIdentity("1".repeat(64), directory.resolve("state"));
            Files.createDirectories(identity.directory());
            Path legacy = identity.directory().resolve(identity.key() + ".json");
            var root = JsonParser.parseString("""
                    {"version":1,"plans":[],"tasks":[{"id":"原任务","completed_effects":["已放置"]}],
                    "request_keys":{"原请求":"原任务"},"landmarks":[{"label":"原仓库"}],
                    "containers":[{"items":{"minecraft:stone":64}}]}
                    """).getAsJsonObject();
            root.addProperty("identity_key", identity.key()); Files.writeString(legacy, root.toString());
            check(new IntentStateStore().load(identity).root().equals(root), "迁移丢失任务、地点、容器或效果");
            check(Files.readString(legacy).equals(root.toString()) && Files.exists(identity.databaseFile()), "旧文件必须保留且新库确实落盘");
            // 后续施工保存到 SQLite；仍留在磁盘上的旧 JSON 不能在重启时覆盖更新进度。
            JsonObject updated = root.deepCopy(); updated.addProperty("revision", 2);
            new IntentStateStore(Runnable::run).saveAsync(identity, updated).join();
            check(new IntentStateStore().load(identity).root().equals(updated), "旧 JSON 覆盖了迁移后的进度");
            var owned = identity.child("owned-construction").child("player-a");
            JsonObject ownership = new JsonObject(); ownership.addProperty("version", 1); ownership.addProperty("identity_key", identity.key());
            new IntentStateStore(Runnable::run).saveAsync(owned, ownership).join();
            check(owned.databaseFile().equals(identity.databaseFile()) && new IntentStateStore().load(owned).root().equals(ownership)
                    && new IntentStateStore().load(identity).root().equals(updated), "同库施工归属覆盖了任务记忆");
            // 模拟数据库中的检查点损坏；保留原记录并阻止空状态覆盖，不能回读旧文件伪装恢复成功。
            new MemoryDatabase(identity.databaseFile()).write(identity.scope(), identity.key(), "{", Map.of(), false);
            var invalid = new IntentStateStore(Runnable::run);
            check(invalid.load(identity).status() == IntentStateStore.Status.CORRUPT && invalid.recoveryProblem(identity) != null, "损坏记录未被保护");
            rejectSave(invalid, identity, updated);
            Files.writeString(identity.databaseFile(), "broken database");
            var unavailable = new IntentStateStore(Runnable::run);
            check(unavailable.load(identity).status() == IntentStateStore.Status.UNAVAILABLE && unavailable.recoveryProblem(identity) != null,
                    "数据库不可用不能被当成新世界或使用旧 JSON");
            rejectSave(unavailable, identity, updated);
            check(Files.readString(legacy).equals(root.toString()), "失败恢复修改了迁移源文件");
            System.out.println("IntentSqliteMigrationTest: passed");
        } finally {
            // 临时世界由本测试创建；确认边界后清理，避免把数据库夹具留进真实游戏实例。
            if (!directory.toRealPath().startsWith(workspace)) throw new AssertionError("fixture escaped workspace");
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void rejectSave(IntentStateStore store, StateIdentity identity, JsonObject root) throws Exception {
        try { store.saveAsync(identity, root); } catch (IOException expected) { return; }
        throw new AssertionError("未恢复的记忆不能被新快照覆盖");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
