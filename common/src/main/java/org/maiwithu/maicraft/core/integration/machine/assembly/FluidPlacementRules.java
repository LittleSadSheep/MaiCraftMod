// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** 只检查落桶目标当前是否可用；源流体放下后的流动与相遇由原版世界结算。 */
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

    public static String placementProblem(Level world, BlockPos at, BlockState expected) {
        String problem = preparationProblem(world, at, expected, false, false);
        if (problem != null || matches(world.getBlockState(at), expected)) return problem;
        // 原版超热维度会蒸发水标签流体并照常退空桶，必须在消费前识别这种无法留下源格的环境。
        if (world.dimensionType().ultraWarm() && expected.getFluidState().is(FluidTags.WATER)) return "fluid_would_evaporate";
        // 刷石机需要让水和岩浆流入相邻空格，不能把蓝图的源格清单当成禁止外流的边界。
        return null;
    }
}
