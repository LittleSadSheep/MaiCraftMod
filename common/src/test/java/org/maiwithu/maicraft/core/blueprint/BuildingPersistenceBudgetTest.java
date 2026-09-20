// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import java.lang.reflect.Method;
import java.util.UUID;

/** 建筑模型、冻结施工单和自有支撑各用自己的配置预算；失败不发布半份记录，恢复不再被旧常量截断。 */
public final class BuildingPersistenceBudgetTest {
    private static final String WORLD = "c".repeat(64), DIMENSION = "minecraft:overworld";
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path directory = Files.createTempDirectory("building-persistence-budget-");
        try {
            configure(directory, "");
            // 真正编码和恢复跨过旧一万六千格/四千根门槛，不能只确认配置 getter 变大。
            var manyTargets = targets(16_385);
            var restored = BuildProjectTargets.decode(BuildProjectTargets.encode(manyTargets));
            check(restored.size() == 16_385 && restored.getLast().pos().equals(manyTargets.getLast().pos()), "大型冻结目标仍被旧上限截断");
            check(BuildProjectScaffolds.decode(BuildProjectScaffolds.encode(scaffolds(4_097))).size() == 4_097, "大型支撑账仍受旧四千条限制");
            sceneBudgets(directory); projectBudgets(directory); scaffoldBudgets(directory);
        } finally {
            // 测试只替换临时配置快照，结束后恢复默认预算，让其他游戏回归使用同一启动状态。
            BuildingBudgets.initialize(Files.createTempDirectory("building-persistence-defaults-"));
        }
        System.out.println("BuildingPersistenceBudgetTest: passed");
    }

    private static void sceneBudgets(Path directory) throws Exception {
        JsonObject scene = json("""
                {"schema_version":1,"coordinate_system":"minecraft_y_up","materials":{"Body":{"block_id":"minecraft:stone"}},
                 "objects":[{"name":"Wall","type":"cube","location":[0.5,0.5,0.5],"dimensions":[1,1,1],"material":"Body"}]}
                """);
        // 材料库也是作者输入，体素只有一格；借此独立验证文件预算，不把文件大小和目标数量混为一谈。
        for (int i = 0; i < 160; i++) scene.getAsJsonObject("materials").add("DecorationMaterial" + i, json("{\"block_id\":\"minecraft:stone\"}"));
        var store = new BuildingSceneStore(directory, WORLD); var anchor = new Goal.WorldPosition(0, 64, 0, DIMENSION);
        configure(directory, "maxSceneBytes=4096\n"); rejects(() -> store.save(scene, anchor));
        configure(directory, "maxSceneBytes=32768\n"); var saved = store.save(scene, anchor);
        check(store.load(saved.sceneId(), DIMENSION).scene().equals(scene), "调高场景字节预算后作者字段未完整保存");
        configure(directory, "maxSceneBytes=4096\n"); rejects(() -> store.load(saved.sceneId(), DIMENSION));

        JsonObject twoEdits = json("{\"objects\":[{\"name\":\"Wall\"},{\"name\":\"Other\"}]}");
        configure(directory, "maxObjects=1\n"); rejects(() -> BuildingSceneStore.validateEdits(twoEdits));
        configure(directory, "maxObjects=2\n"); BuildingSceneStore.validateEdits(twoEdits);
    }

    private static void projectBudgets(Path directory) throws Exception {
        var three = targets(3); JsonArray encoded = BuildProjectTargets.encode(three);
        configure(directory, "maxTargets=2\n");
        rejects(() -> BuildProjectTargets.encode(three)); rejects(() -> BuildProjectTargets.decode(encoded));
        configure(directory, "maxTargets=3\n");
        check(BuildProjectTargets.decode(encoded).size() == 3, "提高冻结目标数量预算后不能恢复完整施工单");

        // 项目文件包含完整坐标和验收属性，与小型作者模型独立计费；低预算失败后可原样重试。
        var store = new BuildProjectStore(new StateIdentity(WORLD, directory)); var forty = targets(40);
        configure(directory, "maxProjectBytes=4096\n"); rejects(() -> store.save(DIMENSION, new JsonObject(), forty));
        configure(directory, "maxProjectBytes=65536\n"); String id = store.save(DIMENSION, new JsonObject(), forty);
        check(store.load(id, DIMENSION).getAsJsonArray("project_targets").size() == 40, "项目未按调高后的文件预算保存");
        configure(directory, "maxProjectBytes=4096\n"); rejects(() -> store.load(id, DIMENSION));
    }

    private static void scaffoldBudgets(Path directory) throws Exception {
        configure(directory, ""); var three = scaffolds(3); JsonArray encoded = BuildProjectScaffolds.encode(three);
        configure(directory, "maxScaffolds=2\n");
        rejects(() -> BuildProjectScaffolds.encode(three)); rejects(() -> BuildProjectScaffolds.decode(encoded));
        configure(directory, "maxScaffolds=3\n"); check(BuildProjectScaffolds.decode(encoded).size() == 3, "提高支撑记录预算未生效");

        // 只直接测试 sidecar 的文件读写边界；不调用世界恢复，绝不把这些夹具记录当作真实放置证据。
        var store = new BuildProjectStore(new StateIdentity(WORLD, directory)); String id = UUID.randomUUID().toString();
        Path sidecar = directory.resolve("budget-scaffolds.json"); var hundred = scaffolds(100);
        var write = BuildProjectStore.class.getDeclaredMethod("saveScaffolds", Path.class, String.class, String.class, Map.class);
        var read = BuildProjectStore.class.getDeclaredMethod("readScaffolds", Path.class, String.class, String.class);
        write.setAccessible(true); read.setAccessible(true);
        configure(directory, "maxScaffoldBytes=4096\n"); rejects(() -> invoke(write, store, sidecar, id, DIMENSION, hundred));
        configure(directory, "maxScaffoldBytes=65536\n"); invoke(write, store, sidecar, id, DIMENSION, hundred);
        check(((Map<?, ?>) invoke(read, store, sidecar, id, DIMENSION)).size() == 100, "支撑字节预算升高后没有完整恢复记录");
        configure(directory, "maxScaffoldBytes=4096\n"); rejects(() -> invoke(read, store, sidecar, id, DIMENSION));
    }

    private static List<BuildTaskRecord.Target> targets(int count) {
        var result = new ArrayList<BuildTaskRecord.Target>();
        for (int i = 0; i < count; i++) result.add(new BuildTaskRecord.Target(Blocks.STONE.defaultBlockState(), Items.STONE,
                new BlockPos(i % 128, 64 + i / 128, 0), "stone", null, null, null));
        return List.copyOf(result);
    }
    private static Map<BlockPos, BlockState> scaffolds(int count) {
        var result = new LinkedHashMap<BlockPos, BlockState>();
        for (int i = 0; i < count; i++) result.put(new BlockPos(i % 128, 64 + i / 128, 1), Blocks.DIRT.defaultBlockState());
        return result;
    }
    private static void configure(Path directory, String properties) throws Exception {
        Path config = directory.resolve(BuildingBudgets.CONFIG_PATH); Files.createDirectories(config.getParent());
        Files.writeString(config, properties); BuildingBudgets.initialize(directory);
    }
    private static Object invoke(Method method, Object owner, Object... arguments) throws Exception {
        try { return method.invoke(owner, arguments); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception exception) throw exception;
            throw failed;
        }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked operation) throws Exception {
        try { operation.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("超预算持久化被接受");
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
