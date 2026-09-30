// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.core.tools.SemanticParameters.bool;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.integer;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.strings;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.core.task.lighting.SemanticLightAreaTaskRecord;

/** 已核实语义区域照明的注册入口和类型化记录接口。 */
public final class SemanticLightAreaApi {
    /** 初始存活期限；子任务或覆盖范围取得已核实进展后，可跨 tick 续期。 */
    private static final long INITIAL_PROGRESS_LEASE_TICKS = 2L * 60L * 20L;

    private SemanticLightAreaApi() {}

    public static void register() {
        SemanticLightAreaTaskRecord.ensureRegistered();
        ToolRegistry.register(new SemanticLightAreaTool());
    }

    public static SemanticLightAreaTaskRecord newRecord(
            ToolContext context, JsonObject arguments) {
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        int x = requiredInteger(args, "center_x");
        int y = requiredInteger(args, "center_y");
        int z = requiredInteger(args, "center_z");
        boolean explicitRadius = args.has("radius") && !args.get("radius").isJsonNull();
        int radius = explicitRadius
                ? integer(args, "radius", 0,
                        SemanticLightAreaTaskRecord.MIN_RADIUS,
                        SemanticLightAreaTaskRecord.MAX_EXPLICIT_RADIUS)
                : 0;
        SemanticLightAreaTaskRecord.Coverage coverage =
                SemanticLightAreaTaskRecord.Coverage.parse(string(args, "coverage"));
        int defaultLight = coverage == SemanticLightAreaTaskRecord.Coverage.CROP_GROWTH
                ? 9 : 8;
        int minimumLight = integer(args, "minimum_light", defaultLight, 1, 15);
        SemanticLightAreaTaskRecord.Style style =
                SemanticLightAreaTaskRecord.Style.parse(string(args, "style"));
        SemanticLightAreaTaskRecord.PlacementPreference placementPreference =
                SemanticLightAreaTaskRecord.PlacementPreference.parse(
                        string(args, "placement_preference"));

        List<String> preferences = new ArrayList<>();
        String preferred = string(args, "block_id");
        if (preferred != null) preferences.add(preferred);
        preferences.addAll(strings(args.get("light_preferences"), "light_preferences"));
        List<String> protectedLabels = strings(
                args.get("protected_labels"), "protected_labels");
        var materialPolicy = SemanticMaterialSupplyCoordinator.MaterialPolicy.parse(
                string(args, "material_policy"));
        var allowedSources = SemanticMaterialSupplyCoordinator.parseSources(
                strings(args.get("allowed_sources"), "allowed_sources"));
        boolean allowHarm = bool(args, "allow_harm", false);
        int maxPlacements = args.has("max_placements")
                        && !args.get("max_placements").isJsonNull()
                ? integer(args, "max_placements", 0, 1,
                        SemanticLightAreaTaskRecord.MAX_EXPLICIT_PLACEMENTS)
                : 0;
        String semanticTarget = string(args, "semantic_target");
        boolean resolveLoadedComponent = bool(
                args, "resolve_loaded_component", !explicitRadius);

        return new SemanticLightAreaTaskRecord(
                context.toolCallId(), context.deadline(INITIAL_PROGRESS_LEASE_TICKS),
                new BlockPos(x, y, z), semanticTarget, resolveLoadedComponent,
                radius, minimumLight, coverage, style, placementPreference,
                preferences, protectedLabels,
                materialPolicy, allowedSources, allowHarm,
                maxPlacements);
    }

    private static int requiredInteger(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException(key + " is required");
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static String string(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) return null;
        if (!args.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        String value = args.get(key).getAsString().strip();
        return value.isEmpty() ? null : value;
    }
}
