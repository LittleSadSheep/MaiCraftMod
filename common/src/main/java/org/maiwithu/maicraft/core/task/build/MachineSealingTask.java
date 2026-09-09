// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.supply.SemanticBuildSupplyTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 机器内部施工结束后，从预留洞口走到外面，再用普通建筑任务封住洞口。
 * 实际封口期间持续要求人在外侧，其他机器格子受保护，避免封口取材或走路时拆掉已完成部分。
 */
final class MachineSealingTask extends AbstractCompanionTask<MachineSealingTaskRecord> {
    private final Level world;
    private Task child;
    private TaskRecord childRecord;
    private int sealed;
    private Map<String, Object> childEvidence = Map.of();
    private String failureCode;

    MachineSealingTask(LocalPlayer player, MachineSealingTaskRecord record) {
        super(player, record); world = player.level();
    }

    @Override protected TaskState onTick() {
        if (player.level() != world) return failure("seal_world_changed", "The construction world changed before closure.");
        if (child != null) return tickClosure();
        if (sealed >= r.seals.size()) return TaskState.SUCCESS;
        var seal = r.seals.get(sealed);
        // 已封好的洞口直接计为完成；只有需要新放方块的洞口才要求这次先走到外面。
        if (matches(seal)) { sealed++; return TaskState.RUNNING; }
        if (!PlayerNav.playerFeet(player).equals(seal.outside()) || !seal.onOutside(player.position())) {
            if (nav == null) nav = PlayerNav.toGoal(player, () -> NavGoal.exact(seal.outside()), 1,
                    () -> PlayerNav.playerFeet(player).equals(seal.outside()) && seal.onOutside(player.position()),
                    PlayerNav.ContextProvider.DEFAULT);
            var state = nav.tick();
            if (state == PlayerNav.Status.FAILED) return failure("seal_exit_unreachable",
                    "The reserved outside stance cannot be reached; the opening was left unsealed.");
            if (state == PlayerNav.Status.ARRIVED) stopNav();
            return TaskState.RUNNING;
        }
        stopNav();
        // 生存模式封口仍要备料，创造模式直接建；预览交给父流程管理，不为每个洞口重复展示。
        boolean consume = !WorkProfile.of(player).freeMaterials();
        BuildTaskRecord build = new BuildTaskRecord(r.getToolCallId() + "-closure-" + sealed,
                r.getDeadlineGameTime(), seal.targets(), false, consume, consume);
        build.previewManaged(true);
        List<BlockPos> protectedCells = sealingProtection(r.fullPlanCells, seal);
        build.executionGuards(protectedCells,
                actor -> actor.level() == world && seal.onOutside(actor.position()),
                (actor, pos) -> actor.level() == world && seal.onOutside(actor.position()) && world.isLoaded(pos),
                (actor, pos) -> {});
        build.materialSupplyProtection(r.fullPlanCells);
        childRecord = consume ? new SemanticBuildSupplyTaskRecord(r.getToolCallId() + "-closure-supply-" + sealed,
                r.getDeadlineGameTime(), build, r.materialPolicy, List.of(), false, r.protectedLabels, false) : build;
        child = TaskFactory.create(player, childRecord);
        return TaskState.RUNNING;
    }

    // 保留机器全部目标，排除本次要封的两格以及外侧人的脚和头，给封口留下操作空间。
    static List<BlockPos> sealingProtection(List<BlockPos> allCells, MachineSealingTaskRecord.Seal seal) {
        return allCells.stream().filter(pos -> !seal.isOpening(pos)
                && !pos.equals(seal.outside()) && !pos.equals(seal.outside().above())).toList();
    }

    private boolean matches(MachineSealingTaskRecord.Seal seal) {
        return seal.targets().stream().allMatch(target -> world.isLoaded(target.pos())
                && target.matches(world.getBlockState(target.pos())));
    }

    // 等待建筑或供料子任务结束，再同时核对结果、洞口方块和外侧身体位置；只听到子任务成功还不够。
    private TaskState tickClosure() {
        TaskState state = world.getGameTime() >= childRecord.getDeadlineGameTime() ? TaskState.TIMEOUT : runChild(child);
        r.extendDeadlineTo(childRecord.getDeadlineGameTime());
        if (state == null) return TaskState.RUNNING;
        if (state == TaskState.TIMEOUT) child.stop(player, StopReason.REPLACED);
        TaskResult result = child.result(state);
        childEvidence = result == null || result.data() == null ? Map.of() : result.data();
        child = null; childRecord = null;
        if (result == null || !result.success() || !matches(r.seals.get(sealed)))
            return failure("seal_not_confirmed", result == null ? "Closure produced no native receipt." : result.message());
        if (!r.seals.get(sealed).onOutside(player.position()))
            return failure("seal_body_not_outside", "Closure finished without a verified exterior body position.");
        sealed++;
        return TaskState.RUNNING;
    }

    private TaskState failure(String code, String message) { failureCode = code; fail(message, FailureType.NO_PATH); return TaskState.FAILED; }
    // 本任务结束时，仍在运行的子任务也要停止并结算为取消，随后执行公共导航清理。
    @Override protected void cleanup() {
        if (child != null) { child.stop(player, StopReason.REPLACED); child.result(TaskState.CANCELLED); child = null; }
        super.cleanup();
    }
    @Override protected String successMessage() { return "All machine closure cells verified; newly placed seals were constructed from outside."; }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sealed_entrances", sealed); data.put("expected_entrances", r.seals.size());
        data.put("closures_verified", sealed == r.seals.size());
        if (!childEvidence.isEmpty()) data.put("last_native_closure", childEvidence);
        if (failureCode != null) data.put("failure_code", failureCode);
        return data;
    }
}
