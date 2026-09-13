// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.build.ReplaceMode;
import org.maiwithu.maicraft.core.task.supply.SemanticBuildSupplyTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;

final class UtilityCableConstruction {
    static final ResourceLocation CABLE = ResourceLocation.parse("mekanism:basic_universal_cable");
    private UtilityCableConstruction() {}
    static boolean available() {
        return BuiltInRegistries.ITEM.getOptional(CABLE).orElse(null) instanceof BlockItem;
    }
    static UtilityConnectionPlanner.Route existing(LocalPlayer player, BlockPos source, List<Direction> faces, BlockPos target, Direction targetFace) {
        var world = player.level();
        return UtilityExistingCableRoute.find(source,faces,target,targetFace,new UtilityExistingCableRoute.WorldView() {
            public boolean loaded(BlockPos at) {
                return world.isLoaded(at) && !world.isOutsideBuildHeight(at) && world.getWorldBorder().isWithinBounds(at)
                        && !NavigationSafetyContext.protectsUse(at);
            }
            public boolean cable(BlockPos at) { return BuiltInRegistries.BLOCK.getKey(world.getBlockState(at).getBlock()).equals(CABLE); }
            public boolean device(BlockPos at) { return world.getBlockEntity(at) != null; }
        });
    }
    static boolean empty(LocalPlayer player, BlockPos position, List<BlockPos> endpoints) {
        var world = player.level();
        if (!world.isLoaded(position) || world.isOutsideBuildHeight(position)
                || !world.getWorldBorder().isWithinBounds(position) || NavigationSafetyContext.protectsMutation(position)
                || !world.getBlockState(position).isAir() || world.getBlockEntity(position) != null
                || !world.getBlockState(position).getFluidState().isEmpty()) return false;
        // Avoid accidentally merging a new cable into an unrelated device/network along the route.
        for (Direction face : Direction.values()) {
            BlockPos adjacent = position.relative(face);
            if (endpoints.contains(adjacent)) continue;
            if (!world.isLoaded(adjacent) || world.getBlockEntity(adjacent) != null) return false;
        }
        return true;
    }
    static SemanticBuildSupplyTaskRecord task(String callId, long deadline, MaterialPolicy materialPolicy,
            List<String> protectedLabels, UtilityConnectionPlanner.Route route, BooleanSupplier endpointsCurrent) {
        BlockItem item = (BlockItem) BuiltInRegistries.ITEM.get(CABLE);
        var targets = route.cables().stream().map(position -> new BuildTaskRecord.Target(item.getBlock().defaultBlockState(),
                item, position, "external energy cable", null, null, null, true, Set.of(), true, Set.of())).toList();
        // Real carried materials are required even when the caller happens to be in creative mode.
        BuildTaskRecord plan = new BuildTaskRecord(callId + "-cables", deadline,
                targets, ReplaceMode.DONT_REPLACE, false, true, false, Map.of(), List.of(), false);
        List<BlockPos> endpoints = List.of(route.path().getFirst(), route.path().getLast());
        plan.materialSupplyProtection(route.path());
        plan.executionGuards(endpoints, actor -> endpointsCurrent.getAsBoolean(), (actor, position) -> {
            if (!endpointsCurrent.getAsBoolean() || !actor.level().isLoaded(position)) return false;
            // Scaffold cells remain owned by the normal builder; planned cables may only replace air.
            if (endpoints.contains(position)) return false;
            if (!route.cables().contains(position)) return true;
            if (!actor.level().getBlockState(position).isAir()) return false;
            for (Direction face : Direction.values()) {
                BlockPos adjacent = position.relative(face);
                if (route.path().contains(adjacent)) continue;
                if (!actor.level().isLoaded(adjacent) || actor.level().getBlockEntity(adjacent) != null) return false;
            }
            return true;
        }, (actor, position) -> {});
        return new SemanticBuildSupplyTaskRecord(callId + "-cable-supply", deadline,
                plan, materialPolicy, List.of(), false, protectedLabels, false);
    }
    static boolean matches(LocalPlayer player, List<BlockPos> positions) {
        return positions.stream().allMatch(at -> player.level().isLoaded(at)
                && BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(at).getBlock()).equals(CABLE));
    }
}
