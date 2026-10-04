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
import org.maiwithu.maicraft.core.blueprint.BuildProjectTargets;

/** 展示作者模型或冻结施工单的只读预览；不生成房屋、不取材料、不走路，也不施工。 */
final class BuildDesignAdapter {
    static final String ABILITY = "maicraft:design_build";
    private BuildDesignAdapter() {}

    static IntentAction design(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        return design(goal, player, runtime, PreviewController::showDesign);
    }

    static IntentAction design(Goal goal, LocalPlayer player, IntentRuntime runtime,
                                Predicate<PreviewSession> publish) {
        // 新设计必须提供模型或蓝图；只有单独引用 project_id 时才预览已经保存的施工要求。
        if (!goal.parameters().has("project_id") || BuildingSceneContract.supports(goal))
            return BuildingSceneAdapter.adapt(goal, player, runtime, publish);
        IntentAction compiled = BuildProjectAdapter.plan(goal, player, runtime);
        if (!(compiled instanceof IntentAction.Tool tool)) return compiled;
        if (!"build".equals(tool.toolName())) throw new IllegalStateException("preview compiler returned body work");
        var args = tool.arguments();
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        (args.has("project_targets")
                ? BuildProjectTargets.decode(args.getAsJsonArray("project_targets"))
                : BuildTool.resolvedTargets(args.getAsJsonArray("ops")))
                .forEach(target -> cells.put(target.pos(), target.desiredState()));
        // 冻结项目只展开到预览状态，不创建施工子任务；此分支没有场景预览的逐格已加载/高度检查。
        // 发布成功只表示预览会话已建立，不能据此推断远处目标已加载、可达或已经建好。
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
