// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.core.task.lighting.SemanticLightAreaTaskRecord;

/** Registration and typed-record seam for verified, semantic loaded-area lighting. */
public final class SemanticLightAreaApi {
    private static final long MIN_TOTAL_TICKS = 2L * 60L * 20L;
    private static final long MAX_TOTAL_TICKS = 15L * 60L * 20L;

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
        int radius = integer(args, "radius", 16,
                SemanticLightAreaTaskRecord.MIN_RADIUS,
                SemanticLightAreaTaskRecord.MAX_RADIUS);
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

    private static int integer(
            JsonObject args, String key, int fallback, int minimum, int maximum) {
        if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return Math.clamp(args.get(key).getAsInt(), minimum, maximum);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static boolean bool(JsonObject args, String key, boolean fallback) {
        if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsBoolean();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be a boolean");
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

    private static List<String> strings(JsonElement value, String label) {
        if (value == null || value.isJsonNull()) return List.of();
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException(label + " must be an array of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                throw new IllegalArgumentException(label + " must contain only strings");
            }
            String valueString = element.getAsString().strip();
            if (!valueString.isEmpty()) result.add(valueString);
        }
        return List.copyOf(result);
    }
}
