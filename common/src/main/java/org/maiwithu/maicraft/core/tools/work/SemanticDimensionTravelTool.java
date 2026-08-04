// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.task.dimension.DimensionTravelTaskRecord;

/** Hidden executor for semantic dimension travel; portal cells remain a Mod concern. */
public final class SemanticDimensionTravelTool implements MaiCraftTool {
    private static final Gson GSON = new Gson();
    private record Args(
            String destination_dimension,
            Integer max_search_radius,
            Boolean may_alter_terrain) {}

    @Override
    public String name() {
        return DimensionTravelTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Reach a semantic destination dimension through a portal observed by the client. "
                + "The Mod finds the portal block, walks into it, authorises one same-connection "
                + "LocalPlayer handoff and verifies the new dimension. It never asks the model for "
                + "portal coordinates and never treats a disconnect as a successful crossing.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("destination_dimension", "Namespaced destination dimension id.")
                .optionalInteger(
                        "max_search_radius",
                        "Loaded-world portal evidence radius (default 128).",
                        DimensionTravelTaskRecord.MIN_RADIUS,
                        DimensionTravelTaskRecord.MAX_RADIUS)
                .optionalBool(
                        "may_alter_terrain",
                        "Explicit permission for the route to dig, bridge or pillar; default false.")
                .build();
    }

    @Override
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        if (parsed == null || parsed.destination_dimension() == null) {
            throw new IllegalArgumentException("destination_dimension is required");
        }
        int radius = parsed.max_search_radius() == null ? 128 : parsed.max_search_radius();
        boolean alter = Boolean.TRUE.equals(parsed.may_alter_terrain());
        var record = new DimensionTravelTaskRecord(
                toolCallId,
                player.level().getGameTime() + 20L * 60L * 10L,
                parsed.destination_dimension(),
                radius,
                alter);
        setTask(player, record, args, reply);
    }
}
