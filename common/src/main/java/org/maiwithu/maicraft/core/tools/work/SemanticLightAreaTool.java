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

/** 语义照明的隐藏执行工具；放置坐标不会作为参数传入。 */
public final class SemanticLightAreaTool implements MaiCraftTool {
    @Override public String name() {
        return SemanticLightAreaTaskRecord.TOOL_NAME;
    }

    @Override public String description() {
        // 模型入口使用 light_area Goal；隐藏工具说明内部已解析参数，区分主任务区域验收与随行配置成功。
        return "Internal executor for maicraft:light_area. Resolve the semantic target before calling; "
                + "center_x/y/z are an investigation seed, not lamp coordinates. This is body work: "
                + "observe the boundary and dark cells, choose supported ground candidates, acquire "
                + "the current batch if needed, re-observe after supply, place, wait for light propagation "
                + "and verify actual BLOCK light. Connected discovery may travel to load frontiers; "
                + "explicit-circle scanning fails if any requested column is unloaded. Defaults are "
                + "coverage=all and minimum_light=8; crop_growth defaults to 9. Survival-mode torches "
                + "use the offhand fast pass; other light sources and free-material mode use Build. "
                + "Native placement receipts and achieved coverage are separate evidence. Inspect "
                + "lighting_observation, build_receipts and supply_receipts even after partial failure. "
                + "Use auto_light for route-following assistance without replacing the current task.";
    }

    @Override public Map<String, Object> parameterSchema() {
        // 参数只描述范围、验收比例和取材选择；灯位仍由实时调查产生，物品偏好不能解释成禁止所有备选材质。
        return Schema.object()
                .integer("center_x", "Internal semantic-area landmark seed X.")
                .integer("center_y", "Internal semantic-area landmark seed Y.")
                .integer("center_z", "Internal semantic-area landmark seed Z.")
                .optionalString("semantic_target", "Area label retained as semantic evidence; never coordinates.")
                .optionalBool("resolve_loaded_component", "Progressively close the matching connected component from live block facts; internal semantic compiler field.")
                .optionalInteger("radius", "Optional horizontal circle radius in blocks, 1..29999984. "
                                + "Omitted/null means no explicit radius; 0 is invalid here. "
                                + "Connected discovery, when selected, stays within this boundary.",
                        SemanticLightAreaTaskRecord.MIN_RADIUS,
                        SemanticLightAreaTaskRecord.MAX_EXPLICIT_RADIUS)
                .optionalInteger("minimum_light", "Actual BLOCK light, 1..15; omitted/null defaults to 9 for crop_growth, otherwise 8; independent of sky light.", 1, 15)
                .optionalEnum("coverage", "Default all: 100% of sampled walkable feet cells. most requires 90%, player_visibility 95% of the same cells; crop_growth requires 100% of crop/farmland-above samples.",
                        "all", "most", "crop_growth", "player_visibility")
                .optionalEnum("style", "Default auto; ground and unobtrusive also use supported ground candidates. No wall/hanging area planner.",
                        "auto", "ground", "unobtrusive")
                .optionalEnum("placement_preference",
                        "Default coverage_optimal. Tie-break after estimated gain over measured dark cells; "
                                + "central_unplanted requires crop_growth coverage.",
                        "coverage_optimal", "central_unplanted", "unobtrusive")
                .optionalString("block_id", "First preferred light-emitting block/item id, e.g. minecraft:torch; not a strict material lock. Unusable preferences are skipped.")
                .optionalStringArray("light_preferences", "Ordered preferences after block_id; omitted/null/[] adds none. Carried and standard fallback light sources remain eligible.")
                .optionalEnum("material_policy", "Default ordinary; inventory_only avoids acquisition unless allowed_sources explicitly includes storage. storage_available enables storage and crafting in the shared supplier.",
                        "ordinary", "storage_available", "inventory_only")
                .optionalStringArray("allowed_sources", "inventory, nearby, wireless, storage, harvest, craft, cook, mine, trade, hunt. Omitted/null/[] uses material-policy defaults. Shared supply always adds inventory and adds craft when storage is enabled; this is not a strict whitelist.")
                .optionalBool("allow_harm", "Default false, also for null; true explicitly permits harmful acquisition through the shared supplier.")
                .optionalStringArray("protected_labels", "Same-dimension remembered labels. Area placement protects each anchor within +/-4 blocks on every axis, plus observed sensitive cells; a name is not an arbitrary full-region shape.")
                .optionalInteger("max_placements",
                        "Optional 1..24000 placement budget across passes. Omitted/null means no "
                                + "explicit cap; 0 is invalid. Counts submitted torch sites or dispatched "
                                + "ordinary-build sites, not confirmed material consumption.",
                        1, SemanticLightAreaTaskRecord.MAX_EXPLICIT_PLACEMENTS)
                .build();
    }

    @Override public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        var record = SemanticLightAreaApi.newRecord(ctx(toolCallId, player), args);
        setTask(player, record, args, reply);
    }
}
