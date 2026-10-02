// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.blueprint.BuildProjectScaffolds;
import org.maiwithu.maicraft.core.blueprint.BuildProjectStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import java.sql.DriverManager;
import org.maiwithu.maicraft.intent.persistence.MemoryDatabase;
import static org.maiwithu.maicraft.intent.persistence.MemoryRecordsTestSupport.*;
import java.util.UUID;

/** 用明确模拟的原生确认事件检查磁盘恢复；不依据世界中的其他泥土推断所有权，也不实际执行施工。 */
public final class BuildProjectScaffoldPersistenceTest {
    private static final String WORLD = "a".repeat(64), DIMENSION = "minecraft:overworld";
    private static final BlockPos SUPPORT = new BlockPos(3, 1, 3), SECOND = new BlockPos(4, 1, 3);
    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        codecKeepsExactStateAndRejectsInvalidRecords();
        ledgerWritesOnlyConfirmedChanges();
        restartKeepsNativeOwnershipAndObservedRemoval();
        staleOrForeignRecordsCannotOverwriteEvidence();
        machineRetriesRestoreOnlyTheirOwnStage();
        legacyProjectAndScaffoldsKeepIds();
        System.out.println("BuildProjectScaffoldPersistenceTest: passed");
    }

    private static void machineRetriesRestoreOnlyTheirOwnStage() throws Exception {
        // 新的执行任务号不代表新工地；已核实的垫块必须跟着实际机器目标恢复，而不是跟着调用号丢失。
        try (var h = new InteractionWorldTestHarness()) {
            var store = new BuildProjectStore(new StateIdentity(WORLD, Files.createTempDirectory("maicraft-machine-stage-")));
            UUID actor = UUID.randomUUID();
            var targets = List.of(new BuildTaskRecord.Target(Blocks.OAK_PLANKS, Items.OAK_PLANKS,
                    new BlockPos(6, 1, 6), "machine base", null, null, null));
            var first = new BuildTaskRecord("first-machine-attempt", 100, targets, false);
            store.bindMachineStage(first, h.level, actor);
            h.set(SUPPORT, LOG); first.scaffoldLedger().confirmed(SUPPORT, LOG);
            var retry = new BuildTaskRecord("different-execute-id", 200, targets, false);
            store.bindMachineStage(retry, h.level, actor);
            check(first.projectId().equals(retry.projectId()) && retry.scaffoldLedger().owns(SUPPORT, LOG),
                    "机器重试继承同一阶段的原生支撑身份");
            var otherActor = new BuildTaskRecord("other-player", 200, targets, false);
            store.bindMachineStage(otherActor, h.level, UUID.randomUUID());
            check(otherActor.scaffoldLedger().isEmpty(), "其他玩家不能继承本角色的支撑所有权");
            h.set(SUPPORT, Blocks.CHEST.defaultBlockState());
            var changed = new BuildTaskRecord("changed-world", 200, targets, false);
            rejects(() -> store.bindMachineStage(changed, h.level, actor));
            check(h.blockUses() == 0 && h.itemUses() == 0, "不匹配的支撑只报告冲突，不能拆除替换后的箱子");
        }
    }

    private static void codecKeepsExactStateAndRejectsInvalidRecords() {
        var expected = Map.of(SUPPORT, LOG, SECOND, Blocks.DIRT.defaultBlockState());
        var encoded = BuildProjectScaffolds.encode(expected);
        check(BuildProjectScaffolds.decode(encoded).equals(expected), "坐标、方块编号和横放原木轴向全部往返保留");
        var duplicate = encoded.deepCopy(); duplicate.add(duplicate.get(0).deepCopy());
        rejects(() -> BuildProjectScaffolds.decode(duplicate));
        var incomplete = BuildProjectScaffolds.encode(Map.of(SUPPORT, LOG));
        incomplete.get(0).getAsJsonObject().getAsJsonObject("properties").remove("axis");
        rejects(() -> BuildProjectScaffolds.decode(incomplete));
        var coordinate = encoded.deepCopy(); coordinate.get(0).getAsJsonObject().addProperty("x", 1.5);
        rejects(() -> BuildProjectScaffolds.decode(coordinate));
        rejects(() -> BuildProjectScaffolds.encode(Map.of(SUPPORT, Blocks.CHEST.defaultBlockState())));
        rejects(() -> BuildProjectScaffolds.encode(Map.of(SUPPORT, Blocks.WATER.defaultBlockState())));
        rejects(() -> BuildProjectScaffolds.encode(Map.of(SUPPORT, Blocks.AIR.defaultBlockState())));
    }

    private static void ledgerWritesOnlyConfirmedChanges() {
        var ledger = new BuildScaffoldLedger(); AtomicInteger writes = new AtomicInteger();
        ledger.persistence(Map.of(SUPPORT, LOG), ignored -> writes.incrementAndGet());
        check(writes.get() == 0, "恢复既有账时不触发写入或制造新的原生放置证明");
        ledger.confirmed(SUPPORT, LOG); ledger.cleared(SECOND);
        check(writes.get() == 0, "重复确认和清理不存在条目都不产生磁盘写入");
        ledger.confirmed(SECOND, Blocks.DIRT.defaultBlockState()); ledger.cleared(SUPPORT);
        check(writes.get() == 2 && ledger.snapshot().equals(Map.of(SECOND, Blocks.DIRT.defaultBlockState())), "只在原生已确认集合真正变化时各写一次");
    }

    private static void restartKeepsNativeOwnershipAndObservedRemoval() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            Fixture f = new Fixture(); var original = f.plan();
            h.set(SUPPORT, LOG); h.set(SECOND, Blocks.DIRT.defaultBlockState());
            f.store.bindScaffolds(original, h.level);
            check(original.scaffoldLedger().isEmpty() && f.scaffoldJson() == null && !Files.exists(f.sidecar()), "旧版工程没有支撑账时为空，不能收编周围现成方块");
            String frozen = f.projectJson();
            // 给冻结项目安装只读触发器；支撑更新若误写大工程，即使内容相同也会真实失败。
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + f.directory.resolve(MemoryDatabase.FILE_NAME)); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER frozen_project BEFORE UPDATE ON memory_records WHEN OLD.scope='state/build-projects' "
                        + "BEGIN SELECT RAISE(ABORT,'project_rewrite'); END");
            }
            // 模拟本项目已得到原生放置确认，随后另一施工批次共享同一本账和保存回调。
            original.scaffoldLedger().confirmed(SUPPORT, LOG); var batch = f.plan(); original.copyExecutionContextTo(batch);
            batch.scaffoldLedger().confirmed(SECOND, Blocks.DIRT.defaultBlockState());
            check(f.store.load(f.id, DIMENSION).has("project_targets") && !f.store.load(f.id, DIMENSION).has("confirmed_scaffolds"),
                    "普通项目读取仍只返回原施工参数，支撑侧账不会冒充第二份蓝图");
            rejects(() -> f.store.load(f.id + ".scaffolds", DIMENSION));
            check(f.scaffoldJson() != null && frozen.equals(f.projectJson()) && !Files.exists(f.sidecar()), "更新小支撑账不会重写数千格冻结目标，也不再生成 JSON 文件");
            var restarted = f.plan(); new BuildProjectStore(new StateIdentity(WORLD, f.directory)).bindScaffolds(restarted, h.level);
            check(restarted.scaffoldLedger().snapshot().equals(original.scaffoldLedger().snapshot()), "新存储实例和新任务恢复同样的原生支撑所有权");
            h.set(SUPPORT, Blocks.AIR.defaultBlockState()); var afterRemoval = f.plan(); f.store.bindScaffolds(afterRemoval, h.level);
            check(afterRemoval.scaffoldLedger().snapshot().equals(Map.of(SECOND, Blocks.DIRT.defaultBlockState()))
                    && rows(f) == 1, "已记录支撑现场为空气时按移除观察更新，其他已确认支撑保留");
            h.set(SECOND, Blocks.AIR.defaultBlockState()); afterRemoval.scaffoldLedger().cleared(SECOND);
            check(rows(f) == 0, "原生清理确认后把持久支撑账同步清空");
        }
    }

    private static void staleOrForeignRecordsCannotOverwriteEvidence() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            Fixture f = new Fixture(); var original = f.plan(); f.store.bindScaffolds(original, h.level);
            h.set(SUPPORT, LOG); original.scaffoldLedger().confirmed(SUPPORT, LOG);
            String recorded = f.scaffoldJson();
            for (BlockState replacement : List.of(Blocks.STONE.defaultBlockState(), Blocks.CHEST.defaultBlockState())) {
                h.set(SUPPORT, replacement); var restored = f.plan(); rejects(() -> f.store.bindScaffolds(restored, h.level));
                check(restored.scaffoldLedger().isEmpty() && recorded.equals(f.scaffoldJson()),
                        "记录位置被替换或变成容器时拒绝恢复，不能把无法核验的账覆盖为空");
            }
            h.set(SUPPORT, LOG);
            for (String key : List.of("world_key", "project_id", "dimension", "evidence")) {
                JsonObject altered = JsonParser.parseString(recorded).getAsJsonObject();
                altered.addProperty(key, "foreign"); f.writeScaffolds(altered.toString());
                String corrupt = f.scaffoldJson(); rejects(() -> f.store.bindScaffolds(f.plan(), h.level));
                check(corrupt.equals(f.scaffoldJson()), "不同世界、项目、维度或非原生证据不被接纳也不被覆盖");
            }
            // 构造未加载位置，夹具禁止越界读取；恢复应在读取方块之前拒绝，不能强行加载区块。
            JsonObject unloaded = JsonParser.parseString(recorded).getAsJsonObject();
            unloaded.getAsJsonArray("confirmed_scaffolds").get(0).getAsJsonObject().addProperty("x", 32);
            f.writeScaffolds(unloaded.toString()); rejects(() -> f.store.bindScaffolds(f.plan(), h.level));
            check(f.scaffoldJson().equals(unloaded.toString()), "未加载记录保留原状，不悄悄当成已移除");
            f.writeScaffolds(recorded);
            var wrongPlan = new BuildTaskRecord("wrong-geometry", 100, List.of(new BuildTaskRecord.Target(
                    Blocks.STONE, Items.STONE, new BlockPos(8, 1, 8), "other", null, null, null)), false);
            wrongPlan.project(f.id, ignored -> {}); rejects(() -> f.store.bindScaffolds(wrongPlan, h.level));
            check(h.blockUses() == 0 && h.itemUses() == 0, "恢复和拒绝仅操作项目账，绝不挖掉替换方块");
        }
    }

    private static void legacyProjectAndScaffoldsKeepIds() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            Fixture f = new Fixture(); var original = f.plan(); f.store.bindScaffolds(original, h.level);
            h.set(SUPPORT, LOG); original.scaffoldLedger().confirmed(SUPPORT, LOG);
            Path oldRoot = Files.createTempDirectory("old-build-project-");
            Path project = oldRoot.resolve("build-projects").resolve(WORLD).resolve(f.id + ".json");
            Path ledger = project.resolveSibling(f.id + ".scaffolds.json"); Files.createDirectories(project.getParent());
            String projectJson = f.projectJson(), scaffoldJson = f.scaffoldJson();
            Files.writeString(project, projectJson); Files.writeString(ledger, scaffoldJson);
            // 按原项目号恢复旧工程与横放原木支撑；迁移不修改源文件，也不虚构已完成的建筑目标。
            var migrated = new BuildProjectStore(new StateIdentity(WORLD, oldRoot)); var restored = f.plan();
            migrated.bindScaffolds(restored, h.level);
            check(restored.scaffoldLedger().owns(SUPPORT, LOG) && Files.readString(project).equals(projectJson)
                    && Files.readString(ledger).equals(scaffoldJson), "旧项目或原生支撑账迁移丢失身份");
            Files.writeString(project, "old"); Files.writeString(ledger, "old");
            migrated.bindScaffolds(f.plan(), h.level);
            check(readMemory(oldRoot, "build-projects", WORLD, f.id).equals(projectJson)
                    && readMemory(oldRoot, "build-scaffolds", WORLD, f.id).equals(scaffoldJson), "重启恢复回退到了旧文件");
        }
    }

    private static final class Fixture {
        final Path directory; final BuildProjectStore store; final String id;
        final List<BuildTaskRecord.Target> targets = List.of(new BuildTaskRecord.Target(Blocks.OAK_PLANKS, Items.OAK_PLANKS,
                new BlockPos(6, 1, 6), "floor", null, null, null));
        Fixture() throws Exception {
            directory = Files.createTempDirectory("maicraft-project-scaffolds-"); store = new BuildProjectStore(new StateIdentity(WORLD, directory));
            id = store.save(DIMENSION, new JsonObject(), targets);
        }
        BuildTaskRecord plan() { var result = new BuildTaskRecord("resume", 100, targets, false); result.project(id, frozen -> store.save(id, DIMENSION, new JsonObject(), frozen.targets)); return result; }
        Path sidecar() { return directory.resolve("build-projects").resolve(WORLD).resolve(id + ".scaffolds.json"); }
        String projectJson() throws Exception { return readMemory(directory, "build-projects", WORLD, id); }
        String scaffoldJson() throws Exception { return readMemory(directory, "build-scaffolds", WORLD, id); }
        void writeScaffolds(String json) throws Exception { writeMemory(directory, "build-scaffolds", WORLD, id, json); }
    }
    private static int rows(Fixture fixture) throws Exception { return JsonParser.parseString(fixture.scaffoldJson()).getAsJsonObject().getAsJsonArray("confirmed_scaffolds").size(); }
    private static void rejects(Runnable run) { try { run.run(); throw new AssertionError("不安全支撑恢复被接纳"); } catch (IllegalArgumentException expected) { } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
