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

/** 采掘和补拾取共用接触站位查询；开路候选交给导航执行，最后短靠近仍须已有实底、净空及保护范围许可。 */
public final class CollectItemsApproach {
    private CollectItemsApproach() {}

    public static GoalCompiler.Compiled goal(LocalPlayer player, Collection<ItemEntity> drops) {
        return goal(player, drops, false);
    }

    /** 开路获准时加入可补挖的脚位和头顶候选；候选存在只表示可以交给寻路，不能替代实际到达与入包确认。 */
    public static GoalCompiler.Compiled goal(LocalPlayer player, Collection<ItemEntity> drops, boolean mayAlterTerrain) {
        var cells = new LinkedHashSet<BlockPos>();
        for (var drop : drops) {
            // 掉落格可能是刚挖出的坑或水面；先加入能从岸边接触的真实落脚点，再保留原格交给正常导航判断。
            var box = drop.getBoundingBox();
            double half = player.getBoundingBox().getXsize() / 2, height = player.getBoundingBox().getYsize();
            var min = BlockPos.containing(box.minX - half - 1.5, box.minY - height - .5, box.minZ - half - 1.5);
            var max = BlockPos.containing(box.maxX + half + .5, box.maxY + 1.5, box.maxZ + half + .5);
            var corridor = corridor(player);
            // 掉落格自身能站立时，走下凹格拾取属普通行走；水面等站不住的格子不享受这条放宽。
            boolean walkableDropCell = corridor.stance(drop.blockPosition()) != null;
            for (var cell : BlockPos.betweenClosed(min, max)) {
                if (contactStance(player, drop, cell, corridor, walkableDropCell, mayAlterTerrain)) cells.add(cell.immutable());
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

    /** 站位安全核查结论：不能站人、直接可站的空气站位，或需要补挖才能到达的站位。 */
    private enum StanceSafety { BLOCKED, STANDABLE, DIGGABLE }

    /**
     * 判断一格能否作为该掉落物的接近站位：空气身体直接可站；需要补挖的脚位和头顶候选只在开路获准时纳入。
     * 掉落格自身能站立且掉落物停在站立面下一格以内时（1 格深凹格坑底），走下凹格拾取属普通行走，
     * 坑边站位不要求开路授权；接近候选只表示可以交给寻路，实际拾取仍以走到、碰到和入包回执确认。
     */
    private static boolean contactStance(LocalPlayer player, ItemEntity drop, BlockPos feet,
            GroundCorridor corridor, boolean walkableDropCell, boolean mayAlterTerrain) {
        if (contactPoint(player, drop, feet, corridor) != null) return true;
        var body = player.getBoundingBox().move(Vec3.atBottomCenterOf(feet).subtract(player.position()));
        AABB reach = body.inflate(1, .5, 1);
        if (!reach.intersects(drop.getBoundingBox())) {
            if (!walkableDropCell) return false;
            AABB steppedDown = new AABB(body.minX - 1, body.minY - 1.5, body.minZ - 1,
                    body.maxX + 1, body.maxY + .5, body.maxZ + 1);
            if (!steppedDown.intersects(drop.getBoundingBox())) return false;
            reach = steppedDown;
        }
        return switch (stanceSafety(player, drop, feet, reach)) {
            case STANDABLE -> true;
            case DIGGABLE -> mayAlterTerrain;
            case BLOCKED -> false;
        };
    }

    private static StanceSafety stanceSafety(LocalPlayer player, ItemEntity drop, BlockPos feet, AABB reach) {
        var world = player.level();
        Vec3 point = Vec3.atBottomCenterOf(feet);
        AABB body = player.getBoundingBox().move(point.subtract(player.position()));
        if (!reach.intersects(drop.getBoundingBox())) return StanceSafety.BLOCKED;
        BlockPos floor = feet.below();
        // 这里仅扩大寻路候选，绝不把尚未挖开的洞口判为已经走通；脚下必须有真实支撑且保持原位。
        if (!world.isLoaded(floor) || !world.getWorldBorder().isWithinBounds(floor)) return StanceSafety.BLOCKED;
        var support = world.getBlockState(floor);
        if (!support.isCollisionShapeFullBlock(world, floor) || TransportLanding.unsafe(world, floor, support)) return StanceSafety.BLOCKED;
        if (!EmbeddedBaritoneRuntime.physicalObstacles().clearSegment(point, point,
                body.getXsize(), body.getYsize())) return StanceSafety.BLOCKED;
        var min = BlockPos.containing(body.minX + 1e-5, body.minY + 1e-5, body.minZ + 1e-5);
        var max = BlockPos.containing(body.maxX - 1e-5, body.maxY - 1e-5, body.maxZ - 1e-5);
        boolean needsDigging = false;
        for (var cell : BlockPos.betweenClosed(min, max)) {
            if (!world.isLoaded(cell) || !world.getWorldBorder().isWithinBounds(cell)
                    || NavigationSafetyContext.forbidsBody(cell)) return StanceSafety.BLOCKED;
            var state = world.getBlockState(cell);
            if (TransportLanding.unsafe(world, cell, state)) return StanceSafety.BLOCKED;
            if (state.getCollisionShape(world, cell).toAabbs().stream().noneMatch(box -> box.move(cell).intersects(body))) continue;
            // 脚位和头顶的天然障碍可由导航补挖；容器、明确保护和名单外构件不能借拾取获得拆除权限。
            if (!ClearanceWhitelist.allows(state) || NavigationSafetyContext.protectsMutation(cell)
                    || state.hasBlockEntity() || state.getDestroySpeed(world, cell) < 0) return StanceSafety.BLOCKED;
            needsDigging = true;
        }
        return needsDigging ? StanceSafety.DIGGABLE : StanceSafety.STANDABLE;
    }

    public static Vec3 nudgePoint(LocalPlayer player, ItemEntity drop) {
        var corridor = corridor(player);
        // 寻路可能在岸边格的边缘停下；只向该格的接触点微调，不能到岸后又径直走进掉落所在的坑。
        var point = contactPoint(player, drop, PlayerNav.playerFeet(player), corridor);
        if (point != null) return point;
        // 掉落格是站立面下一格的可站立凹格时，最后一步就是普通地走下凹格；其余仍回落到掉落坐标。
        var descent = descentLanding(player, drop, corridor);
        return descent != null ? descent : drop.position();
    }

    /**
     * 掉落格恰好比脚下一层且原版落点检查通过时，返回该格的落脚点；否则 null。
     * 这里复用站位查询的完整落点核查（支撑、身体净空、禁入格与危险落点），水面与更深的坑自然不通过。
     */
    private static Vec3 descentLanding(LocalPlayer player, ItemEntity drop, GroundCorridor corridor) {
        BlockPos cell = drop.blockPosition();
        if (cell.getY() != PlayerNav.playerFeet(player).getY() - 1) return null;
        return corridor.stance(cell);
    }

    private static Vec3 contactPoint(LocalPlayer player, ItemEntity drop, BlockPos cell, GroundCorridor corridor) {
        var point = corridor.stance(cell);
        if (point == null) return null;
        var body = player.getBoundingBox().move(point.subtract(player.position()));
        // 先按原版身体扩展盒核对横向与高度接触，再确认该落点没有碰撞、禁入格或物理障碍。
        return body.inflate(1, .5, 1).intersects(drop.getBoundingBox()) && corridor.clear(point, point) ? point : null;
    }

    public static boolean safeNudge(LocalPlayer player, Vec3 item) {
        // 最后一步只作不超过一点五格的水平短走；即使获准开路，也不能靠直接按前进绕过尚未修好的地形。
        if (player.isPassenger() || player.isSwimming() || !player.onGround() && !player.isInWater()) return false;
        Vec3 from = player.position(), target = new Vec3(item.x, from.y, item.z);
        if (from.distanceToSqr(target) > 2.25) return false;
        var corridor = corridor(player);
        // 走下 1 格凹格属普通行走：落点格经原版落点检查、到坑沿的走行段干净时放行；
        // 两格深坑与水面掉落格在层差或落点检查上不过关，仍走同高度的拒绝路径。
        if (descent(player, item, corridor)) return corridor.clear(from, lipPoint(from, item));
        // 除了目的地，还验证当前惯性会滑到的整段身体范围，不能到格后靠无约束前进穿入保护区。
        Vec3 drift = from.add(player.getDeltaMovement().multiply(4, 0, 4));
        return corridor.clear(from, target) && corridor.clear(from, drift);
    }

    /** 目标恰比脚下一层且所在格可站立时判定为下坑形态；坑沿走行段由调用方另行核查。 */
    private static boolean descent(LocalPlayer player, Vec3 item, GroundCorridor corridor) {
        double depth = player.position().y - item.y;
        if (depth <= 0 || depth > 1 + 1e-4) return false;
        return corridor.stance(BlockPos.containing(item)) != null;
    }

    /**
     * 同高度走行段跨入落点柱列前的边界点；该点之前必须有连续支撑，跨出坑沿后的下落由落点检查接管。
     * 惯性滑行段不再按原高度要求支撑——向凹格移动时滑行点本来就在坑上方，落点安全性由 stance 覆盖。
     */
    private static Vec3 lipPoint(Vec3 from, Vec3 item) {
        var cell = BlockPos.containing(item);
        double dx = item.x - from.x, dz = item.z - from.z;
        double t = 1;
        if (Math.abs(dx) > 1e-9) {
            double edge = dx > 0 ? cell.getX() : cell.getX() + 1;
            t = Math.min(t, Math.max(0, (edge - from.x) / dx));
        }
        if (Math.abs(dz) > 1e-9) {
            double edge = dz > 0 ? cell.getZ() : cell.getZ() + 1;
            t = Math.min(t, Math.max(0, (edge - from.z) / dz));
        }
        return new Vec3(from.x + dx * t, from.y, from.z + dz * t);
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
