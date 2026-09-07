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

/** Walk out through the reserved entrance, then close it using the ordinary native builder/supply path. */
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

    static List<BlockPos> sealingProtection(List<BlockPos> allCells, MachineSealingTaskRecord.Seal seal) {
        return allCells.stream().filter(pos -> !seal.isOpening(pos)
                && !pos.equals(seal.outside()) && !pos.equals(seal.outside().above())).toList();
    }

    private boolean matches(MachineSealingTaskRecord.Seal seal) {
        return seal.targets().stream().allMatch(target -> world.isLoaded(target.pos())
                && target.matches(world.getBlockState(target.pos())));
    }

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
