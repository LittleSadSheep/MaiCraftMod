// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import net.minecraft.core.BlockPos;
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
        // 目标已有水流、火把或其他方块不证明桶不能使用；是否替换、反应或拒绝由真实原生右键结算。
        return null;
    }

    public static boolean needsSolidClearance(Level world, BlockPos at, boolean replace, boolean replaceBlockEntities) {
        // 只有明确授权的普通拆换才预先清空；其余占用留给桶的原生交互，不用“不能代拆”提前否决倒桶。
        BlockState actual = world.getBlockState(at);
        return replace && !actual.isAir() && actual.getFluidState().isEmpty() && !actual.canBeReplaced()
                && (!actual.hasBlockEntity() || replaceBlockEntities) && actual.getDestroySpeed(world, at) >= 0
                && !actual.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && !actual.hasProperty(BlockStateProperties.BED_PART);
    }

    public static String placementProblem(Level world, BlockPos at, BlockState expected) {
        // 超热维度蒸发、水岩浆相遇等设计后果也由世界结算，之后通过实际状态和蓝图diff反馈。
        return preparationProblem(world, at, expected, false, false);
    }
}
