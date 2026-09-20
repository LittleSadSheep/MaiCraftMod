// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import net.minecraft.world.level.block.Blocks;
import static org.maiwithu.maicraft.core.blueprint.BuildingModelTestData.*;

/** 只检查设计数据和导入登记；超世界高度的坐标不是原生可施工承诺，测试不创建世界或启动身体任务。 */
public final class BuildingCoordinateBudgetTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var directory = Files.createTempDirectory("building-coordinate-budget-");
        Files.createDirectories(directory.resolve("config"));
        Files.writeString(directory.resolve(BuildingBudgets.CONFIG_PATH),
                "maxRadius=4096\nmaxObjects=2147483647\nmaxConnections=2147483647\n");
        try {
            BuildingBudgets.initialize(directory);
            var targets = coordinateRoundTrip(); loaderCoordinateRecords(targets); aggregateCounters();
        } finally {
            // 恢复独立默认快照，避免极大数值边界影响后续测试；不修改用户游戏目录。
            BuildingBudgets.initialize(directory.resolve("restore-defaults"));
        }
        System.out.println("BuildingCoordinateBudgetTest: coordinate retention and aggregate limits passed; no native construction tested");
    }

    private static JsonObject tallDesign() {
        // 两个一格图元相差4096层，处于配置半径内；旧压缩坐标会把它们当作同一格。
        return scene(mesh("Lower", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"),
                mesh("Upper", "cube", new double[]{.5, 4096.5, .5}, new int[]{1, 1, 1}, "Glass"));
    }

    private static List<BuildTaskRecord.Target> coordinateRoundTrip() {
        JsonObject blueprint = BuildingSceneCompiler.compile(tallDesign()); JsonArray ops = new JsonArray();
        for (var value : blueprint.getAsJsonArray("blocks")) {
            JsonObject cell = value.getAsJsonObject(), op = new JsonObject(); var at = cell.getAsJsonArray("offset");
            op.addProperty("op", "set"); op.add("block_id", cell.get("block_id"));
            op.add("x", at.get(0)); op.add("y", at.get(1)); op.add("z", at.get(2)); ops.add(op);
        }
        var targets = BuildTool.resolvedTargets(ops, true);
        check(targets.size() == 2 && targets.get(0).pos().equals(BlockPos.ZERO)
                && targets.get(1).pos().equals(new BlockPos(0, 4096, 0)), "大半径设计解析时丢失完整坐标");
        check(targets.get(0).pos().asLong() == targets.get(1).pos().asLong(), "夹具必须真实覆盖压缩坐标碰撞");
        var restored = BuildProjectTargets.decode(BuildProjectTargets.encode(targets));
        check(restored.size() == 2 && restored.get(1).pos().equals(targets.get(1).pos()), "冻结目标往返合并了不同高度");
        // 后写仍只覆盖真正同坐标的目标，保留另一层的材料和位置。
        ops.add(ops.get(0).deepCopy());
        ops.get(2).getAsJsonObject().addProperty("block_id", "minecraft:dirt");
        var replaced = BuildTool.resolvedTargets(ops, true);
        check(replaced.size() == 2 && replaced.get(0).block() == Blocks.DIRT
                && replaced.get(1).block() == targets.get(1).block(), "完整坐标去重改变了同格后写覆盖规则");
        return targets;
    }

    private static void loaderCoordinateRecords(List<BuildTaskRecord.Target> targets) throws Exception {
        var loader = new BlueprintStore.Loader(BuildingSceneExport.structure(BuildingSceneCompiler.compile(tallDesign())), BlockPos.ZERO, 0);
        CompoundTag decoration = new CompoundTag(); decoration.putString("fixture", "lower-decoration");
        var costs = List.of(new BuildTaskRecord.CellNeed(Items.STONE.getDefaultInstance(), false));
        // 直接推进生产使用的登记步骤，不伪造客户端世界或声称这些附带记录来自真实施工。
        retain(loader, targets.get(0), decoration, costs); retain(loader, targets.get(1), null, List.of());
        check(records(loader, "byPos").size() == 2 && records(loader, "beData").get(0L).equals(decoration)
                && records(loader, "needs").get(0L).equals(costs), "远处普通目标覆盖了底层目标或附带记录");
        rejectsWith(() -> retain(loader, targets.get(1), decoration, List.of()), "cannot represent position");
        rejectsWith(() -> retain(loader, targets.get(1), null, costs), "cannot represent position");
        check(records(loader, "byPos").size() == 2 && records(loader, "beData").get(0L).equals(decoration)
                && records(loader, "needs").get(0L).equals(costs), "不可表达的附带数据在拒绝前改写了已有记录");
        retain(loader, targets.get(0), null, List.of());
        check(records(loader, "byPos").size() == 2 && records(loader, "beData").isEmpty()
                && records(loader, "needs").isEmpty(), "真正同格覆盖时没有清理原装饰和材料要求");
    }

    private static void aggregateCounters() throws Exception {
        var solid = mesh("Solid", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body");
        solid.add("modifiers", json("{\"cuts\":[{\"type\":\"BOOLEAN\",\"operation\":\"DIFFERENCE\",\"object\":\"Cut\"}]}").get("cuts"));
        var cutter = mesh("Cut", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, null);
        cutter.addProperty("role", "cutter");
        // 用两份真实节点接续接近整数上界的聚合状态，验证加法先保持精度再拒绝，无需分配数十亿图元。
        for (String counter : List.of("authoredNodes", "declaredModifiers", "expandedNodes", "expandedCuts")) {
            var model = BuildingModelExpansion.expand(scene(solid, cutter));
            var field = BuildingModelExpansion.class.getDeclaredField(counter); field.setAccessible(true);
            field.set(model, counter.equals("authoredNodes") ? Integer.MAX_VALUE - 1 : Integer.MAX_VALUE);
            var objects = model.scene.getAsJsonArray("objects");
            if (counter.equals("authoredNodes") || counter.equals("declaredModifiers")) {
                rejectsWith(() -> invoke(model, "inspectList", new Class<?>[]{JsonArray.class}, objects),
                        counter.equals("authoredNodes") ? "component budget" : "modifier budget");
            } else {
                rejectsWith(() -> invoke(model, "expandList", new Class<?>[]{JsonArray.class, String.class,
                                BuildingModelTransform.class, List.class, List.class, String.class, int.class},
                        objects, "", BuildingModelTransform.identity(), List.of(), List.of(), "local", 0),
                        counter.equals("expandedNodes") ? "component budget" : "modifier budget");
            }
        }
    }

    // 反射仅绕过对真实客户端上下文的依赖，调用的仍是导入器实际使用的目标登记与模型累计步骤。
    private static void retain(BlueprintStore.Loader loader, BuildTaskRecord.Target target, CompoundTag data,
                               List<BuildTaskRecord.CellNeed> needs) throws Exception {
        invoke(loader, "retainTarget", new Class<?>[]{BuildTaskRecord.Target.class, CompoundTag.class, List.class}, target, data, needs);
    }
    private static Map<?, ?> records(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return (Map<?, ?>) field.get(owner);
    }
    private static Object invoke(Object owner, String name, Class<?>[] signature, Object... args) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name, signature); method.setAccessible(true);
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception exception) throw exception;
            throw failed;
        }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejectsWith(Checked operation, String fragment) throws Exception {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains(fragment), expected.getMessage()); return; }
        throw new AssertionError("应在登记前拒绝越界坐标或聚合预算: " + fragment);
    }
}
