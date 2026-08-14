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
        int centerX() { return Math.floorDiv(minX + maxX, 2); }
        int centerZ() { return Math.floorDiv(minZ + maxZ, 2); }
    }
    private record Site(int minX, int maxX, int minZ, int maxZ, int baseY,
                        int lowY, int highY, int[][] heights, Direction front,
                        Shore shore, int score) {}

    private static final StyleProfile DEFAULT_STYLE = new StyleProfile(
            List.of("xuanshan", "wudian"), "concave", 2, 0, true, true, 3);
    private static final List<StyleRule> STYLE_RULES = List.of(
            new StyleRule(Set.of("chinese", "japanese", "korean", "east_asian", "hanok",
                    "minka", "shrine", "pagoda"),
                    new StyleProfile(List.of("xieshan", "wudian", "xuanshan"),
                            "concave", 2, 1, true, true, 3)),
            new StyleRule(Set.of("modern", "minimal", "brutalist", "cyberpunk"),
                    new StyleProfile(List.of("shed", "xuanshan"),
                            "straight", 1, 0, false, false, 2)),
            new StyleRule(Set.of("industrial", "factory", "steampunk"),
                    new StyleProfile(List.of("shed", "xuanshan"),
                            "straight", 2, 0, true, true, 3)),
            new StyleRule(Set.of("gothic", "alpine", "nordic", "viking", "tudor"),
                    new StyleProfile(List.of("xuanshan"),
                            "straight", 2, 0, true, true, 3)),
            new StyleRule(Set.of("coastal", "seaside", "lighthouse", "mediterranean"),
                    new StyleProfile(List.of("wudian", "xuanshan"),
                            "concave", 2, 0, true, true, 3)),
            new StyleRule(Set.of("desert", "adobe", "egyptian", "mesoamerican"),
                    new StyleProfile(List.of("wudian", "shed"),
                            "straight", 1, 0, false, false, 3)),
            new StyleRule(Set.of("dwarven", "underground", "cave", "embedded"),
                    new StyleProfile(List.of("xuanshan", "shed"),
                            "straight", 1, 0, false, true, 4)));
    private static final Map<String, RoomUse> ROOM_LANGUAGE = Map.ofEntries(
            Map.entry("home", RoomUse.REST),
            Map.entry("house", RoomUse.REST),
            Map.entry("bedroom", RoomUse.REST),
            Map.entry("kitchen", RoomUse.KITCHEN),
            Map.entry("food", RoomUse.KITCHEN),
            Map.entry("workshop", RoomUse.WORK),
            Map.entry("factory", RoomUse.WORK),
            Map.entry("forge", RoomUse.WORK),
            Map.entry("storage", RoomUse.STORAGE),
            Map.entry("warehouse", RoomUse.STORAGE),
            Map.entry("farm", RoomUse.STORAGE),
            Map.entry("study", RoomUse.STUDY),
            Map.entry("library", RoomUse.STUDY),
            Map.entry("temple", RoomUse.STUDY));

    private static Site findSite(LocalPlayer player, Goal.WorldPosition anchor, Size size,
                                 String terrain, boolean replace, boolean waterfront, boolean dock) {
        int stride = Math.max(2, Math.min(size.width, size.depth) / 3);
        Site best = null;
        int checked = 0;
        for (int[] offset : offsets(stride)) {
            if (checked++ >= MAX_CANDIDATES) break;
            Site site = inspect(player, anchor.x() + offset[0], anchor.z() + offset[1],
                    anchor.y(), size, terrain, replace, waterfront, dock);
            if (site != null && (best == null || site.score < best.score)) best = site;
        }
        return best;
    }

    private static List<int[]> offsets(int stride) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{0, 0});
        for (int r = stride; r <= SEARCH_RADIUS; r += stride) {
            for (int x = -r; x <= r; x += stride) {
                out.add(new int[]{x, -r}); out.add(new int[]{x, r});
            }
            for (int z = -r + stride; z < r; z += stride) {
                out.add(new int[]{-r, z}); out.add(new int[]{r, z});
            }
        }
        return out;
    }

    private static Site inspect(LocalPlayer player, int cx, int cz, int anchorY, Size size,
                                String terrain, boolean replace, boolean waterfront, boolean dock) {
        ClientLevel level = player.clientLevel;
        int minX = cx - size.width / 2, minZ = cz - size.depth / 2;
        int maxX = minX + size.width - 1, maxZ = minZ + size.depth - 1;
        int[][] heights = new int[size.width][size.depth];
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!loadedColumn(level, x, z, anchorY)) return null;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= level.getMinBuildHeight() + 4
                    || y + size.wallHeight() + 22 >= level.getMaxBuildHeight()) return null;
            BlockPos ground = new BlockPos(x, y - 1, z);
            BlockState state = level.getBlockState(ground);
            if (!state.getFluidState().isEmpty() || !state.isFaceSturdy(level, ground, Direction.UP)
                    || sensitive(level, ground) || BlockHelper.isHazard(level, ground)) return null;
            heights[x - minX][z - minZ] = y;
            low = Math.min(low, y); high = Math.max(high, y);
        }
        int variation = high - low;
        if (terrain.equals("surface") && variation > 1) return null;
        if (!terrain.equals("surface") && (variation < 2 || variation > MAX_SLOPE
                || !gentle(heights))) return null;
        int baseY = terrain.equals("embedded") ? low : high;
        int roofTop = baseY + size.wallHeight() + Math.max(size.width, size.depth) / 2 + 5;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            int columnY = heights[x - minX][z - minZ];
            int bottom = Math.min(columnY - 3, baseY - (terrain.equals("embedded") ? 4 : 1));
            for (int y = bottom; y <= roofTop; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.isLoaded(pos) || sensitive(level, pos) || BlockHelper.isHazard(level, pos)) return null;
                BlockState state = level.getBlockState(pos);
                if (!state.getFluidState().isEmpty()) return null;
                if (!replace && y >= baseY && !state.canBeReplaced()) return null;
            }
        }
        if (!safeBoundary(level, minX, maxX, minZ, maxZ, low, high, anchorY,
                baseY, roofTop, replace, waterfront)) return null;
        int shoreLength = dock
                ? Math.max(8, Math.min(14, Math.max(size.width, size.depth) / 2 + 3))
                : 3;
        Shore shore = waterfront
                ? shore(level, minX, maxX, minZ, maxZ, baseY, shoreLength) : null;
        if (waterfront && shore == null) return null;
        Direction slopeDown = variation >= 2 ? lowEdge(heights)
                : directionToward(cx, cz, player.getBlockX(), player.getBlockZ());
        Direction front = shore != null && dock ? shore.direction : slopeDown;
        int score = Math.abs(cx - player.getBlockX()) + Math.abs(cz - player.getBlockZ())
                + variation * 8 + Math.abs(baseY - player.getBlockY()) * 2;
        return new Site(minX, maxX, minZ, maxZ, baseY, low, high, heights,
                front == null ? Direction.SOUTH : front, shore, score);
    }

    private static boolean safeBoundary(ClientLevel level, int minX, int maxX, int minZ, int maxZ,
                                        int low, int high, int anchorY, int baseY, int roofTop,
                                        boolean replace, boolean allowWater) {
        for (int x = minX - 3; x <= maxX + 3; x++) for (int z = minZ - 3; z <= maxZ + 3; z++) {
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) continue;
            if (!loadedColumn(level, x, z, anchorY)) return false;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos surface = new BlockPos(x, y - 1, z);
            BlockState state = level.getBlockState(surface);
            if (!state.getFluidState().isEmpty()) {
                if (allowWater && state.getFluidState().is(FluidTags.WATER)) continue;
                return false;
            }
            if (sensitive(level, surface) || BlockHelper.isHazard(level, surface)
                    || y < low - 3 || y > high + 3) return false;
            for (int scanY = baseY; scanY <= roofTop; scanY++) {
                BlockPos pos = new BlockPos(x, scanY, z);
                if (!level.isLoaded(pos) || sensitive(level, pos) || BlockHelper.isHazard(level, pos)) return false;
                BlockState occupied = level.getBlockState(pos);
                if (!occupied.getFluidState().isEmpty() && scanY >= baseY) return false;
                if (!replace && !occupied.canBeReplaced()) return false;
            }
        }
        return true;
    }

    private static Shore shore(ClientLevel level, int minX, int maxX, int minZ, int maxZ,
                               int baseY, int length) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Integer waterLevel = null;
            int[][] supportFloor = new int[length][3];
            boolean valid = true;
            for (int d = 1; d <= length && valid; d++) for (int lateral = -1; lateral <= 1; lateral++) {
                int x = switch (direction) {
                    case EAST -> maxX + d; case WEST -> minX - d;
                    default -> (minX + maxX) / 2 + lateral;
                };
                int z = switch (direction) {
                    case SOUTH -> maxZ + d; case NORTH -> minZ - d;
                    default -> (minZ + maxZ) / 2 + lateral;
                };
                int y = waterTop(level, x, z, baseY);
                if (y == Integer.MIN_VALUE || waterLevel != null && waterLevel != y) { valid = false; break; }
                int floor = waterFloor(level, x, z, y);
                if (floor == Integer.MIN_VALUE) { valid = false; break; }
                BlockPos deck = new BlockPos(x, y + 1, z);
                if (!level.isLoaded(deck.above()) || sensitive(level, deck)
                        || sensitive(level, deck.above())
                        || !level.getFluidState(deck).isEmpty()
                        || !level.getFluidState(deck.above()).isEmpty()
                        || !level.getBlockState(deck).canBeReplaced()
                        || !level.getBlockState(deck.above()).canBeReplaced()) {
                    valid = false;
                    break;
                }
                supportFloor[d - 1][lateral + 1] = floor;
                waterLevel = y;
            }
            if (valid && waterLevel != null && waterLevel + 1 == baseY) {
                return new Shore(direction, waterLevel, length, supportFloor);
            }
        }
        return null;
    }

    private static int waterTop(ClientLevel level, int x, int z, int aroundY) {
        for (int y = aroundY + 2; y >= aroundY - 7; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos) || !level.isLoaded(pos.above())) return Integer.MIN_VALUE;
            var fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER) && fluid.isSource()
                    && level.getFluidState(pos.above()).isEmpty()
                    && level.getBlockEntity(pos) == null) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static int waterFloor(ClientLevel level, int x, int z, int waterY) {
        for (int y = waterY; y >= waterY - 8; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos) || level.getBlockEntity(pos) != null) return Integer.MIN_VALUE;
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.WATER)) continue;
            if (!state.getFluidState().isEmpty() || sensitive(level, pos)
                    || BlockHelper.isHazard(level, pos)
                    || !state.isFaceSturdy(level, pos, Direction.UP)) {
                return Integer.MIN_VALUE;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean gentle(int[][] heights) {
        for (int x = 0; x < heights.length; x++) for (int z = 0; z < heights[x].length; z++) {
            if (x > 0 && Math.abs(heights[x][z] - heights[x - 1][z]) > 2) return false;
            if (z > 0 && Math.abs(heights[x][z] - heights[x][z - 1]) > 2) return false;
        }
        return true;
    }

    private static Direction lowEdge(int[][] h) {
        long north = 0, south = 0, west = 0, east = 0;
        for (int x = 0; x < h.length; x++) { north += h[x][0]; south += h[x][h[x].length - 1]; }
        for (int z = 0; z < h[0].length; z++) { west += h[0][z]; east += h[h.length - 1][z]; }
        Map<Direction, Double> avg = Map.of(Direction.NORTH, north / (double) h.length,
                Direction.SOUTH, south / (double) h.length, Direction.WEST, west / (double) h[0].length,
                Direction.EAST, east / (double) h[0].length);
        return avg.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(Direction.SOUTH);
    }

    private static Direction directionToward(int x, int z, int tx, int tz) {
        int dx = tx - x, dz = tz - z;
        return Math.abs(dx) > Math.abs(dz) ? (dx < 0 ? Direction.WEST : Direction.EAST)
                : (dz < 0 ? Direction.NORTH : Direction.SOUTH);
    }

    private static JsonArray design(Site s, Size size, String purpose,
                                    Set<String> features, Palette palette,
                                    StyleProfile style, String terrain, boolean replace) {
        JsonArray ops = new JsonArray();
        int floorY = s.baseY;
        int wallTop = floorY + size.wallHeight();
        BlockPos ladder = ladderPosition(s, floorY + 1);
        BlockPos cellarLadder = ladder.relative(s.front);

        if (terrain.equals("embedded")) {
            ops.add(box("minecraft:air", s.minX + 1, floorY + 1,
                    s.minZ + 1, s.maxX - 1, wallTop + 2, s.maxZ - 1, false));
        }
        if (features.contains("cellar") && replace) {
            ops.add(box(palette.foundation, s.minX + 1, floorY - 4, s.minZ + 1,
                    s.maxX - 1, floorY - 1, s.maxZ - 1, true));
            ops.add(box("minecraft:air", s.minX + 2, floorY - 3, s.minZ + 2,
                    s.maxX - 2, floorY - 1, s.maxZ - 2, false));
        }
        if (terrain.equals("hillside")) supports(ops, s, palette.foundation);
        ops.add(box(palette.foundation, s.minX, floorY, s.minZ,
                s.maxX, floorY, s.maxZ, false));
        ops.add(box(palette.floor, s.minX + 1, floorY, s.minZ + 1,
                s.maxX - 1, floorY, s.maxZ - 1, false));
        for (int storey = 1; storey < size.storeys; storey++) {
            int upperFloor = floorY + storey * size.floorHeight;
            ops.add(box(palette.floor, s.minX + 1, upperFloor, s.minZ + 1,
                    s.maxX - 1, upperFloor, s.maxZ - 1, false));
        }
        ops.add(walls(palette.wall, s.minX, floorY + 1, s.minZ,
                s.maxX, wallTop, s.maxZ));
        if (style.exposedFrame) frameShell(ops, s, floorY, wallTop, size, palette.frame);

        List<RoomUse> functions = roomFunctions(purpose, features);
        Set<Long> reserved = new LinkedHashSet<>();
        reserveColumn(reserved, ladder, floorY + 1, wallTop);
        if (features.contains("cellar")) {
            reserveColumn(reserved, cellarLadder, floorY - 3, floorY);
        }
        int roomSerial = 0;
        for (int storey = 0; storey < size.storeys; storey++) {
            int storeyFloor = floorY + storey * size.floorHeight;
            List<Room> rooms = partitionRooms(
                    ops, s, size, storeyFloor, palette.wall, functions, roomSerial);
            roomSerial += rooms.size();
            for (Room room : rooms) {
                ceilingAndLight(ops, room, storeyFloor + size.floorHeight,
                        palette.frame, palette.light, reserved);
                furnish(ops, room, storeyFloor, palette, reserved);
            }
        }

        if (size.storeys > 1) {
