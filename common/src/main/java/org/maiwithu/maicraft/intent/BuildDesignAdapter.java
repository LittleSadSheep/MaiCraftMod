// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.preview.PreviewController;
import org.maiwithu.maicraft.client.preview.PreviewSession;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.task.TaskResult;

/** 只在世界里展示房屋蓝图：复用建造规划，但不取材料、不走路，也不施工。 */
final class BuildDesignAdapter {
    static final String ABILITY = "maicraft:design_build";
    private BuildDesignAdapter() {}

    static IntentAction design(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        return design(goal, player, runtime, PreviewController::showDesign);
    }

    static IntentAction design(Goal goal, LocalPlayer player, IntentRuntime runtime,
                                Predicate<PreviewSession> publish) {
        // 预览规划如果无法完成，直接说明失败；不进入普通任务的“先出去找地”或等待恢复流程。
        IntentAction compiled = SemanticBuildPlanner.previewPlan(goal, player, runtime);
        if (compiled instanceof IntentAction.Decision decision) return new IntentAction.Report(TaskResult.fail(
                decision.snapshot().question(), Map.of("failure_code", "preview_design_unavailable",
                        "preview_created", false, "construction_started", false)), null);
        if (!(compiled instanceof IntentAction.Tool tool)) return compiled;
        if (!"build".equals(tool.toolName())) throw new IllegalStateException("preview compiler returned body work");
        var args = tool.arguments();
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        BuildTool.resolvedTargets(args.getAsJsonArray("ops"))
                .forEach(target -> cells.put(target.pos(), target.desiredState()));
        // 展开到最终每一格的状态，用于显示蓝图；这里没有把这些格子提交给施工任务。
        var session = PreviewSession.design("design-" + UUID.randomUUID(),
                player.level().dimension().location().toString(), goal.outcome(), cells);
        if (!publish.test(session)) return new IntentAction.Report(TaskResult.fail(
                "The preview cannot replace an active construction review or belong to a different world.",
                Map.of("failure_code", "preview_display_unavailable", "preview_created", false,
                        "construction_started", false)), null);
        return new IntentAction.Report(TaskResult.ok(
                "Read-only blueprint displayed. No movement, supply or construction was started. "
                        + "Confirm cannot start this design; submit maicraft:build separately to construct.",
                Map.of("preview_created", true, "preview_id", session.owner(), "preview_mode", "design_only",
                        "cell_count", cells.size(), "construction_started", false)), null);
    }
}
