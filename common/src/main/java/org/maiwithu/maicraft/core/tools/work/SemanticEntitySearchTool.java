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
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchTaskRecord;

/** Semantic entity discovery; concrete identities, positions and route legs stay inside the Mod. */
public final class SemanticEntitySearchTool implements MaiCraftTool {
    private static final Gson GSON = new Gson();

    private record Args(
            List<String> entity_type_ids,
            String relation,
            Integer count,
            Integer max_distance,
            Boolean may_alter_terrain,
            List<String> protected_labels) {}

    @Override
    public String name() {
        return GenericEntitySearchTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Find real entities by namespaced type and semantic relationship. The Mod scans "
                + "currently loaded client evidence first, then walks bounded first-person frontiers "
                + "and rescans. It never accepts runtime entity IDs or coordinates, queries a seed or "
                + "server locate authority, forces chunks, or treats named/tamed/owned/leashed, "
                + "persistent, enclosed or protected entities as wild/unowned.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .stringArray("entity_type_ids",
                        "Acceptable namespaced entity types; never runtime entity IDs.", 1)
                .optionalEnum("relation",
                        "Relationship evidence: wild, hostile, unowned or any (default any).",
                        "wild", "hostile", "unowned", "any")
                .optionalInteger("count", "Required distinct observed count; partial counts fail.",
                        1, GenericEntitySearchTaskRecord.MAX_COUNT)
                .optionalInteger("max_distance",
                        "Maximum bounded first-person search radius from the starting place.",
                        GenericEntitySearchTaskRecord.MIN_DISTANCE,
                        GenericEntitySearchTaskRecord.MAX_DISTANCE)
                .optionalBool("may_alter_terrain",
                        "Explicit route permission to dig, bridge or pillar; default false.")
                .optionalStringArray("protected_labels",
                        "Remembered places or possessions that search evidence must not use.")
                .build();
    }

    @Override
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        var record = SemanticEntitySearchApi.newRecord(
                ctx(toolCallId, player),
                parsed == null ? null : parsed.entity_type_ids(),
                parsed == null ? null : parsed.relation(),
                parsed == null ? null : parsed.count(),
                parsed == null ? null : parsed.max_distance(),
                parsed == null ? null : parsed.may_alter_terrain(),
                parsed == null ? null : parsed.protected_labels());
        setTask(player, record, args, reply);
    }
}
