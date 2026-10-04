// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlan;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.task.TaskState;

/** 已授权机器蓝图在声明格内拆换当前部件，不以旧观察或重复许可门控；明确保留规则与原生限制仍生效。 */
public final class MachineModificationClearanceTest {
    private static final BlockPos AT = new BlockPos(5, 1, 5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        declaredModificationSurvivesBatching(); blockEntitiesAndObservationScopeRemainExplicit(); automaticModificationOwnsItsDeclaredScope();
        attachmentReplacementKeepsDistinctSupplyProtection();
        System.out.println("MachineModificationClearanceTest: passed");
    }
    private static void declaredModificationSurvivesBatching() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.set(AT, Blocks.BRICKS.defaultBlockState());
            var plan = plan(false);
            check(!survey(h, plan.blockTask("ordinary", 1000, true)).blocked(), "声明机器蓝图默认可清理目标格中的旧部件");
            plan.bindObservedModification(h.level, AT, 4);
            var source = plan.blockTask("modify", 1000, true); source.previewManaged(true);
            check(!survey(h, source).blocked() && source.observedMachineEdit(AT, h.level.getBlockState(AT)),
                    "explicit surveyed machine demolition passes the unrelated site-clearance whitelist");
            var batch = new BuildTaskRecord("modify-batch", 1000, source.targets, source.replaceMode, true, true, false,
                    Map.of(), List.of(), source.replaceBlockEntities);
            source.copyExecutionContextTo(batch);
            check(batch.fixedMachineModification() && !survey(h, batch).blocked(), "supply batches retain the exact original modification scope");
            check(!batch.observedMachineEdit(AT.east(), Blocks.BRICKS.defaultBlockState()), "an undeclared neighbor does not inherit demolition scope");
            var task = new FirstPersonBuildCompanionTask(h.player, batch); task.start(h.player);
            check(invoke(task, "preflightTick") == TaskState.RUNNING, "real build preflight accepts the requested modification");
            invoke(task, "excavationTick");
            check(permitted(task, Blocks.BRICKS.defaultBlockState()), "the actual pre-break gate accepts the observed old block");
            // 开工后同一声明格换成其他旧部件，仍读取当前状态拆换；不能要求模型只为更新快照重发蓝图。
            h.set(AT, Blocks.GOLD_BLOCK.defaultBlockState());
            check(permitted(task, h.level.getBlockState(AT)), "声明格的拆换权限不依赖旧方块快照");
            var changed = survey(h, batch);
            check(!changed.blocked() && changed.report().get("suggested_offsets").equals(List.of())
                    && changed.report().get("relocation_scope").equals("none_fixed_machine_modification"),
                    "fixed machine edits never propose relocating their AIR targets");
            check(batch.broken() == 0 && h.blockUses() == 0, "permission checks alone do not fabricate native demolition");
            task.result(TaskState.CANCELLED);
        }
    }
    private static void blockEntitiesAndObservationScopeRemainExplicit() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.set(AT, Blocks.BARREL.defaultBlockState());
            var protectedEntity = plan(false); protectedEntity.bindObservedModification(h.level, AT, 4);
            // 底层调用明确保留方块实体时仍遵守该约束；机器蓝图默认拆换则不要求追加重复许可字段。
            var explicitPreservation = new BuildTaskRecord("preserve-entities", 1000, protectedEntity.blocks(), true);
            var denied = survey(h, explicitPreservation);
            check(denied.blocked(),
                    "replace_existing alone cannot authorize demolition of a block entity");
            // 实机轴替换案例缺的是方块实体选项，回执必须返回精确参数名，不能笼统叫模型换场地。
            var obstacle = (Map<?, ?>) ((List<?>) denied.report().get("obstacles")).getFirst();
            check(obstacle.get("required_permissions").equals(List.of("replace_block_entities"))
                    && obstacle.get("has_block_entity").equals(true), "the exact missing entity-replacement option is visible");
            var noReplace = new BuildTaskRecord("no-replacement-options", 1000, protectedEntity.blocks(), false);
            noReplace.machineModification(Map.of(AT, h.level.getBlockState(AT)));
            var both = (Map<?, ?>) ((List<?>) survey(h, noReplace).report().get("obstacles")).getFirst();
            check(both.get("required_permissions").equals(List.of("replace_existing", "replace_block_entities")),
                    "all missing options are reported together rather than producing two rounds of failed modification");
            var allowedEntity = plan(true); allowedEntity.bindObservedModification(h.level, AT, 4);
            check(!survey(h, allowedEntity.blockTask("entity-permission", 1000, true)).blocked(),
                    "the separate explicit block-entity option is honored at declared targets");
            var outside = plan(true); outside.bindObservedModification(h.level, AT.east(2), 1);
            check(!survey(h, outside.blockTask("outside-observation", 1000, true)).blocked(),
                    "声明格由执行器读取当前状态，不受旧勘测范围限制");
            h.set(AT, Blocks.BEDROCK.defaultBlockState());
            check(survey(h, outside.blockTask("unbreakable", 1000, true)).blocked(), "已授权也不能绕过原生不可破坏条件");
        }
    }
    private static MachineConstructionPlan plan(boolean entities) {
        var document = JsonParser.parseString("{\"schema_version\":1,\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:air\"}]}").getAsJsonObject();
        return MachineConstructionPlan.compile(AT, MachineBlueprintDocument.compile(document, MachineConstructionPlan.registry()), true, entities);
    }
    private static void attachmentReplacementKeepsDistinctSupplyProtection() throws Exception {
        try(var h=new InteractionWorldTestHarness()) {
            // 固定翼反向传动把已有木板换为墙上红石火把；支承和其他机体格仍受保护，不能把火把自身的格子也锁死。
            h.set(AT,Blocks.OAK_PLANKS.defaultBlockState());h.set(AT.east(),Blocks.STONE.defaultBlockState());
            var document=JsonParser.parseString("""
                    {"schema_version":1,"blocks":[
                      {"offset":[0,0,0],"block_id":"minecraft:redstone_wall_torch","properties":{"facing":"west"}},
                      {"offset":[1,0,0],"block_id":"minecraft:stone"}]}
                    """).getAsJsonObject();
            var plan=MachineConstructionPlan.compile(AT,MachineBlueprintDocument.compile(document,MachineConstructionPlan.registry()),true,true);
            plan.bindAutomaticModification(h.level);
            var attachment=plan.attachmentTask(0,"wall-attachment",1000,true);
            check(!attachment.materialSupplyProtection().contains(AT)&&attachment.materialSupplyProtection().contains(AT.east()),"本层拆换格与其他机体保护未分开");
            check(!survey(h,attachment).blocked(),"已声明的墙火把替换被供料保护误挡");
            var protectedSurvey=NavigationSafetyContext.withProtectedArea(List.of(AT),List.of(),()->survey(h,attachment));
            check(protectedSurvey.blocked(),"明确保留规则仍应阻止替换，不能随附件拆换范围一起豁免");
            check(h.blockUses()==0,"前置权限检查不能伪造原生放置");
        }
    }
    private static void automaticModificationOwnsItsDeclaredScope() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var plan = plan(true); plan.bindAutomaticModification(h.level);
            // 目标块在进入施工区后才可读，也由 Mod 自己绑定；不要求模型再拿一个只用于重复授权的观察编号。
            h.set(AT, Blocks.BARREL.defaultBlockState());
            var task = plan.blockTask("automatic-edit", 1000, true);
            check(!survey(h, task).blocked() && task.observedMachineEdit(AT, h.level.getBlockState(AT)),
                    "authorized declared cells are re-read internally even when no old snapshot contained the current block");
            check(!task.observedMachineEdit(AT.east(), Blocks.BARREL.defaultBlockState()), "omitted neighbor cells remain outside the edit scope");
            var batch = new BuildTaskRecord("automatic-edit-batch", 1000, task.targets, ReplaceMode.REPLACE_EMPTY, true, true, true,
                    Map.of(), List.of(), true); task.copyExecutionContextTo(batch);
            check(!survey(h, batch).blocked(), "material batches preserve the internally refreshed edit scope");
        }
    }
    private static BuildClearanceSurvey survey(InteractionWorldTestHarness h, BuildTaskRecord record) {
        var survey = BuildClearanceSurvey.forPlan(h.player, record);
        for (int i = 0; i < 4096; i++) if (survey.advance(128)) return survey;
        throw new AssertionError("clearance observation exceeded its bounded work");
    }
    private static Object invoke(Object task, String method) throws Exception {
        var call = task.getClass().getDeclaredMethod(method); call.setAccessible(true); return call.invoke(task);
    }
    private static boolean permitted(Object task, BlockState state) throws Exception {
        var call = task.getClass().getDeclaredMethod("clearingPermitted", BlockState.class); call.setAccessible(true); return (Boolean) call.invoke(task, state);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
