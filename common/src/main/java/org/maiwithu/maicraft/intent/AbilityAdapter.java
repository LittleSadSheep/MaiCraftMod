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
import net.minecraft.world.level.levelgen.Heightmap;
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
                if (player.level().isDay() && !player.level().isThundering()) {
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
            if (player.level().isDay() && !player.level().isThundering()) {
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
                    int y = player.clientLevel.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
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
                String exploreTarget = exploreTarget(goal, runtime);
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
