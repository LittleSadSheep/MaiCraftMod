package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.tools.work.BuildTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Loaded-client-only semantic site selection and bounded BuildTool planning. */
public final class SemanticBuildPlanner {
    private static final int MAX_CELLS = 16_384;
    private static final int SEARCH_RADIUS = 32;
    private static final int MAX_CANDIDATES = 128;
    private static final int MAX_SLOPE = 6;
    /** Internal-only capability used after one fully valid plan has no loaded safe site. */
    public static final String SITE_INVESTIGATION_TOOL = "investigate_build_site";

    private SemanticBuildPlanner() {}

    /** Integration point for {@link AbilityAdapter}; per-cell ops never enter the Goal. */
    public static IntentAction plan(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        JsonObject p = goal.parameters();
        if (p.has("ops")) return decision(goal, "Semantic builds cannot contain per-cell ops.",
                option("replace_goal", "Describe purpose, size, terrain fit and features."),
                option("skip", "Leave the world unchanged."), option("cancel", "Cancel the task."));

        String purpose = text(p, "purpose");
        if (purpose == null || purpose.isBlank()) purpose = goal.outcome();
        Size size = parseSize(p.get("size"));
        if (size == null) return decision(goal, "Build size is missing or invalid; no footprint was assumed.",
                option("replace_goal", "Use small/medium/large or width, depth and height."),
                option("skip", "Skip this structure."), option("cancel", "Cancel the task."));

        String terrain = normalized(text(p, "terrain_fit"), "surface");
        if (terrain.equals("cave") || terrain.equals("underground")) terrain = "embedded";
        if (!Set.of("surface", "hillside", "embedded").contains(terrain)) {
            return decision(goal, "Unsupported terrain_fit: " + terrain + ".",
                    option("recover", "Travel semantically to another terrain type."),
                    option("replace_goal", "Choose surface, hillside or embedded."),
                    option("skip", "Skip this structure."), option("cancel", "Cancel the task."));
        }
        boolean replace = bool(p, "replace_existing", false);
        Set<String> features = normalizedFeatures(p.get("features"));
        Set<String> unsupportedFeatures = new LinkedHashSet<>(features);
        unsupportedFeatures.removeAll(Set.of(
                "dock", "porch", "cellar", "workshop", "windows", "rooms", "interior",
                "furnished", "lighting", "storage", "kitchen", "study", "bedroom"));
        if (!unsupportedFeatures.isEmpty()) return decision(goal,
                "Unsupported semantic build features: " + unsupportedFeatures + ".",
                option("replace_goal", "Choose dock, porch, cellar, workshop or windows."),
                option("skip", "Skip this structure."), option("cancel", "Cancel the task."));
        if ((terrain.equals("embedded") || features.contains("cellar")) && !replace) {
            return decision(goal, "Embedded space needs verified excavation, but replace_existing is false.",
                    option("retry", "Retry with details.parameters={\"replace_existing\":true} "
                            + "after approving terrain removal."),
                    option("replace_goal", "Use a non-excavating surface or hillside design."),
                    option("skip", "Skip this structure."), option("cancel", "Cancel the task."));
        }

        String policy = normalized(text(p, "material_policy"), "available");
        if (!Set.of("available", "storage_available", "specified", "preserve_rare").contains(policy)) {
            return decision(goal, "Unsupported material_policy: " + policy + ".",
                    option("replace_goal", "Use available, storage_available, specified or preserve_rare."),
                    option("skip", "Skip this structure."), option("cancel", "Cancel the task."));
        }
        List<String> preferred = resources(p.get("preferred_materials"));
        if (policy.equals("specified") && preferred.isEmpty()) {
            return decision(goal, "material_policy=specified needs preferred_materials.",
                    option("replace_goal", "Provide block resource ids."),
                    option("skip", "Skip this structure."), option("cancel", "Cancel the task."));
        }

        Goal.WorldPosition anchor = target(goal, player, runtime);
        if (anchor == null) return decision(goal,
                "The semantic target is unresolved, unloaded, or in another dimension.",
                option("recover", "Travel semantically to load the intended area."),
                option("replace_goal", "Choose current_place, loaded coordinates or a landmark."),
                option("skip", "Skip this structure."), option("cancel", "Cancel the task."));

        StyleProfile style = styleProfile(text(p, "style"), purpose);
        boolean waterfront = waterfront(purpose, features);
        Site site = findSite(player, anchor, size, terrain, replace, waterfront, features.contains("dock"));
        if (site == null) {
            // Every semantic and permission check above already passed. A missing loaded site gets
            // one bounded internal first-person investigation; invalid goals still stop normally.
            JsonObject investigation = new JsonObject();
            investigation.add("goal", goal.toJson());
            return new IntentAction.Tool(SITE_INVESTIGATION_TOOL, investigation.toString());
        }

        Palette palette = palette(player, preferred, policy);
        JsonArray ops = design(site, size, purpose, features, palette, style, terrain, replace);
        int resolvedCells;
        try {
            resolvedCells = BuildTool.resolvedCellCount(ops);
        } catch (RuntimeException invalidDesign) {
            return decision(goal,
                    "The bounded semantic design could not be expressed safely: "
                            + invalidDesign.getMessage(),
                    option("replace_goal", "Choose another style, feature set or material policy."),
                    option("skip", "Skip this structure."), option("cancel", "Cancel the task."));
        }
        if (resolvedCells > MAX_CELLS) return decision(goal,
                "The reviewed design resolves to exactly " + resolvedCells
                        + " cells, above the " + MAX_CELLS + " limit.",
                option("replace_goal", "Choose a smaller footprint, fewer storeys or fewer features."),
                option("skip", "Skip this structure."), option("cancel", "Cancel the task."));

        JsonObject args = new JsonObject();
        args.add("ops", ops);
        args.addProperty("replace_existing", replace);
        args.addProperty("allow_partial", policy.equals("storage_available"));
        args.add("semantic_contract", semanticContract(
                size, features, site, resolvedCells, purpose));
        args.add("traversability_contract", traversabilityContract(site, size, features));
        return new IntentAction.Tool("build", args.toString());
    }

    /** Outcome of a fresh read-only loaded-world probe made by the hidden investigation task. */
    public record LoadedBuildProbe(
            Status status, JsonObject buildArguments, String failureCode, String message) {
        public enum Status { READY, NO_SITE, INVALID }

        static LoadedBuildProbe ready(JsonObject arguments) {
            return new LoadedBuildProbe(Status.READY, arguments, null, "safe loaded site verified");
        }

        static LoadedBuildProbe noSite(boolean waterfront) {
            return new LoadedBuildProbe(Status.NO_SITE, null,
                    waterfront ? "no_verified_loaded_waterfront_site" : "no_verified_loaded_build_site",
                    waterfront
                            ? "no safe loaded site with a verified source-water shoreline was found"
                            : "no safe bounded site was found in loaded terrain near the target");
        }

        static LoadedBuildProbe invalid(String code, String message) {
            return new LoadedBuildProbe(Status.INVALID, null, code, message);
        }
    }

    /**
     * Re-run the same strict loaded-only site selection after real first-person movement.
     * READY returns frozen BuildTool arguments; generated coordinates remain inside the Mod.
     */
    public static LoadedBuildProbe probeLoadedBuild(
            Goal goal, LocalPlayer player, IntentRuntime runtime) {
        return probeLoadedBuildAt(goal, player, runtime, player.blockPosition());
    }

    /**
     * Strictly probe around one internally observed first-person survey stance. The original
     * semantic target is still resolved for validity/dimension, but is not reused as the local
     * loaded scan centre; otherwise a prior-result coordinate would be scanned forever.
     */
    public static LoadedBuildProbe probeLoadedBuildAt(
            Goal goal, LocalPlayer player, IntentRuntime runtime, BlockPos surveyAnchor) {
        JsonObject p = goal.parameters();
        if (p.has("ops")) return LoadedBuildProbe.invalid(
                "per_cell_build_forbidden", "semantic builds cannot contain per-cell ops");

        String purpose = text(p, "purpose");
        if (purpose == null || purpose.isBlank()) purpose = goal.outcome();
        Size size = parseSize(p.get("size"));
        if (size == null) return LoadedBuildProbe.invalid(
                "invalid_build_size", "build size is missing or invalid");

        String terrain = normalized(text(p, "terrain_fit"), "surface");
        if (terrain.equals("cave") || terrain.equals("underground")) terrain = "embedded";
        if (!Set.of("surface", "hillside", "embedded").contains(terrain)) {
            return LoadedBuildProbe.invalid("unsupported_terrain_fit",
                    "unsupported terrain_fit: " + terrain);
        }
        boolean replace = bool(p, "replace_existing", false);
        Set<String> features = normalizedFeatures(p.get("features"));
        Set<String> unsupported = new LinkedHashSet<>(features);
        unsupported.removeAll(Set.of(
                "dock", "porch", "cellar", "workshop", "windows", "rooms", "interior",
                "furnished", "lighting", "storage", "kitchen", "study", "bedroom"));
        if (!unsupported.isEmpty()) return LoadedBuildProbe.invalid(
                "unsupported_build_features", "unsupported semantic build features: " + unsupported);
        if ((terrain.equals("embedded") || features.contains("cellar")) && !replace) {
            return LoadedBuildProbe.invalid("excavation_not_authorized",
                    "embedded space requires replace_existing=true");
        }

        String policy = normalized(text(p, "material_policy"), "available");
        if (!Set.of("available", "storage_available", "specified", "preserve_rare").contains(policy)) {
            return LoadedBuildProbe.invalid("unsupported_material_policy",
                    "unsupported material_policy: " + policy);
        }
        List<String> preferred = resources(p.get("preferred_materials"));
        if (policy.equals("specified") && preferred.isEmpty()) {
            return LoadedBuildProbe.invalid("specified_materials_missing",
                    "material_policy=specified needs preferred_materials");
        }

        Goal.WorldPosition semanticAnchor = target(goal, player, runtime);
        if (semanticAnchor == null) return LoadedBuildProbe.invalid(
                "semantic_target_unresolved",
                "the semantic target is unresolved or in another dimension");
        if (surveyAnchor == null || !player.clientLevel.hasChunkAt(surveyAnchor)) {
            return LoadedBuildProbe.noSite(waterfront(purpose, features));
        }
        Goal.WorldPosition anchor = new Goal.WorldPosition(
                surveyAnchor.getX(), surveyAnchor.getY(), surveyAnchor.getZ(),
                player.level().dimension().location().toString());
        StyleProfile style = styleProfile(text(p, "style"), purpose);
        boolean waterfront = waterfront(purpose, features);
        Site site = findSite(player, anchor, size, terrain, replace,
                waterfront, features.contains("dock"));
        if (site == null) return LoadedBuildProbe.noSite(waterfront);

        try {
            Palette palette = palette(player, preferred, policy);
            JsonArray ops = design(site, size, purpose, features, palette, style, terrain, replace);
            int resolvedCells = BuildTool.resolvedCellCount(ops);
            if (resolvedCells > MAX_CELLS) return LoadedBuildProbe.invalid(
                    "semantic_build_cell_limit",
                    "the reviewed design resolves above the " + MAX_CELLS + " cell limit");
            JsonObject args = new JsonObject();
            args.add("ops", ops);
            args.addProperty("replace_existing", replace);
            args.addProperty("allow_partial", policy.equals("storage_available"));
            args.add("semantic_contract", semanticContract(
                    size, features, site, resolvedCells, purpose));
            args.add("traversability_contract", traversabilityContract(site, size, features));
            return LoadedBuildProbe.ready(args);
        } catch (RuntimeException invalidDesign) {
            return LoadedBuildProbe.invalid("semantic_design_invalid",
                    "the bounded semantic design could not be expressed safely: "
                            + invalidDesign.getMessage());
        }
    }

    /** Candidate-ordering hint only; acceptance always goes through probeLoadedBuild. */
    public static boolean requiresWaterfront(Goal goal) {
        JsonObject parameters = goal.parameters();
        String purpose = text(parameters, "purpose");
        if (purpose == null || purpose.isBlank()) purpose = goal.outcome();
        return waterfront(purpose, normalizedFeatures(parameters.get("features")));
    }

    /** Resolve the semantic focus internally. It is never included in task receipts. */
    public static Goal.WorldPosition investigationAnchor(
            Goal goal, LocalPlayer player, IntentRuntime runtime) {
        return target(goal, player, runtime);
    }

    private record Size(int width, int depth, int storeys, int floorHeight) {
        int wallHeight() { return storeys * floorHeight; }
    }
    private record Shore(Direction direction, int waterY, int length, int[][] supportFloor) {
        int supportFloor(int distance, int lateral) {
            return supportFloor[distance - 1][lateral + 1];
        }
    }
    private record Palette(
            String foundation, String floor, String wall, String frame, String roof,
            String accent, String door, String window, String railing, String ladder,
            String light, String storage, String work, String study, String seat,
            String textile) {}
    private record StyleProfile(
            List<String> roofShapes, String roofCurve, int overhang, int cornerLift,
            boolean soffit, boolean exposedFrame, int windowStride) {}
    private record StyleRule(Set<String> tokens, StyleProfile profile) {}
    private enum RoomUse { COMMON, REST, KITCHEN, WORK, STORAGE, STUDY }
    private record Room(int minX, int maxX, int minZ, int maxZ, RoomUse use) {
