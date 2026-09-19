// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.collect;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** 受限收取到格后的最后短靠近仍需真实支撑；仅允许有实底且头顶无水的浅水查询，不修改池子或玩家。 */
final class CollectItemsApproach {
    private CollectItemsApproach() {}

    static boolean safeTarget(LocalPlayer player, BlockPos cell) {
        var world = player.level();
        if (!world.isLoaded(cell)) return false;
        var fluid = world.getFluidState(cell);
        // 受限收取可进入已见实底的一格浅水，不能为了追产物把同一个目标延伸到深水或其他流体。
        return fluid.isEmpty() || fluid.is(FluidTags.WATER) && world.isLoaded(cell.below()) && world.isLoaded(cell.above())
                && world.getBlockState(cell.below()).isCollisionShapeFullBlock(world, cell.below()) && world.getFluidState(cell.above()).isEmpty();
    }

    static boolean safeNudge(LocalPlayer player, Vec3 item) {
        if (player.isPassenger() || player.isSwimming() || !player.onGround() && !player.isInWater()) return false;
        Vec3 from = player.position(), target = new Vec3(item.x, from.y, item.z);
        if (from.distanceToSqr(target) > 2.25) return false;
        var world = player.level();
        var view = new BlockGetter() {
            public BlockState getBlockState(BlockPos cell) {
                BlockState state = world.getBlockState(cell);
                // 只在碰撞查询中忽略浅水本身；底部、池壁和其他流体保持真实状态，深水仍被走廊检查拒绝。
                return state.getFluidState().is(FluidTags.WATER) && world.isLoaded(cell.below()) && world.isLoaded(cell.above())
                        && world.getBlockState(cell.below()).isCollisionShapeFullBlock(world, cell.below())
                        && world.getFluidState(cell.above()).isEmpty() && state.getCollisionShape(world, cell).isEmpty()
                        ? Blocks.AIR.defaultBlockState() : state;
            }
            public FluidState getFluidState(BlockPos cell) { return getBlockState(cell).getFluidState(); }
            public BlockEntity getBlockEntity(BlockPos cell) { return world.getBlockEntity(cell); }
            public int getHeight() { return world.getHeight(); }
            public int getMinBuildHeight() { return world.getMinBuildHeight(); }
        };
        var corridor = new GroundCorridor(view, cell -> world.isLoaded(cell) && world.getWorldBorder().isWithinBounds(cell),
                player.getBbWidth(), player.getBbHeight(), NavigationSafetyContext.forbiddenBodyCells(), EmbeddedBaritoneRuntime.physicalObstacles());
        // 除了目的地，还验证当前惯性会滑到的整段身体范围，不能到格后靠无约束前进穿入保护区。
        Vec3 drift = from.add(player.getDeltaMovement().multiply(4, 0, 4));
        return corridor.clear(from, target) && corridor.clear(from, drift);
    }
}
