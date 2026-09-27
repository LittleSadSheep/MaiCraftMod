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
import org.maiwithu.maicraft.task.TaskState;

/** 同一份 AIR 蓝图用于修改既有机器时保留精确旧状态，不能被普通选址清障拒绝或平移到另一片空气中。 */
public final class MachineModificationClearanceTest {
    private static final BlockPos AT = new BlockPos(5, 1, 5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        declaredModificationSurvivesBatching(); blockEntitiesAndObservationScopeRemainExplicit();
        System.out.println("MachineModificationClearanceTest: passed");
    }
    private static void declaredModificationSurvivesBatching() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.set(AT, Blocks.BRICKS.defaultBlockState());
            var plan = plan(false);
            check(survey(h, plan.blockTask("ordinary", 1000, true)).blocked(), "ordinary construction retains the clearance whitelist");
            plan.bindObservedModification(h.level, AT, 4);
            var source = plan.blockTask("modify", 1000, true); source.previewManaged(true);
            check(!survey(h, source).blocked() && source.observedMachineEdit(AT, h.level.getBlockState(AT)),
                    "explicit surveyed machine demolition passes the unrelated site-clearance whitelist");
            var batch = new BuildTaskRecord("modify-batch", 1000, source.targets, true);
            source.copyExecutionContextTo(batch);
            check(batch.fixedMachineModification() && !survey(h, batch).blocked(), "supply batches retain the exact original modification scope");
            check(!batch.observedMachineEdit(AT.east(), Blocks.BRICKS.defaultBlockState()), "an undeclared neighbor does not inherit demolition scope");
            var task = new FirstPersonBuildCompanionTask(h.player, batch); task.start(h.player);
            check(invoke(task, "preflightTick") == TaskState.RUNNING, "real build preflight accepts the requested modification");
            invoke(task, "excavationTick");
            check(permitted(task, Blocks.BRICKS.defaultBlockState()), "the actual pre-break gate accepts the observed old block");
            // 开工后换成另一台机器时，原位重新观察；不能平移拆除请求后报告旧轴已拆掉。
            h.set(AT, Blocks.CHEST.defaultBlockState());
            check(!permitted(task, h.level.getBlockState(AT)), "a later unobserved block cannot use the old state's exception");
            var changed = survey(h, batch);
            check(changed.blocked() && changed.report().get("suggested_offsets").equals(List.of())
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
            var denied = survey(h, protectedEntity.blockTask("no-entity-permission", 1000, true));
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
            check(survey(h, outside.blockTask("outside-observation", 1000, true)).blocked(),
                    "a target outside the supplied observation cannot inherit a machine-edit exception");
        }
    }
    private static MachineConstructionPlan plan(boolean entities) {
        var document = JsonParser.parseString("{\"schema_version\":1,\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:air\"}]}").getAsJsonObject();
        return MachineConstructionPlan.compile(AT, MachineBlueprintDocument.compile(document, MachineConstructionPlan.registry()), true, entities);
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
