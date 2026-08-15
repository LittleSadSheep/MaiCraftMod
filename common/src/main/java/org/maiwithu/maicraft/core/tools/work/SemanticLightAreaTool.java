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
        return "Make a semantic area meet an actual block-light threshold. Supply only the "
                + "semantic area seed, coverage policy, aesthetic preferences and protected "
                + "labels. The Mod progressively discovers the connected boundary from real loaded "
                + "block evidence, travels to load unresolved frontiers, derives protected footprints, chooses a "
                + "light source and placement set, delegates real first-person placement to Build, "
                + "acquires the exact current batch before any placement, re-investigates after "
                + "supply, "
                + "then re-reads block light and performs corrective passes until coverage is met or "
                + "live evidence stops improving. It never reports "
                + "success from a theoretical spacing calculation.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("center_x", "Internal semantic-area landmark seed X.")
                .integer("center_y", "Internal semantic-area landmark seed Y.")
                .integer("center_z", "Internal semantic-area landmark seed Z.")
                .optionalString("semantic_target", "Area label retained as semantic evidence; never coordinates.")
                .optionalBool("resolve_loaded_component", "Progressively close the matching connected component from live block facts; internal semantic compiler field.")
                .optionalInteger("radius", "Optional player-authored geometric boundary. Omit it so "
                                + "MaiCraft discovers the complete connected semantic component.",
                        SemanticLightAreaTaskRecord.MIN_RADIUS,
                        SemanticLightAreaTaskRecord.MAX_EXPLICIT_RADIUS)
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
                .optionalInteger("max_placements",
                        "Optional explicit whole-task placement budget. Omit it to let verified "
                                + "coverage/convergence decide when work ends.",
                        1, SemanticLightAreaTaskRecord.MAX_EXPLICIT_PLACEMENTS)
                .build();
    }

    @Override public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        var record = SemanticLightAreaApi.newRecord(ctx(toolCallId, player), args);
        setTask(player, record, args, reply);
    }
}
