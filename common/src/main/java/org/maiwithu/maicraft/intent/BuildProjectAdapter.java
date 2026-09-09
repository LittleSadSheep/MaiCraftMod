// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.blueprint.BuildProjectStore;
import org.maiwithu.maicraft.task.TaskResult;

/**
 * 按 project_id 读取固定施工单并交给建造工具；取消旧任务不删除它保存的施工单。
 */
final class BuildProjectAdapter {
    private BuildProjectAdapter() {}

    static IntentAction plan(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        String id = goal.parameters().get("project_id").getAsString();
        try {
            // 当前直接使用磁盘中保存的参数；新目标里的地点和 protected_labels 在此没有合并或核对。
            var arguments = BuildProjectStore.current().load(id, player.level().dimension().location().toString());
            return new IntentAction.Tool("build", arguments.toString());
        // 项目不存在、世界或维度不符、文件损坏时只报告无法续建，不用当前地点生成一个新工程冒充恢复。
        } catch (RuntimeException unavailable) {
            return new IntentAction.Report(TaskResult.fail(unavailable.getMessage(),
                    Map.of("failure_code", "build_project_unavailable", "project_id", id,
                            "construction_started", false)), null);
        }
    }
}
