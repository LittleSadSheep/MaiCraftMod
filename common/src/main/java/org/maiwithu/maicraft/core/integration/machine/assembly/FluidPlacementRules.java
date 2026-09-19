// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** 所有流体源共用现场准入；声明源格可以是任意池形，但桶不能替玩家清除物品、混合其他流体或向池外漫流。 */
public final class FluidPlacementRules {
    private FluidPlacementRules() {}
    public static boolean matches(BlockState actual, BlockState expected) { return actual.equals(expected) && actual.getFluidState().isSource(); }
    public static boolean sameFluid(BlockState state, BlockState expected) {
        return state.getBlock() instanceof LiquidBlock && !state.getFluidState().isEmpty()
                && state.getFluidState().getType().isSame(expected.getFluidState().getType());
    }

    public static String preparationProblem(Level world, BlockPos at, BlockState expected, boolean replace, boolean replaceBlockEntities) {
        if (!world.isLoaded(at)) return "fluid_target_unloaded";
        BlockState actual = world.getBlockState(at);
        if (matches(actual, expected)) return null;
        if (NavigationSafetyContext.protectsMutation(at)) return "fluid_target_protected";
        if (actual.isAir() || sameFluid(actual, expected) && !actual.getFluidState().isSource()) return null;
        if (!actual.getFluidState().isEmpty()) return "fluid_target_contains_other_fluid";
        // 原料桶不能充当隐式拆除器；普通占用只有明确替换授权后，才交前面的正常清空施工处理。
        if (!replace || actual.hasBlockEntity() && !replaceBlockEntities) return "fluid_target_occupied_without_replacement";
        if (actual.getDestroySpeed(world, at) < 0 || actual.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || actual.hasProperty(BlockStateProperties.BED_PART)) return "fluid_target_has_unmodeled_removal";
        return null;
    }

    public static String placementProblem(Level world, BlockPos at, BlockState expected, Set<BlockPos> sources) {
        String problem = preparationProblem(world, at, expected, false, false);
        if (problem != null || matches(world.getBlockState(at), expected)) return problem;
        // 原版超热维度会蒸发水标签流体并照常退空桶，必须在消费前识别这种无法留下源格的环境。
        if (world.dimensionType().ultraWarm() && expected.getFluidState().is(FluidTags.WATER)) return "fluid_would_evaporate";
        for (Direction side : Direction.values()) {
            if (side == Direction.UP) continue;
            BlockPos neighbor = at.relative(side);
            if (!world.isLoaded(neighbor)) return "fluid_boundary_unloaded";
            BlockState state = world.getBlockState(neighbor);
            if (sources.contains(neighbor)) {
                if (!state.isAir() && !sameFluid(state, expected)) return "fluid_boundary_target_occupied";
                if (NavigationSafetyContext.protectsMutation(neighbor) && !matches(state, expected)) return "fluid_boundary_protected";
            } else if (!state.getFluidState().isEmpty() || !state.isFaceSturdy(world, neighbor, side.getOpposite())) {
                return "fluid_would_leave_declared_source_region";
            }
        }
        return null;
    }
}
