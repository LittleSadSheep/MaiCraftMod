// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementTaskRecord;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.border.WorldBorder;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import java.lang.reflect.Field;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import java.nio.file.Files;
import java.util.UUID;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.Identity;

/** 让 L 形多源池与普通结构混排，检查蓝图、材料和准备清空都保留源格真实语义，不固定成单格池。 */
public final class MachineFluidConstructionTest {
    private static final Set<BlockPos> SOURCES = Set.of(new BlockPos(2,1,2),new BlockPos(3,1,2),new BlockPos(3,1,3),new BlockPos(4,1,3));
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var blueprint = blueprint(); var layout = MachineBlueprintDocument.compile(blueprint, MachineConstructionPlan.registry());
        check(layout.buildable(), "源流体蓝图必须经过实际机器格式与状态校验");
        var plan = MachineConstructionPlan.compile(BlockPos.ZERO, layout, false);
        check(plan.fluidTargets().size() == SOURCES.size() && plan.positions().containsAll(SOURCES), "任意池形源格不能从计划位置中消失");
        check(plan.fluidTargets().stream().allMatch(target -> target.item() == Items.WATER_BUCKET
                        && target.desiredState().getFluidState().isSource() && target.exactProperties().contains("level")),
                "省略液位仍要求最终源状态，并用原生满桶作材料");
        check(SOURCES.stream().allMatch(at -> plan.preview().get(at).is(Blocks.WATER)), "预览必须显示真实水源，而不是准备空气");
        var blockTask = plan.blockTask("fluid-plan", 1000, true);
        check(blockTask.targets.stream().noneMatch(target -> SOURCES.contains(target.pos())), "普通施工不能把流体源转成空气目标去挖");
        check(plan.report().getAsJsonObject("native_material_counts").get("minecraft:water_bucket").getAsInt() == SOURCES.size(), "原生材料报告应包含每个源格的满桶上界");
        check(MachineConstructionPlan.reviewExplicit(layout).report().getAsJsonObject("native_material_counts").has("minecraft:water_bucket"), "设计评审应保留实际桶材料");
        check(MachinePlacementItems.itemFor(Blocks.LAVA.defaultBlockState()) == Items.LAVA_BUCKET, "流体桶映射不能写死为水桶");
        rejects(() -> MachinePlacementRules.resolveState("minecraft:water", Map.of("level","1")));
        var copied = blueprint.deepCopy(); var first = copied.getAsJsonArray("blocks").asList().stream()
                .map(value -> value.getAsJsonObject()).filter(value -> value.get("block_id").getAsString().equals("minecraft:water")).findFirst().orElseThrow();
        JsonObject nbt = new JsonObject(); nbt.addProperty("fabricated_contents", true); first.add("nbt", nbt);
        rejects(() -> MachineConstructionPlan.compile(BlockPos.ZERO, MachineBlueprintDocument.compile(copied, MachineConstructionPlan.registry()), false));
        try (var world = new InteractionWorldTestHarness()) {
            var border = Level.class.getDeclaredField("worldBorder"); border.setAccessible(true); border.set(world.level, new WorldBorder());
            BlockPos at = SOURCES.iterator().next(); world.set(at, Blocks.WATER.defaultBlockState());
            var reused = new MachineBuildSurvey(plan);
            check(reused.tick(world.level).complete() && !reused.partClears().contains(at), "已有正确源格必须直接保留");
            world.set(at, Blocks.STONE.defaultBlockState());
            var nativeOnly = new MachineBuildSurvey(plan);
            check(nativeOnly.tick(world.level).complete() && !nativeOnly.partClears().contains(at), "未授权拆换时保留普通方块，允许后续原生桶尝试而不隐式挖掉它");
            var replace = MachineConstructionPlan.compile(BlockPos.ZERO, layout, true);
            var prepared = new MachineBuildSurvey(replace); check(prepared.tick(world.level).complete() && prepared.partClears().contains(at), "明确授权后才将普通障碍加入准备清空");
            var protectedResult = NavigationSafetyContext.withProtectedArea(List.of(at), List.of(), () -> new MachineBuildSurvey(replace).tick(world.level));
            check(protectedResult.failure() != null, "替换授权不能绕过明确的保护格");
            world.set(at, Blocks.LAVA.defaultBlockState());
            check(new MachineBuildSurvey(replace).tick(world.level).complete(), "另一种流体不再提前阻止声明的原生倒桶");
            world.set(at, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
            check(!plan.fluidTargets().stream().filter(target -> target.pos().equals(at)).findFirst().orElseThrow().matches(world.level.getBlockState(at)),
                    "流进来的同种水不能冒充蓝图要求的源格");
        }
        constructionKeepsNativeReceipt(plan);
        modificationRecoversDeclaredSourcesBeforeBuilding();
        reactionProducesBlueprintDiff();
        unavailableComparisonSaysSo();
        System.out.println("MachineFluidConstructionTest: passed");
    }
    private static void reactionProducesBlueprintDiff() throws Exception {
        try (var world = new InteractionWorldTestHarness(); var archive = new ComparisonArchive(world)) {
            // 原生倒桶已把设计水源变成黑曜石；动作任务正常结束，附diff供模型判断，不替它否决设计。
            var document = JsonParser.parseString("{\"blocks\":[{\"offset\":[3,1,3],\"block_id\":\"minecraft:water\"}]}").getAsJsonObject();
            var plan = MachineConstructionPlan.compile(BlockPos.ZERO, MachineBlueprintDocument.compile(document, MachineConstructionPlan.registry()), false);
            world.set(new BlockPos(3,1,3), Blocks.OBSIDIAN.defaultBlockState());
            var task = new MachineBuildTask(world.player, new MachineBuildTaskRecord("reaction-diff", 1000, plan,
                    "minecraft:overworld", MaterialPolicy.INVENTORY_ONLY, List.of()));
            field("fluidIndex").setInt(task, 1);
            var verify = MachineBuildTask.class.getDeclaredMethod("verify"); verify.setAccessible(true);
            check(verify.invoke(task) == TaskState.RUNNING, "布局不符先进入差异扫描而非丢掉现场差异");
            var compare = MachineBuildTask.class.getDeclaredMethod("compareCompletedMachine"); compare.setAccessible(true);
            check(compare.invoke(task) == TaskState.SUCCESS, "静态差异不能把已执行完的动作任务改判失败");
            var result = task.result(TaskState.SUCCESS);
            var diff = (JsonObject) result.data().get("blueprint_diff");
            check(diff.get("comparison_complete").getAsBoolean() && !diff.get("structure_matches_blueprint").getAsBoolean()
                            && diff.getAsJsonArray("differences").size() == 1 && diff.toString().contains("minecraft:obsidian")
                            && ((Number) result.data().get("verified_source_fluid_targets")).intValue() == 0,
                    "回执给出真实黑曜石差异，已处理一桶不能冒充已有一个水源");
            check(result.message().contains("blueprint differences are attached"), "有真实对档时收尾话术才声称差异已附");
        }
    }

    /** 对档前置缺失时收尾话术必须如实说"不可用"，不能沿用"差异已附"的样板（issue 024）。 */
    private static void unavailableComparisonSaysSo() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var document = JsonParser.parseString("{\"blocks\":[{\"offset\":[3,1,3],\"block_id\":\"minecraft:water\"}]}").getAsJsonObject();
            var plan = MachineConstructionPlan.compile(BlockPos.ZERO, MachineBlueprintDocument.compile(document, MachineConstructionPlan.registry()), false);
            var task = new MachineBuildTask(world.player, new MachineBuildTaskRecord("diff-unavailable", 1000, plan,
                    "minecraft:overworld", MaterialPolicy.INVENTORY_ONLY, List.of()));
            var begin = MachineBuildTask.class.getDeclaredMethod("beginComparison"); begin.setAccessible(true);
            check(begin.invoke(task) == TaskState.SUCCESS, "无档案时对档以占位收场，不再等待");
            var result = task.result(TaskState.SUCCESS);
            var diff = (JsonObject) result.data().get("blueprint_diff");
            check(!diff.get("comparison_complete").getAsBoolean()
                            && "full_machine_blueprint_unavailable".equals(diff.get("reason").getAsString())
                            && !Boolean.TRUE.equals(result.data().get("machine_geometry_verified")),
                    "对档占位如实标注不可用原因，整机几何不虚报已核实");
            check(result.message().contains("comparison is unavailable")
                            && result.message().contains("full_machine_blueprint_unavailable")
                            && !result.message().contains("differences are attached"),
                    "无对档时收尾话术不得声称差异已附");
        }
    }

    /** 流体反应后的整机比较需要真实目录前置；夹具补齐登记环境，不能强迫生产代码在缺档时伪造整机 diff。 */
    private static final class ComparisonArchive implements AutoCloseable {
        private final Map<Field, Object> saved = new LinkedHashMap<>();
        private final Map<String, Map<Object, Object>> caches = new LinkedHashMap<>();

        @SuppressWarnings("unchecked") ComparisonArchive(InteractionWorldTestHarness world) throws Exception {
            for (String name : List.of("catalog", "level", "playerId", "issue")) {
                Field field = catalogField(name); saved.put(field, field.get(null));
            }
            for (String name : List.of("compiledBlueprints", "pendingBuilt")) {
                var cache = (Map<Object, Object>) catalogField(name).get(null);
                caches.put(name, new LinkedHashMap<>(cache)); cache.clear();
            }
            var catalog = new MachineCatalog(Files.createTempDirectory("fluid-comparison-archive-"), Runnable::run);
            catalog.bind(new Identity("fluid-comparison", "player"), "test");
            UUID playerId = UUID.randomUUID(); Field uuid = Entity.class.getDeclaredField("uuid"); uuid.setAccessible(true); uuid.set(world.player, playerId);
            catalogField("catalog").set(null, catalog); catalogField("level").set(null, world.level); catalogField("playerId").set(null, playerId);
        }

        @SuppressWarnings("unchecked") public void close() throws Exception {
            for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
            for (var entry : caches.entrySet()) {
                var cache = (Map<Object, Object>) catalogField(entry.getKey()).get(null); cache.clear(); cache.putAll(entry.getValue());
            }
        }

        private static Field catalogField(String name) throws Exception {
            Field field = ClientMachineCatalog.class.getDeclaredField(name); field.setAccessible(true); return field;
        }
    }
    private static void modificationRecoversDeclaredSourcesBeforeBuilding() throws Exception {
        BlockPos at = new BlockPos(3,1,3), outside = new BlockPos(7,1,3);
        var document = JsonParser.parseString("""
                {"blocks":[{"offset":[3,1,3],"block_id":"minecraft:air"}]}
                """).getAsJsonObject();
        var survey = MachineBuildTask.class.getDeclaredMethod("surveyParts"); survey.setAccessible(true);
        var remove = MachineBuildTask.class.getDeclaredMethod("removeFluids"); remove.setAccessible(true);
        try (var world = new InteractionWorldTestHarness()) {
            // 原位修改的空气目标遇到水源时先派发取桶任务，旁边未声明的水源不属于这次改造。
            var border = Level.class.getDeclaredField("worldBorder"); border.setAccessible(true); border.set(world.level,new WorldBorder());
            world.set(at,Blocks.WATER.defaultBlockState()); world.set(outside,Blocks.WATER.defaultBlockState());
            world.inventory.setItem(0,new ItemStack(Items.BUCKET));
            var plan = MachineConstructionPlan.compile(BlockPos.ZERO,MachineBlueprintDocument.compile(document,MachineConstructionPlan.registry()),true,true);
            plan.bindAutomaticModification(world.level);
            var running = new MachineBuildTask(world.player,new MachineBuildTaskRecord("remove-source",1000,plan,
                    "minecraft:overworld",MaterialPolicy.INVENTORY_ONLY,List.of()));
            survey.invoke(running); check(field("phase").get(running).toString().equals("REMOVE_FLUIDS"),"modification prepares source recovery before block excavation");
            remove.invoke(running);
            var child = (FluidPlacementTaskRecord) field("childRecord").get(running);
            check(child.target.equals(at) && child.bucket == Items.BUCKET && child.expected.isAir()
                    && child.removedSource.is(Blocks.WATER) && world.level.getBlockState(outside).is(Blocks.WATER)
                    && world.itemUses() == 0, "planning a source pickup never edits the world or collects an undeclared neighbor");
            running.result(TaskState.CANCELLED);
        }
        try (var world = new InteractionWorldTestHarness()) {
            // 流水不能用空桶反复捞；直接推进可执行的固体施工，最终流水状态交给diff观察。
            var border = Level.class.getDeclaredField("worldBorder"); border.setAccessible(true); border.set(world.level,new WorldBorder());
            world.set(at,Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,3));
            var plan = MachineConstructionPlan.compile(BlockPos.ZERO,MachineBlueprintDocument.compile(document,MachineConstructionPlan.registry()),true,true);
            plan.bindAutomaticModification(world.level);
            var running = new MachineBuildTask(world.player,new MachineBuildTaskRecord("wait-drain",1000,plan,
                    "minecraft:overworld",MaterialPolicy.INVENTORY_ONLY,List.of()));
            survey.invoke(running); remove.invoke(running);
            check(field("childRecord").get(running) == null && field("phase").get(running).toString().equals("BLOCKS")
                    && world.itemUses() == 0,"flowing cells neither trigger bucket retries nor prevent the solid construction stage");
            running.result(TaskState.CANCELLED);
        }
    }
    private static void constructionKeepsNativeReceipt(MachineConstructionPlan plan) throws Exception {
        // 不确定的桶可能由子任务失败、总任务超时或用户取消收场；三种入口都必须留下同一份账，交给上层决定恢复。
        for (TaskState terminal : List.of(TaskState.FAILED, TaskState.TIMEOUT, TaskState.CANCELLED)) {
            try (var world = new InteractionWorldTestHarness()) {
                var record = new MachineBuildTaskRecord("fluid-receipt", 1000, plan, "minecraft:overworld", MaterialPolicy.INVENTORY_ONLY, List.of());
                var construction = new MachineBuildTask(world.player, record);
                var receipt = new ReceiptProbe(Map.of("outcome_uncertain", true, "bucket_submitted", true));
                field("child").set(construction, receipt); field("childRecord").set(construction, record);
                if (terminal == TaskState.FAILED) {
                    var tick = MachineBuildTask.class.getDeclaredMethod("tickChild"); tick.setAccessible(true);
                    check(tick.invoke(construction) == TaskState.FAILED, "子桶回执失败应终止机器施工");
                }
                var result = construction.result(terminal); construction.result(terminal);
                check(Boolean.TRUE.equals(result.data().get("outcome_uncertain"))
                                && Boolean.FALSE.equals(result.data().get("mechanical_retry_allowed"))
                                && result.data().get("last_native_stage").equals(receipt.data) && receipt.results == 1,
                        "施工结束应把桶未知结果提升顶层，并且重复读结果不能重复结算子动作");
                check(receipt.stops == (terminal == TaskState.FAILED ? 0 : 1), "外层收场须停止活子任务，正常失败已由子任务结束");
            }
        }
        try (var world = new InteractionWorldTestHarness()) {
            var record = new MachineBuildTaskRecord("fluid-safe-reuse",1000,plan,"minecraft:overworld",MaterialPolicy.INVENTORY_ONLY,List.of());
            var construction = new MachineBuildTask(world.player,record);
            field("lastChild").set(construction, Map.of("outcome_uncertain",false,"mechanical_retry_allowed",true,"already_present",true));
            var reusable = construction.result(TaskState.CANCELLED);
            check(!Boolean.TRUE.equals(reusable.data().get("outcome_uncertain")) && !Boolean.FALSE.equals(reusable.data().get("mechanical_retry_allowed")),
                    "正确已有源格的只读复用不能被误归为桶消费不确定");
            field("lastChild").set(construction, Map.of("mechanical_retry_allowed",false));
            check(Boolean.FALSE.equals(construction.result(TaskState.CANCELLED).data().get("mechanical_retry_allowed")),
                    "原生子动作单独给出的禁重试也须向上传递");
        }
    }
    private static Field field(String name) throws Exception {
        var field = MachineBuildTask.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static final class ReceiptProbe implements Task {
        final Map<String,Object> data; int stops, results;
        ReceiptProbe(Map<String,Object> data) { this.data=data; }
        @Override public TaskState tick(LocalPlayer player) { return TaskState.FAILED; }
        @Override public void stop(LocalPlayer player, StopReason reason) { stops++; }
        @Override public TaskResult result(TaskState terminal) { results++; return TaskResult.fail("unconfirmed native bucket",data); }
        @Override public String name() { return "bucket-receipt-probe"; }
    }
    private static JsonObject blueprint() {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        // 源格和围挡交错加入，普通施工仍应自行分出液体安装阶段。
        for (BlockPos at : SOURCES) {
            cells.put(at, Blocks.WATER.defaultBlockState()); cells.put(at.below(), Blocks.STONE.defaultBlockState());
            for (Direction side : Direction.Plane.HORIZONTAL) if (!SOURCES.contains(at.relative(side))) cells.put(at.relative(side), Blocks.GLASS.defaultBlockState());
        }
        JsonArray blocks = new JsonArray(); cells.forEach((at,state) -> {
            JsonObject row = new JsonObject(); JsonArray offset = new JsonArray(); offset.add(at.getX()); offset.add(at.getY()); offset.add(at.getZ());
            row.add("offset",offset); row.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()); blocks.add(row);
        }); JsonObject result = new JsonObject(); result.add("blocks", blocks); return result;
    }
    private static void rejects(Runnable operation) { try { operation.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("不支持的流体状态必须拒绝"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
