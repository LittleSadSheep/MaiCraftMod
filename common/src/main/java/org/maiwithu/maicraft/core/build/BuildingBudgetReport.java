// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** LLM 查询建筑能力时读取本实例实际生效的预算，避免继续按旧的16384格缩小用户建筑。 */
public final class BuildingBudgetReport {
    private BuildingBudgetReport() {}

    public static JsonObject current() {
        var budget = BuildingBudgets.current(); JsonObject limits = new JsonObject();
        limits.addProperty("max_targets", budget.maxTargets());
        limits.addProperty("air_targets_count_toward_limit", true);
        limits.addProperty("max_objects", budget.maxObjects());
        limits.addProperty("max_connections", budget.maxConnections());
        limits.addProperty("max_radius", budget.maxRadius());
        limits.addProperty("max_voxel_work", budget.maxVoxelWork());
        limits.addProperty("max_scene_bytes", budget.maxSceneBytes());
        limits.addProperty("max_project_bytes", budget.maxProjectBytes());
        limits.addProperty("max_scaffolds", budget.maxScaffolds());
        limits.addProperty("max_scaffold_bytes", budget.maxScaffoldBytes());
        limits.addProperty("max_cleanup_access_cells", budget.maxCleanupAccessCells());
        limits.addProperty("max_mcp_request_bytes", budget.maxMcpRequestBytes());
        limits.addProperty("max_intent_state_bytes", budget.maxIntentStateBytes());
        JsonObject imports = new JsonObject();
        imports.addProperty("max_file_bytes", budget.maxImportFileBytes());
        imports.addProperty("max_nbt_bytes", budget.maxImportNbtBytes());
        imports.addProperty("max_region_volume", budget.maxImportVolume());
        imports.addProperty("max_palette_entries", budget.maxImportPaletteEntries());
        imports.addProperty("slice_millis", budget.importSliceMillis());
        imports.addProperty("entries_per_slice", budget.importEntriesPerSlice());
        limits.add("import", imports);
        JsonObject preview = new JsonObject();
        preview.addProperty("max_cells", budget.maxPreviewCells());
        preview.addProperty("distance_blocks", budget.previewDistance());
        preview.addProperty("frame_millis", budget.previewFrameMillis());
        preview.addProperty("preparation_steps", budget.previewPreparationSteps());
        preview.addProperty("rebuild_cells", budget.previewRebuildCells());
        preview.addProperty("resort_sections", budget.previewResortSections());
        preview.addProperty("requires_loaded_terrain", true);
        limits.add("preview", preview);
        limits.addProperty("configuration", BuildingBudgets.CONFIG_PATH);
        limits.addProperty("configuration_applies_after", "client_restart");
        // 数量预算只描述可以接收的计划；实际地形、原生放置和施工权限仍要各自验证。
        limits.addProperty("execution_model", "complete_target_plan_with_bounded_preview_and_import_work");
        JsonArray issues = new JsonArray();
        for (var diagnostic : budget.diagnostics()) {
            JsonObject row = new JsonObject(); row.addProperty("key", diagnostic.key());
            row.addProperty("message", diagnostic.message()); issues.add(row);
        }
        limits.add("configuration_diagnostics", issues);
        return limits;
    }
}
