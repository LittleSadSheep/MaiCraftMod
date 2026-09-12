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
import org.maiwithu.maicraft.core.task.dimension.PortalPreparationPolicy;

/** Hidden executor for semantic dimension travel; portal cells remain a Mod concern. */
public final class SemanticDimensionTravelTool implements MaiCraftTool {
    private static final Gson GSON = new Gson();
    private static final long INITIAL_LIVENESS_LEASE_TICKS = 10L * 60L * 20L;
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
                + "portal coordinates and never treats a disconnect as a successful crossing. With prepare_portal, "
                + "it can obtain materials, build or repair a Nether frame, or fill a real End frame, then verify activation.";
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
                .optionalBool("prepare_portal", "Prepare an absent active portal; Nether construction also needs terrain permission.")
                .optionalBool("allow_rare_consumables", "Permit stronghold eye throws and End frame eye insertion.")
                .optionalBool("allow_combat", "Permit hostile hunting for portal materials.")
                .optionalInteger("max_search_distance", "Physical stronghold search limit when preparation is enabled.", 128, 4096)
                .optionalStringArray("allowed_sources", "Permitted material acquisition sources.")
                .optionalEnum("material_policy", "Material supply policy.", "ordinary", "storage_available", "inventory_only")
                .optionalStringArray("protected_labels", "Remembered places that preparation must preserve.")
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
                player.level().getGameTime() + INITIAL_LIVENESS_LEASE_TICKS,
                parsed.destination_dimension(),
                radius,
                alter, PortalPreparationPolicy.parse(args));
        setTask(player, record, args, reply);
    }
}
