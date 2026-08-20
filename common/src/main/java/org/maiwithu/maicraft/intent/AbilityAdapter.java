package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.core.scan.TargetIndex;

import java.util.List;
import java.util.UUID;

/** Compiles semantic abilities into existing internal tools or native intent steps. */
final class AbilityAdapter {

    private AbilityAdapter() {}

    static IntentAction adapt(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        return adapt(goal, player, runtime, null);
    }

    static IntentAction adapt(
            Goal goal, LocalPlayer player, IntentRuntime runtime, UUID continuationToken) {
        if (GeneralAbilityAdapter.supports(goal.ability())) {
            Goal executable = goal;
            if (GeneralAbilityAdapter.EQUIP.equals(goal.ability())) {
                JsonObject parameters = goal.parameters();
                if (parameters.has("equipment_location") && !parameters.has("slot")) {
                    parameters.add("slot", parameters.get("equipment_location").deepCopy());
                }
                parameters.remove("equipment_location");
                executable = goal.withParameters(parameters);
            }
            return GeneralAbilityAdapter.adapt(executable, player, runtime);
        }
        return switch (goal.ability()) {
            case "maicraft:remember_place" -> remember(goal, player, runtime);
            case "maicraft:sleep" -> sleep(goal, player);
            case "maicraft:travel" -> travel(goal, player, runtime);
            case "maicraft:travel_dimension" -> travelDimension(goal);
            case "maicraft:find_structure" -> findStructure(goal);
            case "maicraft:reach_milestone" -> reachMilestone(goal);
            case "maicraft:defeat_ender_dragon" -> defeatEnderDragon(goal);
            case "maicraft:obtain_elytra" -> obtainElytra(goal);
            case "maicraft:craft" -> craft(goal);
            case "maicraft:cook" -> cook(goal);
            case "maicraft:trade" -> trade(goal);
            case "maicraft:build" -> build(goal, player, runtime);
            case "maicraft:light_area" -> lightArea(goal, player, runtime);
            case "maicraft:connect_mechanical_power" ->
                    connectPower(goal, player, runtime, continuationToken);
            case "maicraft:acquire_items" -> acquire(goal);
            case "maicraft:wait_for_condition" -> waitFor(goal, player);
            default -> decision(goal,
                    "No semantic adapter is registered for " + goal.ability() + ". Choose explicitly.",
                    List.of(
                            option("replace_goal", "Provide details.goal as one supported semantic Goal."),
                            option("skip", "Skip this step and continue the sequence."),
                            option("cancel", "Cancel the whole task.")));
        };
    }

    static IntentAction fromAnswer(Goal goal, IntentTaskRecord.DecisionAnswer answer,
                                   LocalPlayer player, IntentRuntime runtime) {
        return fromAnswer(goal, answer, player, runtime, null);
    }

    static IntentAction fromAnswer(
            Goal goal, IntentTaskRecord.DecisionAnswer answer,
            LocalPlayer player, IntentRuntime runtime, UUID continuationToken) {
        JsonObject details = answer.details();
        JsonObject updates = details.has("parameters") && details.get("parameters").isJsonObject()
                ? details.getAsJsonObject("parameters")
                : details;
        JsonObject merged = goal.parameters();
        merge(merged, updates);
        return adapt(goal.withParameters(merged), player, runtime, continuationToken);
    }

    private static IntentAction remember(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        JsonObject parameters = goal.parameters();
        String label = string(parameters, "label");
        if (label == null && goal.target() != null) label = goal.target().label();
        if (label == null || label.isBlank()) label = goal.outcome();
        Goal.WorldPosition position = rememberPosition(goal, player, runtime);
        if (position == null) {
            return decision(goal,
                    "remember_place needs current_place, coordinates, or an existing landmark target.",
                    List.of(option("skip", "Do not create a landmark."),
                            option("cancel", "Cancel the task.")));
        }
        return new IntentAction.Remember(label, position);
    }

    private static IntentAction sleep(Goal goal, LocalPlayer player) {
        java.util.Set<Block> bedBlocks = BuiltInRegistries.BLOCK
                .getTag(BlockTags.BEDS)
                .map(tag -> tag.stream().map(holder -> holder.value())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                .orElseGet(java.util.Set::of);
        if (!bedBlocks.isEmpty()) {
            TargetIndex.register(player.clientLevel, bedBlocks);
            TargetIndex.Result beds;
            try {
                beds = TargetIndex.query(
                        player.clientLevel,
                        player.blockPosition(),
                        bedBlocks,
                        1,
                        2,
                        256);
            } finally {
                TargetIndex.unregister(player.clientLevel, bedBlocks);
            }
            if (!beds.hits().isEmpty()) {
                if (!WorldTimeSemantics.canAttemptSleep(player.level())) {
                    return waitForNightDecision(goal);
                }
                Block bed = player.clientLevel.getBlockState(beds.hits().getFirst()).getBlock();
                String bedId = BuiltInRegistries.BLOCK.getKey(bed).toString();
                JsonObject travel = new JsonObject();
                travel.addProperty("block", bedId);
                return new IntentAction.Chain(List.of(
                        new IntentAction.Tool("goto", travel.toString()),
                        new IntentAction.Tool("sleep", "{}")));
            }
        }

        String carriedBed = inventoryBed(player);
        if (carriedBed != null) {
            if (!WorldTimeSemantics.canAttemptSleep(player.level())) {
                return waitForNightDecision(goal);
            }
            BedSite site = nearbyBedSite(player);
            if (site != null) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set");
                op.addProperty("block_id", carriedBed);
                op.addProperty("x", site.foot().getX());
                op.addProperty("y", site.foot().getY());
                op.addProperty("z", site.foot().getZ());
                op.addProperty("facing", site.facing().getName());
                JsonArray ops = new JsonArray();
                ops.add(op);
                JsonObject build = new JsonObject();
                build.add("ops", ops);
                build.addProperty("replace_existing", false);
                JsonObject travel = new JsonObject();
                travel.addProperty("block", carriedBed);
                return new IntentAction.Chain(List.of(
                        new IntentAction.Tool("build", build.toString()),
                        new IntentAction.Tool("goto", travel.toString()),
                        new IntentAction.Tool("sleep", "{}")));
            }
            return decision(goal,
                    "There is a bed in inventory, but no safe loaded two-block placement site nearby.",
                    List.of(
                            option("recover", "Provide a semantic travel prerequisite to reach a safe open area."),
                            option("skip", "Do not sleep."),
                            option("cancel", "Cancel the whole task.")));
        }

        return decision(goal,
                "No bed is visible in loaded terrain and no bed is in inventory. "
                        + "Choose a semantic prerequisite before MaiCraft changes the world.",
                List.of(
                        option("recover", "Provide details.goal, for example acquiring any usable bed."),
                        option("skip", "Continue without sleeping."),
                        option("cancel", "Cancel the whole task.")));
    }

    private static IntentAction waitForNightDecision(Goal goal) {
        return decision(goal,
                "A usable bed is available, but the observed world is currently daytime and not thundering. MaiCraft will not click it repeatedly or pretend sleep succeeded.",
                List.of(
                        option("recover", "Provide details.goal to wait for the observable night condition, then resume sleep."),
                        option("skip", "Continue without sleeping."),
                        option("cancel", "Cancel the whole task.")));
    }

    private static String inventoryBed(LocalPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem item
                    && item.getBlock() instanceof BedBlock) {
                return BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString();
            }
        }
        return null;
    }

    private static BedSite nearbyBedSite(LocalPlayer player) {
        BlockPos origin = player.blockPosition();
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    int y = ClientSurfaceHeight.motionBlockingNoLeaves(
                            player.clientLevel, x, z);
                    BlockPos foot = new BlockPos(x, y, z);
                    for (Direction facing : Direction.Plane.HORIZONTAL) {
                        BlockPos head = foot.relative(facing);
                        if (validBedCell(player, foot) && validBedCell(player, head)) {
                            return new BedSite(foot, facing);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean validBedCell(LocalPlayer player, BlockPos cell) {
        if (!player.clientLevel.isLoaded(cell)
                || !player.clientLevel.getBlockState(cell).canBeReplaced()
                || !player.clientLevel.getBlockState(cell.above()).getCollisionShape(
                        player.clientLevel, cell.above()).isEmpty()) {
            return false;
        }
        BlockPos support = cell.below();
        return player.clientLevel.isLoaded(support)
                && player.clientLevel.getBlockState(support)
                        .isFaceSturdy(player.clientLevel, support, Direction.UP);
    }

    private record BedSite(BlockPos foot, Direction facing) {}

    private static IntentAction travel(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        JsonObject parameters = new JsonObject();
        String block = string(goal.parameters(), "block_id");
        if (block == null) block = string(goal.parameters(), "block");
        if (block != null) {
            parameters.addProperty("block", block);
        } else {
            Goal.WorldPosition position = position(goal, player, runtime);
            if (position == null) {
                Goal.SemanticTarget semantic = goal.target();
                if (isNamedPlace(semantic)) {
                    return unresolvedNamedPlaceDecision(
                            goal, semantic, player, runtime, "Travel");
                }
                if (semantic != null && "prior_result".equals(semantic.kind())) {
                    return decision(goal,
                            "Travel could not bind prior_result to one authoritative earlier successful place. "
                                    + "MaiCraft refused to guess a destination or start an unrelated exploration.",
                            List.of(
                                    option("recover", "Provide details.goal to produce or remember the intended place first."),
                                    option("replace_goal", "Use a remembered landmark, current_place, or an explicit coast/biome discovery goal."),
                                    option("cancel", "Cancel without moving.")));
                }
                String exploreTarget = exploreTarget(goal);
                if (exploreTarget == null) {
                    return decision(goal,
                            "Travel needs coordinates, a remembered landmark, block_id, coast, biome id or biome tag.",
                            List.of(option("replace_goal", "Provide a semantic destination, never waypoints."),
                                    option("cancel", "Cancel the task.")));
                }
                JsonObject explore = new JsonObject();
                explore.addProperty("target", exploreTarget);
                explore.addProperty("max_distance",
                        integer(goal.parameters(), "max_distance", 768, 64, 2_048));
                if (bool(goal.parameters(), "may_alter_terrain", false)
                        || bool(goal.preferences(), "may_alter_terrain", false)) {
                    explore.addProperty("may_alter_terrain", true);
                }
                return new IntentAction.Tool("explore", explore.toString());
            }
            parameters.addProperty("x", position.x());
            if (bool(goal.parameters(), "exact", false)) parameters.addProperty("y", position.y());
            parameters.addProperty("z", position.z());
        }
        if (bool(goal.parameters(), "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            parameters.addProperty("may_alter_terrain", true);
        }
        return new IntentAction.Tool("goto", parameters.toString());
    }

    private static IntentAction travelDimension(Goal goal) {
        JsonObject parameters = goal.parameters();
        String destination = string(parameters, "destination_dimension");
        Goal.SemanticTarget target = goal.target();
        if (destination == null && target != null && target.position() != null) {
            destination = target.position().dimension();
        }
        if (destination == null && target != null && target.label() != null
                && ResourceLocation.tryParse(target.label()) != null) {
            destination = target.label();
        }
        if (destination == null || ResourceLocation.tryParse(destination) == null) {
            return decision(goal,
                    "Cross-dimension travel needs a namespaced destination_dimension; portal cells and the route remain MaiCraft's responsibility.",
                    List.of(
                            option("retry", "Retry with details.parameters.destination_dimension."),
                            option("skip", "Do not change dimensions."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("destination_dimension", destination);
        args.addProperty("max_search_radius",
                integer(parameters, "max_search_radius", 128, 16, 512));
        if (bool(parameters, "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            args.addProperty("may_alter_terrain", true);
        }
        return new IntentAction.Tool("dimension_travel", args.toString());
    }

    private static IntentAction findStructure(Goal goal) {
        JsonObject parameters = goal.parameters();
        String structure = string(parameters, "structure_id");
        Goal.SemanticTarget target = goal.target();
        if (structure == null && target != null && target.label() != null
                && ResourceLocation.tryParse(target.label()) != null) {
            structure = target.label();
        }
        if (structure == null || ResourceLocation.tryParse(structure) == null) {
            return decision(goal,
                    "Physical structure discovery needs a namespaced structure_id; MaiCraft chooses every search segment and observation.",
                    List.of(
                            option("retry", "Retry with details.parameters.structure_id."),
                            option("skip", "Do not search for a structure."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("structure_id", structure);
        args.addProperty("max_distance",
                integer(parameters, "max_distance", 4_096, 64, 4_096));
        args.addProperty("reach_structure",
                !parameters.has("reach_structure")
                        || bool(parameters, "reach_structure", true));
        if (bool(parameters, "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            args.addProperty("may_alter_terrain", true);
        }
        if (bool(parameters, "allow_rare_consumables", false)) {
            args.addProperty("allow_rare_consumables", true);
        }
        return new IntentAction.Tool("structure_search", args.toString());
    }

    private static IntentAction defeatEnderDragon(Goal goal) {
        JsonObject parameters = goal.parameters();
        if (!bool(parameters, "allow_combat", false)) {
            return decision(goal,
                    "Defeating the Ender Dragon destroys crystals and kills a living boss. Confirm that combat is intended before MaiCraft acts.",
                    List.of(
                            option("retry", "Retry with details.parameters.allow_combat=true if this fight is intended."),
                            option("skip", "Leave the dragon encounter untouched."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("allow_combat", true);
        if (bool(parameters, "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            args.addProperty("may_alter_terrain", true);
        }
        for (String key : List.of("minimum_health", "protected_labels")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        return new IntentAction.Tool("dragon_fight", args.toString());
    }

    private static IntentAction reachMilestone(Goal goal) {
        JsonObject parameters = goal.parameters();
        String milestone = string(parameters, "milestone");
        Goal.SemanticTarget target = goal.target();
        if (milestone == null && target != null) milestone = target.label();
        if (!List.of("nether", "stronghold", "defeat_dragon", "elytra")
                .contains(milestone)) {
            return decision(goal,
                    "reach_milestone needs milestone=nether, stronghold, defeat_dragon or elytra. MaiCraft derives the private prerequisite chain.",
                    List.of(
                            option("retry", "Retry with details.parameters.milestone."),
                            option("cancel", "Cancel progression.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("milestone", milestone);
        args.addProperty("max_search_distance",
                integer(parameters, "max_search_distance", 4_096, 128, 4_096));
        args.addProperty("max_portal_search_radius",
                integer(parameters, "max_portal_search_radius", 128, 16, 512));
        if (bool(parameters, "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            args.addProperty("may_alter_terrain", true);
        }
        for (String key : List.of(
                "minimum_health", "allow_combat", "allow_rare_consumables",
                "allowed_sources", "material_policy", "protected_labels")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        return new IntentAction.Tool("reach_milestone", args.toString());
    }

    private static IntentAction obtainElytra(Goal goal) {
        JsonObject parameters = goal.parameters();
        JsonObject args = new JsonObject();
        args.addProperty("max_search_distance",
                integer(parameters, "max_search_distance", 2_048, 128, 4_096));
        if (bool(parameters, "may_alter_terrain", false)
                || bool(goal.preferences(), "may_alter_terrain", false)) {
            args.addProperty("may_alter_terrain", true);
        }
        if (bool(parameters, "allow_combat", false)) {
            args.addProperty("allow_combat", true);
        }
        if (bool(parameters, "allow_rare_consumables", false)) {
            args.addProperty("allow_rare_consumables", true);
        }
        if (parameters.has("protected_labels")) {
            args.add("protected_labels", parameters.get("protected_labels").deepCopy());
        }
        return new IntentAction.Tool("obtain_elytra", args.toString());
    }

    private static String exploreTarget(Goal goal) {
        String target = string(goal.parameters(), "semantic_target");
        if (target == null) target = string(goal.parameters(), "biome_id");
        if (target == null) {
            String tag = string(goal.parameters(), "biome_tag");
            if (tag != null) target = tag.startsWith("#") ? tag : "#" + tag;
        }
        Goal.SemanticTarget semantic = goal.target();
        if (target == null && semantic != null && "nearest".equals(semantic.kind())) {
            target = semantic.label();
        }
        if (target == null && semantic != null && "nearest".equals(semantic.kind())) {
            target = semantic.relation();
        }
        return target == null || target.isBlank() ? null : target.strip();
    }

    private static IntentAction craft(Goal goal) {
        JsonObject parameters = goal.parameters();
        String item = itemId(goal, parameters);
        if (item == null) {
            return decision(goal, "Craft needs a namespaced item_id.",
                    List.of(option("retry", "Retry with details.parameters.item_id and count."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("item_id", item);
        args.addProperty("count", integer(parameters, "count", 1, 1, 256));
        JsonArray sources = new JsonArray();
        sources.add("inventory");
        sources.add("craft");
        args.add("allowed_sources", sources);
        return new IntentAction.Tool("acquire_items", args.toString());
    }

    private static IntentAction cook(Goal goal) {
        JsonObject parameters = goal.parameters();
        String item = itemId(goal, parameters);
        if (item == null) {
            return decision(goal, "Cook needs a namespaced item_id.",
                    List.of(option("retry",
                                    "Retry with details.parameters.item_id and count."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("item_id", item);
        args.addProperty("count", integer(parameters, "count", 1, 1, 256));
        for (String key : List.of(
                "recipe_preference", "allowed_fuels", "allowed_sources",
                "allow_harm", "protected_labels")) {
            if (parameters.has(key)) {
                args.add(key, parameters.get(key).deepCopy());
            }
        }
        return new IntentAction.Tool("cook", args.toString());
    }

    private static IntentAction trade(Goal goal) {
        JsonObject parameters = goal.parameters();
        String item = itemId(goal, parameters);
        if (item == null) {
            return decision(goal, "Trade needs a namespaced item_id for the desired output.",
                    List.of(
                            option("retry", "Retry with details.parameters.item_id and count."),
                            option("cancel", "Cancel the trade.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("item_id", item);
        args.addProperty("count", integer(parameters, "count", 1, 1, 256));
        for (String key : List.of(
                "merchant_kind", "allowed_payment_items", "protected_labels", "radius")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        return new IntentAction.Tool("trade_items", args.toString());
    }

    private static IntentAction build(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        return SemanticBuildPlanner.plan(goal, player, runtime);
    }

    private static IntentAction lightArea(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        JsonObject parameters = goal.parameters();
        Goal.WorldPosition center = position(goal, player, runtime);
        Goal.SemanticTarget target = goal.target();
        boolean semanticPlace = target != null
                && ("area".equals(target.kind()) || "landmark".equals(target.kind()));
        if (center == null && semanticPlace) {
            return unresolvedNamedPlaceDecision(
                    goal, target, player, runtime, "Lighting");
        }
        if (center == null && target != null && "prior_result".equals(target.kind())) {
            return decision(goal,
                    "Lighting prior_result did not match one authoritative earlier successful "
                            + "place. MaiCraft refused to illuminate the player's current area instead.",
                    List.of(
                            option("recover", "Provide details.goal to produce or remember the intended area first."),
                            option("replace_goal", "Use current_place or an exact label from perceive(view=landmarks)."),
                            option("cancel", "Cancel without changing any area.")));
        }
        if (center == null) {
            return decision(goal,
                    "Lighting needs current_place, same-dimension coordinates, a resolved landmark, or an earlier verified result.",
                    List.of(option("replace_goal", "Provide one unambiguous semantic target."),
                            option("cancel", "Cancel the task.")));
        }
        boolean explicitRadius = parameters.has("radius")
                && !parameters.get("radius").isJsonNull();
        boolean resolveLoadedComponent = (target != null && "area".equals(target.kind()))
                || !explicitRadius;
        String coverage = string(parameters, "coverage");
        if (coverage == null) coverage = "most";
        if (!List.of("all", "most", "crop_growth", "player_visibility").contains(coverage)) {
            return decision(goal,
                    "Unsupported lighting coverage: " + coverage,
                    List.of(option("replace_goal", "Choose all, most, crop_growth or player_visibility."),
                            option("cancel", "Cancel the task.")));
        }
        if (parameters.has("placement_preference")
                && (!parameters.get("placement_preference").isJsonPrimitive()
                        || !parameters.getAsJsonPrimitive("placement_preference").isString())) {
            return decision(goal,
                    "Lighting placement_preference must be a string enum value.",
                    List.of(option("replace_goal", "Choose coverage_optimal, central_unplanted or unobtrusive."),
                            option("cancel", "Cancel the task.")));
        }
        String placementPreference = string(parameters, "placement_preference");
        if (placementPreference == null) placementPreference = "coverage_optimal";
        if (!List.of("coverage_optimal", "central_unplanted", "unobtrusive")
                .contains(placementPreference)) {
            return decision(goal,
                    "Unsupported lighting placement_preference: " + placementPreference,
                    List.of(option("replace_goal", "Choose coverage_optimal, central_unplanted or unobtrusive."),
                            option("cancel", "Cancel the task.")));
        }
        if ("central_unplanted".equals(placementPreference)
                && !"crop_growth".equals(coverage)) {
            return decision(goal,
                    "central_unplanted placement_preference is only valid for crop_growth coverage.",
                    List.of(option("replace_goal", "Use crop_growth coverage or choose another placement preference."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        args.addProperty("center_x", center.x());
        args.addProperty("center_y", center.y());
        args.addProperty("center_z", center.z());
        if (explicitRadius) {
            args.addProperty("radius", integer(parameters, "radius", 1, 1,
                    org.maiwithu.maicraft.core.task.lighting.SemanticLightAreaTaskRecord.MAX_EXPLICIT_RADIUS));
        }
        args.addProperty("coverage", coverage);
        args.addProperty("placement_preference", placementPreference);
        args.addProperty("minimum_light", integer(parameters, "minimum_light",
                "crop_growth".equals(coverage) ? 9 : 8, 1, 15));
        args.addProperty("resolve_loaded_component", resolveLoadedComponent);
        if (target != null && target.label() != null && !target.label().isBlank()) {
            args.addProperty("semantic_target", target.label());
        }
        for (String key : List.of("style", "block_id", "light_preferences",
                "material_policy", "allowed_sources", "allow_harm",
                "protected_labels", "max_placements")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        if (!args.has("protected_labels") && parameters.has("preserve")
                && parameters.get("preserve").isJsonArray()) {
            args.add("protected_labels", parameters.get("preserve").deepCopy());
        }
        return new IntentAction.Tool("light_area", args.toString());
    }

    private static IntentAction connectPower(
            Goal goal, LocalPlayer player, IntentRuntime runtime, UUID continuationToken) {
        JsonObject parameters = goal.parameters();
        String sourceLabel = string(parameters, "source_label");
        String destinationLabel = string(parameters, "target_label");
        Goal.WorldPosition source = namedPosition(sourceLabel, player, runtime);
        Goal.WorldPosition destination = position(goal, player, runtime);
        if (destination == null) destination = namedPosition(destinationLabel, player, runtime);
        if (source == null || destination == null) {
            String sourceIssue = source == null
                    ? namedEndpointIssue("source", sourceLabel, player, runtime)
                    : null;
            String destinationIssue = destination == null
                    ? destinationEndpointIssue(goal.target(), destinationLabel, player, runtime)
                    : null;
            String issue = sourceIssue == null ? destinationIssue
                    : destinationIssue == null ? sourceIssue
                    : sourceIssue + "; " + destinationIssue;
            return decision(goal,
                    "Mechanical connection paused before survey: " + issue
                            + ". MaiCraft refused to invent endpoint coordinates.",
                    List.of(option("recover", "Provide details.goal to reach and remember a verifiable endpoint; an arbitrary human label may need the player to identify it."),
                            option("replace_goal", "Use two same-dimension remembered labels or one authoritative prior_result destination."),
                            option("skip", "Leave the networks unchanged."),
                            option("cancel", "Cancel the whole task.")));
        }
        String requested = string(parameters, "transmission");
        String transmission;
        if (requested == null || "auto".equals(requested)) transmission = "auto";
        else if ("chain_drive".equals(requested) || "encased_chain_drive".equals(requested)) {
            transmission = "encased_chain_drive";
        } else {
            return decision(goal, "Unsupported mechanical transmission: " + requested,
                    List.of(option("replace_goal", "Choose automatic or chain_drive transmission."),
                            option("cancel", "Cancel the whole task.")));
        }

        JsonObject args = new JsonObject();
        args.addProperty("source_name", sourceLabel == null ? "source" : sourceLabel);
        args.addProperty("source_x", source.x());
        args.addProperty("source_y", source.y());
        args.addProperty("source_z", source.z());
        args.addProperty("destination_name", destinationLabel == null ? "destination" : destinationLabel);
        args.addProperty("destination_x", destination.x());
        args.addProperty("destination_y", destination.y());
        args.addProperty("destination_z", destination.z());
        args.addProperty("transmission", transmission);
        args.addProperty("allow_free_receiver", bool(parameters, "allow_new_receiver", false));
        if (continuationToken != null) {
            args.addProperty("continuation_token", continuationToken.toString());
        }
        for (String key : List.of(
                "material_policy", "allowed_sources", "allow_harm", "protected_labels")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        return new IntentAction.Tool("connect_mechanical_power", args.toString());
    }

    private static IntentAction acquire(Goal goal) {
        JsonObject parameters = goal.parameters();
        boolean hasItem = parameters.has("item_id")
                && parameters.get("item_id").isJsonPrimitive()
                && !parameters.get("item_id").getAsString().isBlank();
        boolean hasAlternatives = parameters.has("item_ids")
                && parameters.get("item_ids").isJsonArray()
                && !parameters.getAsJsonArray("item_ids").isEmpty();
        boolean hasTag = parameters.has("item_tag")
                && parameters.get("item_tag").isJsonPrimitive()
                && !parameters.get("item_tag").getAsString().isBlank();
        boolean hasTags = parameters.has("item_tags")
                && parameters.get("item_tags").isJsonArray()
                && !parameters.getAsJsonArray("item_tags").isEmpty();
        if (!hasItem && !hasAlternatives && !hasTag && !hasTags) {
            return decision(goal, "Acquire items needs an item or semantic item tag selector.",
                    List.of(option("retry", "Retry with item_id/item_ids or item_tag/item_tags."),
                            option("cancel", "Cancel the task.")));
        }
        JsonObject args = new JsonObject();
        for (String key : List.of(
                "item_id", "item_ids", "item_tag", "item_tags", "count",
                "allowed_sources", "allow_harm",
                "protected_labels", "radius",
                "source_hint")) {
            if (parameters.has(key)) args.add(key, parameters.get(key).deepCopy());
        }
        return new IntentAction.Tool("acquire_items", args.toString());
    }

    private static IntentAction waitFor(Goal goal, LocalPlayer player) {
        JsonObject parameters = goal.parameters();
        int seconds = integer(parameters, "after_s", 1, 0, 3600);
        String condition = string(parameters, "condition");
        if (condition == null) condition = "elapsed";
        if (!List.of("elapsed", "day", "night", "health_full", "not_hungry").contains(condition)) {
            return decision(goal, "Unsupported wait condition: " + condition,
                    List.of(option("skip", "Skip this wait."),
                            option("cancel", "Cancel the task.")));
        }
        return new IntentAction.Wait(condition, player.level().getGameTime() + seconds * 20L);
    }

    private static IntentAction.Decision decision(Goal goal, String question,
                                                   List<IntentTaskRecord.DecisionOption> options) {
        JsonObject context = new JsonObject();
        context.addProperty("ability", goal.ability());
        context.addProperty("outcome", goal.outcome());
        return new IntentAction.Decision(new IntentTaskRecord.DecisionSnapshot(
                UUID.randomUUID(), question, options, context.toString()));
    }

    private static IntentTaskRecord.DecisionOption option(String choice, String description) {
        return new IntentTaskRecord.DecisionOption(choice, description);
    }

    private static Goal.WorldPosition position(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        Goal.SemanticTarget target = goal.target();
        if (target == null) return null;
        if ("current_place".equals(target.kind())) return currentPosition(player);
        if ("coordinates".equals(target.kind())) return sameDimension(target.position(), player)
                ? target.position() : null;
        if ("landmark".equals(target.kind()) || "area".equals(target.kind())) {
            IntentRuntime.Landmark landmark = runtime.landmark(target.label());
            return landmark == null || !sameDimension(landmark.position(), player)
                    ? null : landmark.position();
        }
        return null;
    }

    private static Goal.WorldPosition namedPosition(
            String label, LocalPlayer player, IntentRuntime runtime) {
        if (label == null || label.isBlank()) return null;
        if ("current_place".equals(label)) return currentPosition(player);
        IntentRuntime.Landmark landmark = runtime.landmark(label);
        return landmark == null || !sameDimension(landmark.position(), player)
                ? null : landmark.position();
    }

    private static boolean isNamedPlace(Goal.SemanticTarget target) {
        return target != null
                && ("landmark".equals(target.kind()) || "area".equals(target.kind()));
    }

    private static IntentAction unresolvedNamedPlaceDecision(
            Goal goal, Goal.SemanticTarget target, LocalPlayer player,
            IntentRuntime runtime, String action) {
        String label = target.label() == null || target.label().isBlank()
                ? "the requested named place" : "'" + target.label() + "'";
        IntentRuntime.Landmark landmark = target.label() == null
                ? null : runtime.landmark(target.label());
        String reason;
        if (landmark == null) {
            reason = label + " is not remembered for this world";
        } else if (!sameDimension(landmark.position(), player)) {
            reason = label + " is remembered in another dimension";
        } else {
            reason = label + " could not be resolved from authoritative semantic memory";
        }
        return decision(goal,
                action + " target " + reason + ". MaiCraft refused to use the current position, "
                        + "guess coordinates, or reinterpret the label as an exploration target.",
                List.of(
                        option("recover", "Provide details.goal to reach and remember a verifiable place; an arbitrary ownership label may need the player to identify it."),
                        option("replace_goal", "Choose an exact label from perceive(view=landmarks), current_place, or an explicit coast/biome discovery."),
                        option("cancel", "Cancel without moving or changing the world.")));
    }

    private static String namedEndpointIssue(
            String endpoint, String label, LocalPlayer player, IntentRuntime runtime) {
        if (label == null || label.isBlank()) return endpoint + "_label is missing";
        if ("current_place".equals(label)) return endpoint + " current_place is unavailable";
        IntentRuntime.Landmark landmark = runtime.landmark(label);
        if (landmark == null) return endpoint + " label '" + label + "' is not remembered";
        if (!sameDimension(landmark.position(), player)) {
            return endpoint + " label '" + label + "' belongs to another dimension";
        }
        return endpoint + " label '" + label + "' has no authoritative position";
    }

    private static String destinationEndpointIssue(
            Goal.SemanticTarget target, String label,
            LocalPlayer player, IntentRuntime runtime) {
        if (target != null && "prior_result".equals(target.kind())) {
            return "destination prior_result did not match one authoritative earlier successful place";
        }
        if (isNamedPlace(target)) {
            return namedEndpointIssue("destination", target.label(), player, runtime);
        }
        return namedEndpointIssue("destination", label, player, runtime);
    }

    private static Goal.WorldPosition rememberPosition(
        Goal goal, LocalPlayer player, IntentRuntime runtime) {
        Goal.SemanticTarget target = goal.target();
        if (target == null) return null;
        if ("current_place".equals(target.kind())) return currentPosition(player);
        if ("coordinates".equals(target.kind())) return target.position();
        if ("landmark".equals(target.kind()) || "area".equals(target.kind())) {
            IntentRuntime.Landmark landmark = runtime.landmark(target.label());
            return landmark == null ? null : landmark.position();
        }
        return null;
    }

    private static boolean sameDimension(Goal.WorldPosition position, LocalPlayer player) {
        return position != null && (position.dimension() == null
                || position.dimension().equals(player.level().dimension().location().toString()));
    }

    private static Goal.WorldPosition currentPosition(LocalPlayer player) {
        BlockPos pos = player.blockPosition();
        return new Goal.WorldPosition(pos.getX(), pos.getY(), pos.getZ(),
                player.level().dimension().location().toString());
    }

    private static String itemId(Goal goal, JsonObject parameters) {
        String item = string(parameters, "item_id");
        if (item == null && goal.target() != null) item = goal.target().label();
        return item;
    }

    private static JsonArray ids(Goal goal, JsonObject parameters) {
        for (String key : List.of("block_ids", "item_ids")) {
            if (parameters.has(key) && parameters.get(key).isJsonArray()) {
                return parameters.getAsJsonArray(key).deepCopy();
            }
        }
        JsonArray result = new JsonArray();
        String item = itemId(goal, parameters);
        if (item != null) result.add(item);
        return result;
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) return null;
        return object.get(key).getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsBoolean()
                : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback, int min, int max) {
        int value = object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsInt()
                : fallback;
        return Math.max(min, Math.min(max, value));
    }

    private static boolean numberInRange(JsonObject object, String key, int min, int max) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) return false;
        try {
            int value = object.get(key).getAsInt();
            return value >= min && value <= max;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void merge(JsonObject target, JsonObject source) {
        for (var entry : source.entrySet()) {
            target.add(entry.getKey(), entry.getValue().deepCopy());
        }
    }
}

sealed interface IntentAction {
    record Chain(List<Tool> actions) implements IntentAction {
        public Chain {
            actions = List.copyOf(actions);
            if (actions.isEmpty()) throw new IllegalArgumentException("intent action chain cannot be empty");
        }
    }
    record Tool(String toolName, String argumentsJson) implements IntentAction {
        JsonObject arguments() {
            return JsonParser.parseString(argumentsJson).getAsJsonObject();
        }
    }
    record Remember(String label, Goal.WorldPosition position) implements IntentAction {}
    record Wait(String condition, long notBeforeGameTime) implements IntentAction {}
    record Decision(IntentTaskRecord.DecisionSnapshot snapshot) implements IntentAction {}
}
