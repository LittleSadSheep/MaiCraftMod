// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.core.task.build.BuildSiteInvestigationTaskRecord;
import org.maiwithu.maicraft.intent.Goal;

/** Hidden bridge from a valid no-loaded-site plan to bounded first-person investigation. */
public final class SemanticBuildSiteInvestigationTool implements MaiCraftTool {
    @Override
    public String name() {
        return BuildSiteInvestigationTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Internal only: investigate a safe semantic build site through bounded first-person "
                + "movement, freeze the first strictly verified plan, then run normal supply/build.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("type", "object");
        goal.put("description", "Planner-retained semantic build goal; never generated cells or waypoints.");
        goal.put("additionalProperties", true);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of("goal", goal));
        root.put("required", List.of("goal"));
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        if (args == null || !args.has("goal") || !args.get("goal").isJsonObject()) {
            throw new IllegalArgumentException("internal build-site investigation needs goal");
        }
        Goal goal = Goal.fromJson(args.getAsJsonObject("goal"));
        var record = new BuildSiteInvestigationTaskRecord(
                toolCallId,
                ctx(toolCallId, player).deadline(BuildSiteInvestigationTaskRecord.MAX_TOTAL_TICKS),
                goal);
        setTask(player, record, args, reply);
    }
}
