// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.create.CreateBeltGeometry;
import org.maiwithu.maicraft.core.integration.create.CreateBeltInstallTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineNativeInstallation;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticResultView;

/** 用明确的模拟原生动作检查父级装配顺序和最终状态；不把夹具世界变化当成实际 Create 验收。 */
public final class MachineNativeInstallationTest {
    private static final BlockPos AT = new BlockPos(3, 1, 3), OTHER = new BlockPos(4, 1, 3);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.set(AT, Blocks.STONE.defaultBlockState()); h.set(OTHER, Blocks.STONE.defaultBlockState());
            int[] starts = {0};
            MachineNativeInstallation installation = new MachineNativeInstallation() {
                public Map<BlockPos, BlockState> preparation() { return Map.of(AT, Blocks.STONE.defaultBlockState()); }
                public Map<BlockPos, BlockState> targets() { return Map.of(AT, Blocks.GOLD_BLOCK.defaultBlockState()); }
                public Map<ResourceLocation, Integer> materials() { return Map.of(); }
                public boolean matches(Level world) { return world.getBlockState(AT).is(Blocks.GOLD_BLOCK); }
                public TaskRecord task(String id, long deadline, List<BlockPos> footprint, List<String> labels) { starts[0]++; return new ProbeRecord(id, deadline); }
            };
            TaskFactory.register(ProbeRecord.class, (player, record) -> new Task() {
                public TaskState tick(LocalPlayer actor) { h.set(AT, Blocks.GOLD_BLOCK.defaultBlockState()); return TaskState.SUCCESS; }
                public void stop(LocalPlayer actor, StopReason reason) {}
                public TaskResult result(TaskState state) { return TaskResult.ok("simulated native installation", Map.of("native_link_verified", true)); }
                public String name() { return "native installation fixture"; }
            });
            var constructor = MachineConstructionPlan.class.getDeclaredConstructor(BlockPos.class, List.class, List.class, List.class,
                    JsonObject.class, boolean.class, boolean.class, List.class, List.class, JsonObject.class); constructor.setAccessible(true);
            var targets = List.of(new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, AT, "prepared", null, null, null),
                    new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, OTHER, "ordinary", null, null, null));
            var plan = constructor.newInstance(BlockPos.ZERO, targets, List.of(), List.of(), new JsonObject(), false, false, List.of(installation), List.of(), new JsonObject());
            check(plan.preview().get(AT).is(Blocks.GOLD_BLOCK) && plan.blocks().getFirst().desiredState().is(Blocks.STONE), "preview shows final structure while bulk building retains preparation targets");
            var record = new MachineBuildTaskRecord("native-parent", 1000, plan, "minecraft:overworld", MaterialPolicy.INVENTORY_ONLY, List.of());
            var task = new MachineBuildTask(h.player, record); field("blocksStarted").setBoolean(task, true);
            invoke(task, "buildBlocks"); check(field("phase").get(task).toString().equals("INSTALLATIONS"), "native work follows ordinary preparation");
            invoke(task, "installNative");
            for (int i = 0; i < 3 && field("child").get(task) != null; i++) invoke(task, "tickChild");
            check(starts[0] == 1 && field("installationIndex").getInt(task) == 1 && installation.matches(h.level), "one native receipt advances the installation exactly once");
            check(invoke(task, "verify") == TaskState.RUNNING && field("verifyInstallationIndex").getInt(task) == 1,
                    "verification accepts final native state instead of rejecting the missing preparation block");
            var survey = new MachineBuildSurvey(plan); survey.tick(h.level);
            check(survey.completedInstallations().contains(AT), "completed native structure is recognized on resume");
            var remaining = plan.blockTask("resume", 1000, false, List.of(), Set.of(), survey.completedInstallations());
            check(remaining.targets.stream().noneMatch(target -> target.pos().equals(AT)), "resume cannot rebuild a finished native structure as its preparation block");
            // 原语需要的新路径若被其他方块占用，必须停在调查阶段，不把隐含净空升级成拆除许可。
            BlockPos obstacle = AT.south(); h.set(obstacle, Blocks.STONE.defaultBlockState());
            MachineNativeInstallation blocked = new MachineNativeInstallation() {
                public Map<BlockPos, BlockState> preparation() { return Map.of(obstacle, Blocks.AIR.defaultBlockState()); }
                public Map<BlockPos, BlockState> targets() { return Map.of(obstacle, Blocks.GOLD_BLOCK.defaultBlockState()); }
                public Map<ResourceLocation, Integer> materials() { return Map.of(); }
                public boolean matches(Level world) { return false; }
                public TaskRecord task(String id, long deadline, List<BlockPos> footprint, List<String> labels) { throw new AssertionError("blocked installation must not start"); }
            };
            var blockedPlan = constructor.newInstance(new BlockPos(2, 1, 2), targets, List.of(), List.of(), new JsonObject(), true, false, List.of(blocked), List.of(), new JsonObject());
            check(new MachineBuildSurvey(blockedPlan).tick(h.level).failure().contains("undeclared obstacles"), "even replacement permission does not invent extra demolition targets");
            check(blockedPlan.blockTask("blocked", 1000, false).targets.stream().noneMatch(target -> target.pos().equals(obstacle)), "implicit native path remains absent from ordinary demolition tasks");
            // 实际父任务的终态、对外净化和注意摘要都必须能定位阻塞，不能只留下笼统的空路径提示。
            var surveyTask = new MachineBuildTask(h.player, new MachineBuildTaskRecord("survey-receipt", 1000, blockedPlan, "minecraft:overworld", MaterialPolicy.INVENTORY_ONLY, List.of()));
            check(invoke(surveyTask, "surveyParts") == TaskState.FAILED, "occupied native path stops before construction");
            var receipt = SemanticResultView.result(surveyTask.result(TaskState.FAILED));
            var clearance = (Map<?, ?>) receipt.data().get("clearance_report");
            check(clearance.get("observed_block_id").equals("minecraft:stone") && clearance.get("blueprint_offset").equals(List.of(1, 0, 2)), "block identity and local offset remain actionable");
            check(clearance.get("failure_position").equals(Map.of("x", 3, "y", 1, "z", 4)) && receipt.data().get("failure_type").equals("terrain_blocked"), "public receipt retains observed position and a concrete failure type");
            var compact = IntentRuntime.class.getDeclaredMethod("compactAttentionResult", JsonObject.class); compact.setAccessible(true);
            var notice = ((JsonObject) compact.invoke(null, JsonParser.parseString(receipt.toJson()).getAsJsonObject())).getAsJsonObject("data").getAsJsonObject("clearance_report");
            check(notice.getAsJsonObject("failure_position").get("z").getAsInt() == 4 && notice.getAsJsonArray("blueprint_offset").size() == 3, "attention preserves exact observed clearance facts");
            check(h.level.getBlockState(obstacle).is(Blocks.STONE) && !((Boolean) clearance.get("world_modified")), "survey diagnostics do not clear undeclared obstacles");
            var belt = new CreateBeltInstallTaskRecord("unsupported-real-belt", 1000,
                    CreateBeltGeometry.between(AT, AT.east(2), Direction.Axis.Z, 20), Set.of(AT, AT.east(2)), List.of(AT), List.of());
            Task actual = TaskFactory.create(h.player, belt); actual.start(h.player);
            check(actual.tick(h.player) == TaskState.FAILED, "missing native API or invalid shafts reject the real belt actor before interaction");
            actual.result(TaskState.FAILED);
            check(h.blockUses() == 0 && h.itemUses() == 0, "rejected real installation performs no native click");
            // 原生供料因容量停下时，经过真实机器父任务结算后仍须保留 no_space 与容量缺口。
            var capacity = Map.of("empty_main_slots", 0, "minimum_additional_slots", 1);
            TaskFactory.register(ProbeRecord.class, (player, probe) -> new Task() {
                public TaskState tick(LocalPlayer actor) { return TaskState.FAILED; }
                public void stop(LocalPlayer actor, StopReason reason) {}
                public String name() { return "模拟原生容量不足"; }
                public TaskResult result(TaskState state) { return TaskResult.fail("没有取料空间", Map.of(
                        "failure_type", "no_space", "failure_code", "inventory_capacity_blocked", "inventory_capacity", capacity)); }
            });
            var failed = new MachineBuildTask(h.player, record);
            var childRecord = new ProbeRecord("capacity-child", 1000);
            field("childRecord").set(failed, childRecord); field("child").set(failed, TaskFactory.create(h.player, childRecord));
            check(invoke(failed, "tickChild") == TaskState.FAILED, "容量失败由父任务停止，不能派出另一份取料");
            var failure = failed.result(TaskState.FAILED).data();
            check(failure.get("failure_type").equals("no_space") && failure.get("cause_code").equals("inventory_capacity_blocked")
                    && failure.get("inventory_capacity").equals(capacity), "公开失败保留具体原因，不包装成不明缺料");
        }
    }
    private static final class ProbeRecord extends TaskRecord { ProbeRecord(String id, long deadline) { super("fixture_native", id, deadline); } }
    private static Field field(String name) throws Exception { var field = MachineBuildTask.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static Object invoke(MachineBuildTask task, String name) throws Exception { var method = MachineBuildTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
