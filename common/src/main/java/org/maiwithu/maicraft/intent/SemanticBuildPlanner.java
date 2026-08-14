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
            verticalLink(ops, ladder, s.front, floorY, size, palette.ladder);
        }
        if (features.contains("cellar") && replace) {
            ops.add(set("minecraft:air", cellarLadder.getX(), floorY,
                    cellarLadder.getZ()));
            for (int y = floorY - 3; y <= floorY; y++) {
                ops.add(setFacing(palette.ladder, cellarLadder.getX(), y,
                        cellarLadder.getZ(), s.front));
            }
        }

        List<BlockPos> openings = new ArrayList<>();
        BlockPos mainDoor = edge(s, s.front, floorY + 1, 0);
        BlockPos interiorEntry = edge(s, s.front, floorY + 1, -1);
        // A centred facade door can meet a centred partition wall. The entrance is a semantic
        // invariant, so cut its two-block landing after all room walls and furniture are planned.
        ops.add(set("minecraft:air", interiorEntry.getX(), interiorEntry.getY(), interiorEntry.getZ()));
        ops.add(set("minecraft:air", interiorEntry.getX(), interiorEntry.getY() + 1,
                interiorEntry.getZ()));
        openings.add(mainDoor);
        openings.add(ladder);
        BlockPos serviceDoor = null;
        if (features.contains("workshop")) {
            Direction side = s.front.getOpposite();
            serviceDoor = edge(s, side, floorY + 1, 0);
            openings.add(serviceDoor);
        }
        for (int storey = 0; storey < size.storeys; storey++) {
            int windowY = floorY + storey * size.floorHeight + 2;
            windows(ops, s, windowY, openings, palette.window, style.windowStride);
        }
        ops.add(door(palette.door, mainDoor, s.front));
        if (serviceDoor != null) {
            ops.add(door(palette.door, serviceDoor, s.front.getOpposite()));
        }

        ops.add(roof(palette, s, wallTop + 1, style, purpose));
        if (features.contains("dock") && s.shore != null) {
            dock(ops, s, palette);
        } else {
            entryTerrace(ops, s, palette, features.contains("porch"));
        }
        return ops;
    }

    private static void supports(JsonArray ops, Site s, String block) {
        int stepX = Math.max(2, (s.maxX - s.minX) / 3);
        int stepZ = Math.max(2, (s.maxZ - s.minZ) / 3);
        for (int x : samples(s.minX, s.maxX, stepX)) {
            for (int z : samples(s.minZ, s.maxZ, stepZ)) {
                int groundY = s.heights[x - s.minX][z - s.minZ];
                if (groundY < s.baseY) {
                    ops.add(line(block, x, groundY, z, x, s.baseY - 1, z));
                }
            }
        }
    }

    private static void frameShell(JsonArray ops, Site s, int floorY, int wallTop,
                                   Size size, String frame) {
        int stride = Math.max(3, Math.min(5, Math.min(size.width, size.depth) / 3));
        for (int x : samples(s.minX, s.maxX, stride)) {
            ops.add(line(frame, x, floorY + 1, s.minZ, x, wallTop, s.minZ));
            ops.add(line(frame, x, floorY + 1, s.maxZ, x, wallTop, s.maxZ));
        }
        for (int z : samples(s.minZ, s.maxZ, stride)) {
            ops.add(line(frame, s.minX, floorY + 1, z, s.minX, wallTop, z));
            ops.add(line(frame, s.maxX, floorY + 1, z, s.maxX, wallTop, z));
        }
        for (int storey = 1; storey <= size.storeys; storey++) {
            int y = floorY + storey * size.floorHeight;
            ops.add(line(frame, s.minX, y, s.minZ, s.maxX, y, s.minZ));
            ops.add(line(frame, s.minX, y, s.maxZ, s.maxX, y, s.maxZ));
            ops.add(line(frame, s.minX, y, s.minZ, s.minX, y, s.maxZ));
            ops.add(line(frame, s.maxX, y, s.minZ, s.maxX, y, s.maxZ));
        }
    }

    private static List<Room> partitionRooms(
            JsonArray ops, Site s, Size size, int floorY, String wall,
            List<RoomUse> functions, int serial) {
        int minX = s.minX + 1, maxX = s.maxX - 1;
        int minZ = s.minZ + 1, maxZ = s.maxZ - 1;
        boolean splitX = size.width >= 11;
        boolean splitZ = size.depth >= 13;
        int midX = Math.floorDiv(minX + maxX, 2);
        int midZ = Math.floorDiv(minZ + maxZ, 2);
        int wallTop = floorY + size.floorHeight - 1;

        List<int[]> xRanges = splitX
                ? List.of(new int[]{minX, midX - 1}, new int[]{midX + 1, maxX})
                : List.of(new int[]{minX, maxX});
        List<int[]> zRanges = splitZ
                ? List.of(new int[]{minZ, midZ - 1}, new int[]{midZ + 1, maxZ})
                : List.of(new int[]{minZ, maxZ});

        if (splitX) {
            ops.add(line(wall, midX, floorY + 1, minZ, midX, wallTop, maxZ));
            for (int[] range : zRanges) {
                int doorZ = Math.floorDiv(range[0] + range[1], 2);
                ops.add(set("minecraft:air", midX, floorY + 1, doorZ));
                ops.add(set("minecraft:air", midX, floorY + 2, doorZ));
            }
        }
        if (splitZ) {
            ops.add(line(wall, minX, floorY + 1, midZ, maxX, wallTop, midZ));
            for (int[] range : xRanges) {
                int doorX = Math.floorDiv(range[0] + range[1], 2);
                ops.add(set("minecraft:air", doorX, floorY + 1, midZ));
                ops.add(set("minecraft:air", doorX, floorY + 2, midZ));
            }
        }

        List<Room> rooms = new ArrayList<>();
        int index = serial;
        for (int[] xr : xRanges) for (int[] zr : zRanges) {
            rooms.add(new Room(xr[0], xr[1], zr[0], zr[1],
                    functions.get(Math.floorMod(index++, functions.size()))));
        }
        return List.copyOf(rooms);
    }

    private static void ceilingAndLight(JsonArray ops, Room room, int ceilingY,
                                        String frame, String light, Set<Long> reserved) {
        int x = room.centerX(), z = room.centerZ();
        ops.add(line(frame, room.minX, ceilingY, z, room.maxX, ceilingY, z));
        BlockPos lamp = new BlockPos(x, ceilingY - 1, z);
        if (!nearReserved(lamp, reserved, 1)) {
            ops.add(setProperties(light, x, ceilingY - 1, z,
                    Map.of("hanging", "true")));
        }
    }

    private static void furnish(JsonArray ops, Room room, int floorY,
                                Palette palette, Set<Long> reserved) {
        List<BlockPos> spots = List.of(
                new BlockPos(room.minX, floorY + 1, room.minZ),
                new BlockPos(room.maxX, floorY + 1, room.minZ),
                new BlockPos(room.minX, floorY + 1, room.maxZ),
                new BlockPos(room.maxX, floorY + 1, room.maxZ));
        List<String> blocks = switch (room.use) {
            case COMMON -> List.of(palette.study, palette.storage, palette.work, palette.seat);
            case REST -> List.of(palette.storage, palette.study, palette.work, palette.seat);
            case KITCHEN -> List.of(palette.work, "minecraft:furnace",
                    palette.storage, palette.storage);
            case WORK -> List.of(palette.work, "minecraft:stonecutter",
                    palette.storage, palette.study);
            case STORAGE -> List.of(palette.storage, palette.storage,
                    palette.storage, palette.study);
            case STUDY -> List.of(palette.study, palette.study,
                    "minecraft:lectern", palette.storage);
        };
        for (int i = 0; i < spots.size(); i++) {
            BlockPos spot = spots.get(i);
            if (!nearReserved(spot, reserved, 1)) {
                ops.add(set(blocks.get(i), spot.getX(), spot.getY(), spot.getZ()));
            }
        }
        int carpetX = room.centerX();
        int carpetZ = Math.max(room.minZ, room.centerZ() - 1);
        BlockPos carpet = new BlockPos(carpetX, floorY + 1, carpetZ);
        if (!nearReserved(carpet, reserved, 1)) {
            ops.add(set(palette.textile, carpet.getX(), carpet.getY(), carpet.getZ()));
        }
    }

    private static void verticalLink(JsonArray ops, BlockPos ladder, Direction facing,
                                     int floorY, Size size, String block) {
        int highestLanding = floorY + (size.storeys - 1) * size.floorHeight;
        for (int storey = 1; storey < size.storeys; storey++) {
            int landingY = floorY + storey * size.floorHeight;
            ops.add(set("minecraft:air", ladder.getX(), landingY, ladder.getZ()));
        }
        for (int y = floorY + 1; y <= highestLanding + 1; y++) {
            ops.add(setFacing(block, ladder.getX(), y, ladder.getZ(), facing));
        }
    }

    private static BlockPos ladderPosition(Site s, int y) {
        Direction back = s.front.getOpposite();
        return switch (back) {
            case NORTH -> new BlockPos(s.minX + 2, y, s.minZ + 1);
            case SOUTH -> new BlockPos(s.minX + 2, y, s.maxZ - 1);
            case WEST -> new BlockPos(s.minX + 1, y, s.minZ + 2);
            case EAST -> new BlockPos(s.maxX - 1, y, s.minZ + 2);
            default -> new BlockPos(s.minX + 2, y, s.minZ + 1);
        };
    }

    private static void reserveColumn(Set<Long> reserved, BlockPos pos, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            reserved.add(BlockPos.asLong(pos.getX(), y, pos.getZ()));
        }
    }

    private static boolean nearReserved(BlockPos pos, Set<Long> reserved, int horizontalRadius) {
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                if (reserved.contains(BlockPos.asLong(
                        pos.getX() + dx, pos.getY(), pos.getZ() + dz))) return true;
            }
        }
        return false;
    }

    private static void windows(JsonArray ops, Site s, int y, List<BlockPos> avoid,
                                String block, int stride) {
        for (int x : samples(s.minX + 2, s.maxX - 2, stride)) {
            window(ops, block, x, y, s.minZ, avoid);
            window(ops, block, x, y, s.maxZ, avoid);
        }
        for (int z : samples(s.minZ + 2, s.maxZ - 2, stride)) {
            window(ops, block, s.minX, y, z, avoid);
            window(ops, block, s.maxX, y, z, avoid);
        }
    }

    private static void window(JsonArray ops, String block, int x, int y, int z,
                               List<BlockPos> avoid) {
        boolean blocked = avoid.stream().anyMatch(cell ->
                Math.abs(x - cell.getX()) + Math.abs(z - cell.getZ()) <= 1);
        if (!blocked) ops.add(set(block, x, y, z));
    }

    private static List<Integer> samples(int min, int max, int stride) {
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        for (int value = min; value <= max; value += Math.max(1, stride)) values.add(value);
        values.add(max);
        return List.copyOf(values);
    }

    private static JsonObject roof(Palette palette, Site s, int y,
                                   StyleProfile style, String purpose) {
        JsonObject op = base("roof", palette.roof);
        bounds(op, s.minX, y, s.minZ, s.maxX, y, s.maxZ);
        int variant = Math.floorMod(
                Objects.hash(purpose.toLowerCase(Locale.ROOT), s.minX, s.minZ),
                style.roofShapes.size());
        String shape = style.roofShapes.get(variant);
        op.addProperty("roof_shape", shape);
        op.addProperty("roof_curve", shape.equals("shed") ? "straight" : style.roofCurve);
        op.addProperty("overhang", style.overhang);
        op.addProperty("corner_lift", style.cornerLift);
        op.addProperty("gable_block", palette.wall);
        op.addProperty("ridge_block", palette.accent);
        op.addProperty("eave_block", palette.frame);
        if (style.soffit) op.addProperty("soffit_block", palette.floor);
        op.addProperty("hollow", true);
        return op;
    }

    private static void entryTerrace(JsonArray ops, Site s, Palette palette, boolean porch) {
        int depth = porch ? 3 : 2;
        int halfWidth = porch ? 2 : 1;
        BlockPos a = edge(s, s.front, s.baseY, 1);
        BlockPos b = edge(s, s.front, s.baseY, depth);
        if (s.front.getAxis() == Direction.Axis.Z) {
            ops.add(box(palette.floor, a.getX() - halfWidth, s.baseY,
                    Math.min(a.getZ(), b.getZ()), a.getX() + halfWidth,
                    s.baseY, Math.max(a.getZ(), b.getZ()), false));
        } else {
            ops.add(box(palette.floor, Math.min(a.getX(), b.getX()), s.baseY,
                    a.getZ() - halfWidth, Math.max(a.getX(), b.getX()),
                    s.baseY, a.getZ() + halfWidth, false));
        }
        for (int lateral : new int[]{-halfWidth, halfWidth}) {
            BlockPos post = lateral(edge(s, s.front, s.baseY + 1, depth), s.front, lateral);
            ops.add(set(palette.railing, post.getX(), post.getY(), post.getZ()));
            ops.add(set(palette.light, post.getX(), post.getY() + 1, post.getZ()));
        }
    }

    private static void dock(JsonArray ops, Site s, Palette palette) {
        Shore shore = s.shore;
        int deckY = shore.waterY + 1;
        BlockPos a = edge(s, shore.direction, deckY, 1);
        BlockPos b = edge(s, shore.direction, deckY, shore.length);
        if (shore.direction.getAxis() == Direction.Axis.Z) {
            ops.add(box(palette.floor, a.getX() - 1, deckY,
                    Math.min(a.getZ(), b.getZ()), a.getX() + 1,
                    deckY, Math.max(a.getZ(), b.getZ()), false));
        } else {
            ops.add(box(palette.floor, Math.min(a.getX(), b.getX()), deckY,
                    a.getZ() - 1, Math.max(a.getX(), b.getX()),
                    deckY, a.getZ() + 1, false));
        }

        Set<Integer> supportRows = new LinkedHashSet<>();
        for (int d = 1; d <= shore.length; d += 3) supportRows.add(d);
        supportRows.add(shore.length);
        for (int d : supportRows) for (int side : new int[]{-1, 1}) {
            BlockPos column = lateral(edge(s, shore.direction, deckY, d),
                    shore.direction, side);
            int bottom = shore.supportFloor(d, side) + 1;
            if (bottom <= shore.waterY) {
                ops.add(line(palette.frame, column.getX(), bottom, column.getZ(),
                        column.getX(), shore.waterY, column.getZ()));
            }
        }
        for (int d = 2; d <= shore.length; d++) for (int side : new int[]{-1, 1}) {
            BlockPos rail = lateral(edge(s, shore.direction, deckY + 1, d),
                    shore.direction, side);
            ops.add(set(palette.railing, rail.getX(), rail.getY(), rail.getZ()));
        }
        for (int d : List.of(2, shore.length)) for (int side : new int[]{-1, 1}) {
            BlockPos lamp = lateral(edge(s, shore.direction, deckY + 2, d),
                    shore.direction, side);
            ops.add(set(palette.light, lamp.getX(), lamp.getY(), lamp.getZ()));
        }
    }

    private static BlockPos lateral(BlockPos center, Direction forward, int offset) {
        return forward.getAxis() == Direction.Axis.Z
                ? center.offset(offset, 0, 0)
                : center.offset(0, 0, offset);
    }

    private static BlockPos edge(Site s, Direction direction, int y, int out) {
        int x = (s.minX + s.maxX) / 2, z = (s.minZ + s.maxZ) / 2;
        return switch (direction) {
            case NORTH -> new BlockPos(x, y, s.minZ - out);
            case SOUTH -> new BlockPos(x, y, s.maxZ + out);
            case WEST -> new BlockPos(s.minX - out, y, z);
            case EAST -> new BlockPos(s.maxX + out, y, z);
            default -> new BlockPos(x, y, z);
        };
    }

    private static StyleProfile styleProfile(String requestedStyle, String purpose) {
        String language = ((requestedStyle == null ? "" : requestedStyle) + ' ' + purpose)
                .toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        StyleProfile selected = DEFAULT_STYLE;
        int bestScore = 0;
        for (StyleRule rule : STYLE_RULES) {
            int score = 0;
            for (String token : rule.tokens) if (language.contains(token)) score++;
            if (score > bestScore) {
                bestScore = score;
                selected = rule.profile;
            }
        }
        return selected;
    }

    private static List<RoomUse> roomFunctions(String purpose, Set<String> features) {
        LinkedHashSet<RoomUse> uses = new LinkedHashSet<>();
        features.stream().sorted().map(ROOM_LANGUAGE::get)
                .filter(Objects::nonNull).forEach(uses::add);
        String language = purpose.toLowerCase(Locale.ROOT);
        ROOM_LANGUAGE.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(entry -> language.contains(entry.getKey()))
                .map(Map.Entry::getValue).forEach(uses::add);
        uses.add(RoomUse.COMMON);
        uses.add(RoomUse.REST);
        uses.add(RoomUse.KITCHEN);
        uses.add(RoomUse.STORAGE);
        uses.add(RoomUse.WORK);
        uses.add(RoomUse.STUDY);
        return List.copyOf(uses);
    }

    private static JsonObject semanticContract(Size size, Set<String> features, Site site,
                                               int resolvedCells, String purpose) {
        int roomsPerStorey = (size.width >= 11 ? 2 : 1) * (size.depth >= 13 ? 2 : 1);
        int roomCount = roomsPerStorey * size.storeys;
        List<RoomUse> functions = roomFunctions(purpose, features);
        LinkedHashSet<String> zones = new LinkedHashSet<>();
        for (int i = 0; i < roomCount; i++) {
            zones.add(functions.get(Math.floorMod(i, functions.size()))
                    .name().toLowerCase(Locale.ROOT));
        }

        JsonObject contract = new JsonObject();
        contract.addProperty("bounded_cell_count", resolvedCells);
        contract.addProperty("storeys", size.storeys);
        contract.addProperty("interior_rooms", roomCount);
        contract.addProperty("interior_lights", roomCount);
        contract.addProperty("floor_slabs", size.storeys);
        contract.addProperty("exterior_doors", features.contains("workshop") ? 2 : 1);
        contract.addProperty("terrain_entry_approach", true);
        contract.addProperty("storeys_connected", true);
        contract.addProperty("cellar_access", features.contains("cellar"));
        contract.addProperty("traversability_requires_world_verification", true);
        JsonArray zoneArray = new JsonArray();
        zones.forEach(zoneArray::add);
        contract.add("functional_zones", zoneArray);

        JsonObject dock = new JsonObject();
        boolean hasDock = features.contains("dock") && site.shore != null;
        dock.addProperty("present", hasDock);
        if (hasDock) {
            int supportRows = (site.shore.length + 2) / 3;
            if ((site.shore.length - 1) % 3 != 0) supportRows++;
            dock.addProperty("length", site.shore.length);
            dock.addProperty("width", 3);
            dock.addProperty("support_columns", supportRows * 2);
            dock.addProperty("railed_sides", 2);
            dock.addProperty("exterior_lights", 4);
            dock.addProperty("connected_to_entry", true);
        }
        contract.add("dock", dock);
        return contract;
    }

    private static JsonObject traversabilityContract(Site site, Size size, Set<String> features) {
        int floorY = site.baseY;
        int highestFeetY = floorY + (size.storeys - 1) * size.floorHeight + 1;
        int approachDistance = features.contains("dock") && site.shore != null
                ? site.shore.length : features.contains("porch") ? 3 : 2;

        JsonObject contract = new JsonObject();
        contract.add("exteriorApproach",
                cellJson(edge(site, site.front, floorY + 1, approachDistance)));
        contract.add("entranceDoor", cellJson(edge(site, site.front, floorY + 1, 0)));
        contract.add("interiorEntry", cellJson(edge(site, site.front, floorY + 1, -1)));

        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", site.minX + 1);
        bounds.addProperty("minY", floorY + 1);
        bounds.addProperty("minZ", site.minZ + 1);
        bounds.addProperty("maxX", site.maxX - 1);
        bounds.addProperty("maxY", highestFeetY);
        bounds.addProperty("maxZ", site.maxZ - 1);
        contract.add("interiorBounds", bounds);

        JsonArray waypoints = new JsonArray();
        List<int[]> xRanges = roomRanges(site.minX + 1, site.maxX - 1, size.width >= 11);
        List<int[]> zRanges = roomRanges(site.minZ + 1, site.maxZ - 1, size.depth >= 13);
        for (int storey = 0; storey < size.storeys; storey++) {
            int feetY = floorY + storey * size.floorHeight + 1;
            for (int[] xr : xRanges) for (int[] zr : zRanges) {
                waypoints.add(cellJson(new BlockPos(
                        Math.floorDiv(xr[0] + xr[1], 2), feetY,
                        Math.floorDiv(zr[0] + zr[1], 2))));
            }
        }
        contract.add("floorWaypoints", waypoints);

        if (size.storeys > 1) {
            BlockPos ladder = ladderPosition(site, floorY + 1);
            JsonObject vertical = new JsonObject();
            vertical.addProperty("x", ladder.getX());
            vertical.addProperty("z", ladder.getZ());
            vertical.addProperty("bottomY", floorY + 1);
            vertical.addProperty("topY", highestFeetY);
            contract.add("verticalLink", vertical);
        }
        if (features.contains("dock") && site.shore != null) {
            JsonObject dock = new JsonObject();
            dock.add("houseSide", cellJson(edge(site, site.shore.direction, floorY + 1, 1)));
            dock.add("deckEnd", cellJson(
                    edge(site, site.shore.direction, floorY + 1, site.shore.length)));
            contract.add("dockPath", dock);
        }
        return contract;
    }

    private static List<int[]> roomRanges(int min, int max, boolean split) {
        if (!split) return List.of(new int[]{min, max});
        int middle = Math.floorDiv(min + max, 2);
        return List.of(new int[]{min, middle - 1}, new int[]{middle + 1, max});
    }

    private static JsonObject cellJson(BlockPos pos) {
        JsonObject cell = new JsonObject();
        cell.addProperty("x", pos.getX());
        cell.addProperty("y", pos.getY());
        cell.addProperty("z", pos.getZ());
        return cell;
    }

    private static Palette palette(LocalPlayer player, List<String> preferred, String policy) {
        Map<String, Integer> inventory = inventoryBlocks(player);
        Predicate<String> allowed = id -> !policy.equals("preserve_rare") || !rare(id);
        List<String> choices = preferred.stream().filter(SemanticBuildPlanner::validMaterial)
                .filter(allowed).toList();
        String wallBase = role(choices, inventory, allowed, SemanticBuildPlanner::basic, policy);
        if (wallBase == null) wallBase = "minecraft:oak_planks";
        String foundationBase = role(
                choices, inventory, allowed, id -> basic(id) && stone(id), policy);
        if (foundationBase == null) {
            foundationBase = stone(wallBase) ? wallBase : "minecraft:cobblestone";
        }
        String floor = role(choices, inventory, allowed, id -> basic(id) && wood(id), policy);
        if (floor == null) floor = wood(wallBase) ? wallBase : "minecraft:oak_planks";
        String frame = role(choices, inventory, allowed, SemanticBuildPlanner::frameBlock, policy);
        if (frame == null) frame = deriveWood(floor, "_log", "minecraft:oak_log");
        String roof = role(choices, inventory, allowed, SemanticBuildPlanner::slab, policy);
        if (roof == null) roof = deriveWood(floor, "_slab", "minecraft:oak_slab");
        String door = role(choices, inventory, allowed, SemanticBuildPlanner::doorBlock, policy);
        if (door == null) door = deriveWood(floor, "_door", "minecraft:oak_door");
        String window = role(choices, inventory, allowed, SemanticBuildPlanner::windowBlock, policy);
        if (window == null) window = "minecraft:glass_pane";
        String railing = role(
                choices, inventory, allowed, SemanticBuildPlanner::railingBlock, policy);
        if (railing == null) railing = deriveWood(floor, "_fence", "minecraft:oak_fence");
        String ladder = role(
                choices, inventory, allowed, SemanticBuildPlanner::ladderBlock, policy);
        if (ladder == null) ladder = "minecraft:ladder";
        String light = role(
                choices, inventory, allowed, SemanticBuildPlanner::hangingLight, policy);
        if (light == null) light = "minecraft:lantern";
        String storage = role(
                choices, inventory, allowed, SemanticBuildPlanner::storageBlock, policy);
        if (storage == null) storage = "minecraft:barrel";
        String work = role(
                choices, inventory, allowed, SemanticBuildPlanner::workBlock, policy);
        if (work == null) work = "minecraft:crafting_table";
        String study = role(
                choices, inventory, allowed, SemanticBuildPlanner::studyBlock, policy);
        if (study == null) study = "minecraft:bookshelf";
        String seat = role(
                choices, inventory, allowed, id -> path(id).endsWith("_stairs"), policy);
        if (seat == null) seat = deriveWood(floor, "_stairs", "minecraft:oak_stairs");
        String textile = role(
                choices, inventory, allowed, id -> path(id).endsWith("_carpet"), policy);
        if (textile == null) textile = "minecraft:gray_carpet";

        String foundation = textured(foundationBase, inventory, policy);
        String wall = textured(wallBase, inventory, policy);
        String accent = frame.equals(wallBase) ? foundationBase : frame;
        return new Palette(foundation, floor, wall, frame, roof, accent, door, window,
                railing, ladder, light, storage, work, study, seat, textile);
    }

    private static String role(List<String> preferred, Map<String, Integer> inventory,
                               Predicate<String> allowed, Predicate<String> kind, String policy) {
        String presentPreference = first(preferred, id -> inventory.containsKey(id) && kind.test(id));
        if (presentPreference != null) return presentPreference;
        if (policy.equals("specified")) return first(preferred, kind);
        String carried = best(inventory, allowed.and(kind));
        return carried != null ? carried : first(preferred, kind);
    }

    private static Map<String, Integer> inventoryBlocks(LocalPlayer player) {
        Map<String, Integer> out = new LinkedHashMap<>();
        int limit = Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) continue;
            out.merge(BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString(), stack.getCount(), Integer::sum);
        }
        return out;
    }

    private static String best(Map<String, Integer> inventory, Predicate<String> predicate) {
        return inventory.entrySet().stream().filter(e -> predicate.test(e.getKey()))
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static String first(List<String> ids, Predicate<String> predicate) {
        return ids.stream().filter(predicate).findFirst().orElse(null);
    }

    private static Block registered(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return null;
        Block block = BuiltInRegistries.BLOCK.get(key);
        return BuiltInRegistries.BLOCK.getKey(block).equals(key) ? block : null;
    }

    private static boolean validMaterial(String id) {
        Block block = registered(id);
        return block != null && block != Blocks.AIR;
    }

    private static boolean basic(String id) {
        Block block = registered(id);
        return block != null && block != Blocks.AIR && !(block instanceof SlabBlock)
                && !(block instanceof DoorBlock) && !(block instanceof TransparentBlock)
                && !(block instanceof IronBarsBlock) && !(block instanceof CropBlock)
                && !(block instanceof StemBlock) && !(block instanceof AttachedStemBlock)
                && !block.defaultBlockState().hasBlockEntity()
                && !storageBlock(id) && !workBlock(id) && !studyBlock(id)
                && block.defaultBlockState().isCollisionShapeFullBlock(
                        net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static boolean slab(String id) { return registered(id) instanceof SlabBlock; }
    private static boolean doorBlock(String id) { return registered(id) instanceof DoorBlock; }
    private static boolean windowBlock(String id) {
        Block block = registered(id);
        ResourceLocation key = ResourceLocation.tryParse(id);
        return block instanceof TransparentBlock || block instanceof IronBarsBlock
                || key != null && key.getPath().contains("glass_pane");
    }

    private static boolean frameBlock(String id) {
        String path = path(id);
        return path.endsWith("_log") || path.endsWith("_wood")
                || path.endsWith("_stem") || path.endsWith("_hyphae")
                || path.startsWith("stripped_");
    }

    private static boolean railingBlock(String id) {
        String path = path(id);
        return path.endsWith("_fence") || path.endsWith("_wall");
    }

    private static boolean ladderBlock(String id) {
