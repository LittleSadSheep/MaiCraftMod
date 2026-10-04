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
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.settings.ClearanceWhitelist;
import org.maiwithu.maicraft.core.pathing.transport.TransportLanding;

/** 采掘和补拾取共用原版接触范围选择落脚点；短靠近仍核对实底、碰撞和保护格，不修改池子或玩家。 */
public final class CollectItemsApproach {
    private CollectItemsApproach() {}

    public static GoalCompiler.Compiled goal(LocalPlayer player, Collection<ItemEntity> drops) {
        return goal(player, drops, false);
    }

    /** 已授权开路时也提交需要补齐身体空间的接触站位，让真实导航计算挖路代价并执行原生清障。 */
    public static GoalCompiler.Compiled goal(LocalPlayer player, Collection<ItemEntity> drops, boolean mayAlterTerrain) {
        var cells = new LinkedHashSet<BlockPos>();
        for (var drop : drops) {
            // 掉落格可能是刚挖出的坑或水面；先加入能从岸边接触的真实落脚点，再保留原格交给正常导航判断。
            var box = drop.getBoundingBox();
            double half = player.getBoundingBox().getXsize() / 2, height = player.getBoundingBox().getYsize();
            var min = BlockPos.containing(box.minX - half - 1.5, box.minY - height - .5, box.minZ - half - 1.5);
            var max = BlockPos.containing(box.maxX + half + .5, box.maxY + 1.5, box.maxZ + half + .5);
            var corridor = corridor(player);
            for (var cell : BlockPos.betweenClosed(min, max)) {
                if (contactPoint(player, drop, cell, corridor) != null
                        || mayAlterTerrain && preparableContact(player, drop, cell)) cells.add(cell.immutable());
            }
            cells.add(drop.blockPosition());
        }
        // 接触站位全部被拒时导航只剩掉落格自己，"走到物件旁捡起"退化为"走进物件格"；
        // 记下此刻的枚举结果供实地取证区分地形几何与闸口误伤。
        if (cells.size() <= drops.size()) {
            for (var drop : drops) {
                var at = drop.position();
                org.maiwithu.maicraft.core.Constants.LOG.info(
                        "[maicraft-collect] no adjacent contact stance accepted for drop at ({}, {}, {}); candidates={}, may_alter_terrain={}",
                        Math.round(at.x * 10) / 10.0, Math.round(at.y * 10) / 10.0,
                        Math.round(at.z * 10) / 10.0, cells.size(), mayAlterTerrain);
            }
        }
        return cells.isEmpty() ? null : GoalCompiler.mineField(List.of(), List.copyOf(cells));
    }

    private static boolean preparableContact(LocalPlayer player, ItemEntity drop, BlockPos feet) {
        var world = player.level();
        Vec3 point = Vec3.atBottomCenterOf(feet);
        AABB body = player.getBoundingBox().move(point.subtract(player.position()));
        if (!body.inflate(1, .5, 1).intersects(drop.getBoundingBox())) return false;
        BlockPos floor = feet.below();
        // 这里仅扩大寻路候选，绝不把尚未挖开的洞口判为已经走通；脚下必须有真实支撑且保持原位。
        if (!world.isLoaded(floor) || !world.getWorldBorder().isWithinBounds(floor)) return false;
        var support = world.getBlockState(floor);
        if (!support.isCollisionShapeFullBlock(world, floor) || TransportLanding.unsafe(world, floor, support)) return false;
        if (!EmbeddedBaritoneRuntime.physicalObstacles().clearSegment(point, point,
                body.getXsize(), body.getYsize())) return false;
        var min = BlockPos.containing(body.minX + 1e-5, body.minY + 1e-5, body.minZ + 1e-5);
        var max = BlockPos.containing(body.maxX - 1e-5, body.maxY - 1e-5, body.maxZ - 1e-5);
        for (var cell : BlockPos.betweenClosed(min, max)) {
            if (!world.isLoaded(cell) || !world.getWorldBorder().isWithinBounds(cell)
                    || NavigationSafetyContext.forbidsBody(cell)) return false;
            var state = world.getBlockState(cell);
            if (TransportLanding.unsafe(world, cell, state)) return false;
            if (state.getCollisionShape(world, cell).toAabbs().stream().noneMatch(box -> box.move(cell).intersects(body))) continue;
            // 脚位和头顶的天然障碍可由导航补挖；容器、明确保护和名单外构件不能借拾取获得拆除权限。
            if (!ClearanceWhitelist.allows(state) || NavigationSafetyContext.protectsMutation(cell)
                    || state.hasBlockEntity() || state.getDestroySpeed(world, cell) < 0) return false;
        }
        return true;
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
