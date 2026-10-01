// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.collect;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/** 采掘和补拾取共用原版接触范围选择落脚点；短靠近仍核对实底、碰撞和保护格，不修改池子或玩家。 */
public final class CollectItemsApproach {
    private CollectItemsApproach() {}

    public static GoalCompiler.Compiled goal(LocalPlayer player, Collection<ItemEntity> drops) {
        var cells = new LinkedHashSet<BlockPos>();
        for (var drop : drops) {
            // 掉落格可能是刚挖出的坑或水面；先加入能从岸边接触的真实落脚点，再保留原格交给正常导航判断。
            var box = drop.getBoundingBox();
            double half = player.getBoundingBox().getXsize() / 2, height = player.getBoundingBox().getYsize();
            var min = BlockPos.containing(box.minX - half - 1.5, box.minY - height - .5, box.minZ - half - 1.5);
            var max = BlockPos.containing(box.maxX + half + .5, box.maxY + 1.5, box.maxZ + half + .5);
            var corridor = corridor(player);
            for (var cell : BlockPos.betweenClosed(min, max)) {
                if (contactPoint(player, drop, cell, corridor) != null) cells.add(cell.immutable());
            }
            cells.add(drop.blockPosition());
        }
        return cells.isEmpty() ? null : GoalCompiler.mineField(List.of(), List.copyOf(cells));
    }

    public static Vec3 nudgePoint(LocalPlayer player, ItemEntity drop) {
        // 寻路可能在岸边格的边缘停下；只向该格的接触点微调，不能到岸后又径直走进掉落所在的坑。
        var point = contactPoint(player, drop, PlayerNav.playerFeet(player), corridor(player));
        return point == null ? drop.position() : point;
    }

    private static Vec3 contactPoint(LocalPlayer player, ItemEntity drop, BlockPos cell, GroundCorridor corridor) {
        var point = corridor.stance(cell);
        if (point == null) return null;
        var body = player.getBoundingBox().move(point.subtract(player.position()));
        // 先按原版身体扩展盒核对横向与高度接触，再确认该落点没有碰撞、禁入格或物理障碍。
        return body.inflate(1, .5, 1).intersects(drop.getBoundingBox()) && corridor.clear(point, point) ? point : null;
    }

    public static boolean safeNudge(LocalPlayer player, Vec3 item) {
        if (player.isPassenger() || player.isSwimming() || !player.onGround() && !player.isInWater()) return false;
        Vec3 from = player.position(), target = new Vec3(item.x, from.y, item.z);
        if (from.distanceToSqr(target) > 2.25) return false;
        var corridor = corridor(player);
        // 除了目的地，还验证当前惯性会滑到的整段身体范围，不能到格后靠无约束前进穿入保护区。
        Vec3 drift = from.add(player.getDeltaMovement().multiply(4, 0, 4));
        return corridor.clear(from, target) && corridor.clear(from, drift);
    }

    private static GroundCorridor corridor(LocalPlayer player) {
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
        return new GroundCorridor(view, cell -> world.isLoaded(cell) && world.getWorldBorder().isWithinBounds(cell),
                player.getBoundingBox().getXsize(), player.getBoundingBox().getYsize(),
                NavigationSafetyContext.forbiddenBodyCells(), EmbeddedBaritoneRuntime.physicalObstacles());
    }
}
