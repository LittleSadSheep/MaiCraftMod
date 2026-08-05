// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.task.lighting.SemanticLightAreaTaskRecord;

/** Hidden execution tool for semantic lighting; placements never enter its arguments. */
public final class SemanticLightAreaTool implements MaiCraftTool {
    @Override public String name() {
        return SemanticLightAreaTaskRecord.TOOL_NAME;
    }

    @Override public String description() {
        return "Make a bounded loaded area meet an actual block-light threshold. Supply only the "
                + "semantic area anchor, radius, coverage policy, aesthetic preferences and protected "
                + "labels. The Mod observes loaded cells, derives protected footprints, chooses a "
                + "light source and placement set, delegates real first-person placement to Build, "
                + "acquires the exact current batch before any placement, re-investigates after "
                + "supply, "
                + "then re-reads block light and performs bounded corrective passes. It never reports "
                + "success from a theoretical spacing calculation.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("center_x", "Internal loaded-area observation anchor X.")
                .integer("center_y", "Internal loaded-area observation anchor Y.")
                .integer("center_z", "Internal loaded-area observation anchor Z.")
                .optionalString("semantic_target", "Area label retained as semantic evidence; never coordinates.")
                .optionalBool("resolve_loaded_component", "Resolve the nearest matching connected component in loaded facts.")
                .optionalInteger("radius", "Bounded horizontal observation radius (default 16).",
                        SemanticLightAreaTaskRecord.MIN_RADIUS,
                        SemanticLightAreaTaskRecord.MAX_RADIUS)
                .optionalInteger("minimum_light", "Required observed block-light level (default depends on coverage).", 1, 15)
                .optionalEnum("coverage", "Cells that define the verified denominator.",
                        "all", "most", "crop_growth", "player_visibility")
                .optionalEnum("style", "Semantic placement preference.",
                        "auto", "ground", "wall", "hanging", "unobtrusive")
                .optionalEnum("placement_preference",
                        "Safe-candidate ranking after measured light-coverage gain; "
                                + "central_unplanted requires crop_growth coverage.",
                        "coverage_optimal", "central_unplanted", "unobtrusive")
                .optionalString("block_id", "One preferred light-emitting block or item id.")
                .optionalStringArray("light_preferences", "Ordered acceptable light-source preferences.")
                .optionalEnum("material_policy", "Material source policy for the investigated layout.",
                        "ordinary", "storage_available", "inventory_only")
                .optionalStringArray("allowed_sources", "Permitted semantic acquisition sources; storage is tried before crafting.")
                .optionalBool("allow_harm", "Permit harmful acquisition only when explicitly true; never inferred.")
                .optionalStringArray("protected_labels", "Remembered areas that placement must not touch.")
                .optionalInteger("max_passes", "Bounded observe/build/verify passes (default 4).", 1, 8)
                .optionalInteger("max_placements", "Whole-task placement work budget (default 192).", 1, 512)
                .build();
    }

    @Override public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        var record = SemanticLightAreaApi.newRecord(ctx(toolCallId, player), args);
        setTask(player, record, args, reply);
    }
}
