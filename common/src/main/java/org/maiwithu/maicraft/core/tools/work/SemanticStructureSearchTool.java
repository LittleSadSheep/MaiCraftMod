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
import org.maiwithu.maicraft.core.task.structure.PhysicalStructureSearchTaskRecord;

/**
 * Hidden semantic executor for physical structure discovery.
 *
 * <p>The public MCP surface never exposes this tool directly. Its caller supplies a structure
 * intent; routes, eye throws, frontier waypoints and evidence checks remain inside the Mod.</p>
 */
public final class SemanticStructureSearchTool implements MaiCraftTool {
    private static final Gson GSON = new Gson();
    private static final long INITIAL_LIVENESS_LEASE_TICKS = 20L * 60L * 20L;

    private record Args(
            String structure_id,
            Integer max_distance,
            Boolean may_alter_terrain,
            Boolean reach_structure,
            Boolean allow_rare_consumables) {}

    @Override
    public String name() {
        return PhysicalStructureSearchTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Discover a structure through physical first-person play and loaded client facts. "
                + "Strongholds use real ender-eye throws and observed trajectories; other registered "
                + "structures use data-driven visible block-evidence profiles plus bounded frontier "
                + "exploration. No seed/locate authority, model-provided waypoint, entity id, GUI "
                + "slot or per-block route is accepted.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("structure_id", "Namespaced structure id, for example minecraft:stronghold.")
                .optionalInteger(
                        "max_distance",
                        "Maximum horizontal first-person search radius from start (default 4096).",
                        PhysicalStructureSearchTaskRecord.MIN_DISTANCE,
                        PhysicalStructureSearchTaskRecord.MAX_DISTANCE)
                .optionalBool(
                        "may_alter_terrain",
                        "Explicit permission for internal routes to dig, bridge or pillar; default false.")
                .optionalBool(
                        "reach_structure",
                        "If true, physically reach observed evidence and verify it again; default true.")
                .optionalBool(
                        "allow_rare_consumables",
                        "Explicitly allow real ender-eye throws for stronghold guidance; default false.")
                .build();
    }

    @Override
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        if (parsed == null || parsed.structure_id() == null) {
            throw new IllegalArgumentException("structure_id is required");
        }
        int distance = parsed.max_distance() == null
                ? PhysicalStructureSearchTaskRecord.DEFAULT_DISTANCE
                : parsed.max_distance();
        boolean alter = Boolean.TRUE.equals(parsed.may_alter_terrain());
        boolean reach = parsed.reach_structure() == null
                || Boolean.TRUE.equals(parsed.reach_structure());
        var record = new PhysicalStructureSearchTaskRecord(
                toolCallId,
                player.level().getGameTime() + INITIAL_LIVENESS_LEASE_TICKS,
                parsed.structure_id(),
                distance,
                alter,
                reach,
                Boolean.TRUE.equals(parsed.allow_rare_consumables()));
        setTask(player, record, args, reply);
    }
}
