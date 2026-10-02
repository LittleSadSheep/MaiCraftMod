// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.Identity;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.Position;
import org.maiwithu.maicraft.intent.persistence.MemoryDatabase;

/** 旧机器目录只有在整套图纸可读时才入库；任务与机器共享数据库仍各自恢复原身份。 */
public final class MachineCatalogMigrationTest {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of("").toRealPath(), directory = Files.createTempDirectory(workspace, "machine-migration-");
        try {
            Path machines = Files.createDirectory(directory.resolve("machines")), file = directory.resolve(MemoryDatabase.FILE_NAME);
            var database = new MemoryDatabase(file); var identity = new Identity("旧世界", "玩家");
            database.write("state", identity.key(), "任务检查点", Map.of(), false);
            var document = JsonParser.parseString("{\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:stone\"}]}").getAsJsonObject();
            var anchor = new Position(8,64,8);
            var blueprint = new MachineBlueprint(MachineBlueprint.namedId(identity.key(), "minecraft:overworld", anchor, "刷石机"),
                    "刷石机", "minecraft:overworld", anchor, document.toString(), CatalogLimits.hash(document.toString()), "success", 10, 20);
            String json = CatalogCodec.encode(new CatalogCodec.Snapshot(identity.key(), List.of(), List.of(), List.of(), List.of(blueprint)));
            Path legacy = machines.resolve(identity.key() + ".json"); Files.writeString(legacy, json);
            // 先故意缺少图纸：索引不能单独迁移，也不能清空已有任务记忆。
            var missing = new MachineCatalog(machines, file, Runnable::run); missing.bind(identity, "missing");
            check(missing.status().state() == MachineCatalog.State.FAILED && database.read("machines", identity.key(), 4096) == null,
                    "缺图纸的旧机器被部分迁移");
            Path archive = Files.createDirectory(machines.resolve(identity.key() + "-blueprints")).resolve(blueprint.fingerprint() + ".json");
            Files.writeString(archive, document.toString());
            var migrated = new MachineCatalog(machines, file, Runnable::run); migrated.bind(identity, "migrated");
            check(migrated.ready() && migrated.blueprint(blueprint.id()).orElseThrow().equals(blueprint), "机器身份、图纸或历史结果丢失");
            check(Files.readString(legacy).equals(json) && Files.readString(archive).equals(document.toString()), "导入改写了旧档案");
            migrated.recordBlueprintState(blueprint.id(), blueprint.fingerprint(), "running", 30); migrated.saveAsync().join();
            // 迁移完成后破坏旧图纸也不能影响数据库恢复，更不能把旧 success 当成当前状态。
            Files.writeString(archive, "旧副本已损坏");
            var restarted = new MachineCatalog(machines, file, Runnable::run); restarted.bind(identity, "restarted");
            check(restarted.ready() && restarted.blueprint(blueprint.id()).orElseThrow().lastBuildState().equals("running"), "重启回退到了旧 JSON");
            check(database.read("state", identity.key(), 100).equals("任务检查点"), "机器迁移覆盖了任务检查点");
            System.out.println("MachineCatalogMigrationTest: passed");
        } finally {
            // 回归只清理本次创建的数据库及旧档案夹具。
            if (!directory.toRealPath().startsWith(workspace)) throw new AssertionError("fixture escaped workspace");
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
