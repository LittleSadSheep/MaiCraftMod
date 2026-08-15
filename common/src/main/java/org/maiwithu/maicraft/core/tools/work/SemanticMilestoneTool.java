// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.task.progression.ReachMilestoneTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;

/** Hidden executor behind the single semantic reach-milestone ability. */
public final class SemanticMilestoneTool implements MaiCraftTool {
    private static final Gson GSON = new Gson();

    private record Args(
            String milestone,
            Integer max_search_distance,
            Integer max_portal_search_radius,
            Float minimum_health,
            Boolean allow_combat,
            Boolean allow_rare_consumables,
            Boolean may_alter_terrain,
            List<String> allowed_sources,
            String material_policy,
            List<String> protected_labels) {}

    @Override
    public String name() {
        return ReachMilestoneTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Reach one survival progression milestone by reconciling live inventory, equipment, "
                + "dimension, structure and encounter facts. MaiCraft privately derives and executes "
                + "typed prerequisites. It never accepts coordinates, routes, entity IDs, slots, "
                + "recipes or item checklists, and it never builds, repairs or activates portals.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("milestone", "Semantic progression outcome.",
                        "nether", "stronghold", "defeat_dragon", "elytra")
                .optionalInteger(
                        "max_search_distance", "Bounded physical structure/End search distance.",
                        ReachMilestoneTaskRecord.MIN_SEARCH_DISTANCE,
                        ReachMilestoneTaskRecord.MAX_SEARCH_DISTANCE)
                .optionalInteger(
                        "max_portal_search_radius", "Loaded active-portal evidence radius.",
                        ReachMilestoneTaskRecord.MIN_PORTAL_RADIUS,
                        ReachMilestoneTaskRecord.MAX_PORTAL_RADIUS)
                .optionalBool("allow_combat", "Permit hostile combat required by the milestone.")
                .optionalBool(
                        "allow_rare_consumables",
                        "Permit typed rare progression consumption such as eyes or gateway pearls.")
                .optionalBool(
                        "may_alter_terrain",
                        "Permit ordinary route/mining terrain changes; this is never portal-lifecycle permission.")
                .optionalInteger(
                        "minimum_health", "Minimum health for a permitted boss encounter.", 1, 1024)
                .optionalStringArray(
                        "allowed_sources", "Semantic supply sources; never concrete targets or slots.")
                .optionalEnum(
                        "material_policy", "Ordinary, storage-first or inventory-only supply.",
                        "ordinary", "storage_available", "inventory_only")
                .optionalStringArray(
                        "protected_labels", "Remembered areas, entities or possessions not to touch.")
                .build();
    }

    @Override
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        JsonObject input = args == null ? new JsonObject() : args;
        Args parsed = GSON.fromJson(input, Args.class);
        if (parsed == null) throw new IllegalArgumentException("milestone is required");
        ReachMilestoneTaskRecord.Milestone milestone =
                ReachMilestoneTaskRecord.Milestone.parse(parsed.milestone());
        int maxDistance = parsed.max_search_distance() == null
                ? ReachMilestoneTaskRecord.DEFAULT_SEARCH_DISTANCE
                : parsed.max_search_distance();
        int portalRadius = parsed.max_portal_search_radius() == null
                ? ReachMilestoneTaskRecord.DEFAULT_PORTAL_RADIUS
                : parsed.max_portal_search_radius();
        float minimumHealth = parsed.minimum_health() == null
                ? 10.0F : parsed.minimum_health();
        var sources = SemanticMaterialSupplyCoordinator.parseSources(parsed.allowed_sources());
        var policy = SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(parsed.material_policy());
        long initialLease = switch (milestone) {
            case NETHER -> 45L * 60L * 20L;
            case STRONGHOLD -> 2L * 60L * 60L * 20L;
            case DEFEAT_DRAGON, ELYTRA -> 4L * 60L * 60L * 20L;
        };
        var context = ctx(toolCallId, player);
        var record = new ReachMilestoneTaskRecord(
                context.toolCallId(), context.deadline(initialLease), milestone,
                maxDistance, portalRadius, minimumHealth,
                Boolean.TRUE.equals(parsed.allow_combat()),
                Boolean.TRUE.equals(parsed.allow_rare_consumables()),
                Boolean.TRUE.equals(parsed.may_alter_terrain()),
                sources, policy, parsed.protected_labels());
        setTask(player, record, input, reply);
    }
}
