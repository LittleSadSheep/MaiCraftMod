// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.blueprint.BuildProjectStore;
import org.maiwithu.maicraft.task.TaskResult;

/** Continue one saved physical design; a semantic target never relocates an existing project. */
final class BuildProjectAdapter {
    private BuildProjectAdapter() {}

    static IntentAction plan(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        String id = goal.parameters().get("project_id").getAsString();
        try {
            var arguments = BuildProjectStore.current().load(id, player.level().dimension().location().toString());
            return new IntentAction.Tool("build", arguments.toString());
        } catch (RuntimeException unavailable) {
            return new IntentAction.Report(TaskResult.fail(unavailable.getMessage(),
                    Map.of("failure_code", "build_project_unavailable", "project_id", id,
                            "construction_started", false)), null);
        }
    }
}
