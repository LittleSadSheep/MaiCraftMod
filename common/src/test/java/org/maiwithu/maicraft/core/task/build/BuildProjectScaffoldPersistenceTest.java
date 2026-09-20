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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
        System.out.println("BuildProjectScaffoldPersistenceTest: passed");
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
            check(original.scaffoldLedger().isEmpty() && !Files.exists(f.sidecar()), "旧版工程没有支撑账时为空，不能收编周围现成方块");
            byte[] frozen = Files.readAllBytes(f.project()); var frozenTime = Files.getLastModifiedTime(f.project());
            // 模拟本项目已得到原生放置确认，随后另一施工批次共享同一本账和保存回调。
            original.scaffoldLedger().confirmed(SUPPORT, LOG); var batch = f.plan(); original.copyExecutionContextTo(batch);
            batch.scaffoldLedger().confirmed(SECOND, Blocks.DIRT.defaultBlockState());
            check(f.store.load(f.id, DIMENSION).has("project_targets") && !f.store.load(f.id, DIMENSION).has("confirmed_scaffolds"),
                    "普通项目读取仍只返回原施工参数，支撑侧账不会冒充第二份蓝图");
            rejects(() -> f.store.load(f.id + ".scaffolds", DIMENSION));
            check(Files.exists(f.sidecar()) && Arrays.equals(frozen, Files.readAllBytes(f.project()))
                    && frozenTime.equals(Files.getLastModifiedTime(f.project())), "更新小支撑账不会重写数千格冻结目标文件");
            var restarted = f.plan(); new BuildProjectStore(new StateIdentity(WORLD, f.directory)).bindScaffolds(restarted, h.level);
            check(restarted.scaffoldLedger().snapshot().equals(original.scaffoldLedger().snapshot()), "新存储实例和新任务恢复同样的原生支撑所有权");
            h.set(SUPPORT, Blocks.AIR.defaultBlockState()); var afterRemoval = f.plan(); f.store.bindScaffolds(afterRemoval, h.level);
            check(afterRemoval.scaffoldLedger().snapshot().equals(Map.of(SECOND, Blocks.DIRT.defaultBlockState()))
                    && rows(f.sidecar()) == 1, "已记录支撑现场为空气时按移除观察更新，其他已确认支撑保留");
            h.set(SECOND, Blocks.AIR.defaultBlockState()); afterRemoval.scaffoldLedger().cleared(SECOND);
            check(rows(f.sidecar()) == 0, "原生清理确认后把持久支撑账同步清空");
        }
    }

    private static void staleOrForeignRecordsCannotOverwriteEvidence() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            Fixture f = new Fixture(); var original = f.plan(); f.store.bindScaffolds(original, h.level);
            h.set(SUPPORT, LOG); original.scaffoldLedger().confirmed(SUPPORT, LOG);
            byte[] recorded = Files.readAllBytes(f.sidecar());
            for (BlockState replacement : List.of(Blocks.STONE.defaultBlockState(), Blocks.CHEST.defaultBlockState())) {
                h.set(SUPPORT, replacement); var restored = f.plan(); rejects(() -> f.store.bindScaffolds(restored, h.level));
                check(restored.scaffoldLedger().isEmpty() && Arrays.equals(recorded, Files.readAllBytes(f.sidecar())),
                        "记录位置被替换或变成容器时拒绝恢复，不能把无法核验的账覆盖为空");
            }
            h.set(SUPPORT, LOG);
            for (String key : List.of("world_key", "project_id", "dimension", "evidence")) {
                JsonObject altered = JsonParser.parseString(new String(recorded, StandardCharsets.UTF_8)).getAsJsonObject();
                altered.addProperty(key, "foreign"); Files.writeString(f.sidecar(), altered.toString());
                byte[] corrupt = Files.readAllBytes(f.sidecar()); rejects(() -> f.store.bindScaffolds(f.plan(), h.level));
                check(Arrays.equals(corrupt, Files.readAllBytes(f.sidecar())), "不同世界、项目、维度或非原生证据不被接纳也不被覆盖");
            }
            // 构造未加载位置，夹具禁止越界读取；恢复应在读取方块之前拒绝，不能强行加载区块。
            JsonObject unloaded = JsonParser.parseString(new String(recorded, StandardCharsets.UTF_8)).getAsJsonObject();
            unloaded.getAsJsonArray("confirmed_scaffolds").get(0).getAsJsonObject().addProperty("x", 32);
            Files.writeString(f.sidecar(), unloaded.toString()); rejects(() -> f.store.bindScaffolds(f.plan(), h.level));
            check(Files.readString(f.sidecar()).equals(unloaded.toString()), "未加载记录保留原状，不悄悄当成已移除");
            Files.write(f.sidecar(), recorded);
            var wrongPlan = new BuildTaskRecord("wrong-geometry", 100, List.of(new BuildTaskRecord.Target(
                    Blocks.STONE, Items.STONE, new BlockPos(8, 1, 8), "other", null, null, null)), false);
            wrongPlan.project(f.id, ignored -> {}); rejects(() -> f.store.bindScaffolds(wrongPlan, h.level));
            check(h.blockUses() == 0 && h.itemUses() == 0, "恢复和拒绝仅操作项目账，绝不挖掉替换方块");
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
        Path project() { return directory.resolve("build-projects").resolve(WORLD).resolve(id + ".json"); }
        Path sidecar() { return directory.resolve("build-projects").resolve(WORLD).resolve(id + ".scaffolds.json"); }
    }
    private static int rows(Path file) throws Exception { return JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("confirmed_scaffolds").size(); }
    private static void rejects(Runnable run) { try { run.run(); throw new AssertionError("不安全支撑恢复被接纳"); } catch (IllegalArgumentException expected) { } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
