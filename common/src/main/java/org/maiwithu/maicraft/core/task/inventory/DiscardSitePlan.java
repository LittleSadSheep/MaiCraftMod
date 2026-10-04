// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.DiscardedItems;
import org.maiwithu.maicraft.core.PlayerInv;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** 有打火石先检查当前位置的投掷通道；其余候选限于同层已加载平面，优先空地，再考虑四格深、两格高的侧袋。 */
public record DiscardSitePlan(BlockPos stance, Direction direction, List<BlockPos> excavation, boolean requiresBurn) {
    private static final int RADIUS = 12;
    public DiscardSitePlan { stance = stance.immutable(); excavation = List.copyOf(excavation); }
    public DiscardSitePlan(BlockPos stance, Direction direction, List<BlockPos> excavation) { this(stance, direction, excavation, false); }

    public static DiscardSitePlan find(LocalPlayer player, Set<BlockPos> rejected) {
        return find(player, rejected, true);
    }
    static DiscardSitePlan find(LocalPlayer player, Set<BlockPos> rejected, boolean allowBurn) {
        Level world = player.level(); BlockPos origin = player.blockPosition();
        Set<BlockPos> connected = connected(world, origin);
        List<BlockPos> stances = connected.stream().filter(pos -> !rejected.contains(pos))
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(origin))).toList();
        Direction facing = Direction.fromYRot(player.getYRot());
        List<Direction> directions = List.of(facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite());
        if (allowBurn && PlayerInv.count(player.getInventory(), Items.FLINT_AND_STEEL) > 0 && !rejected.contains(origin)) {
            DiscardSitePlan burnSite = null;
            for (Direction direction : directions) {
                var plan = new DiscardSitePlan(origin, direction, List.of());
                if (!plan.clear(world)) continue;
                if (preservesRoutes(connected, origin, origin, plan.pickupEnvelope(player)) && !blocksFrontier(world, connected, plan.pickupEnvelope(player))) return plan;
                if (burnSite == null) burnSite = new DiscardSitePlan(origin, direction, List.of(), true);
            }
            // 有打火石可以直接在走廊中原生销毁；此位置必须等烧毁，烧不掉时父任务先回收，再换侧袋。
            if (burnSite != null) return burnSite;
        }
        // 优先利用同一已加载步行区域的现成空地；删除预计拾取范围后，原通道其余部分仍须互相可达。
        for (BlockPos stance : stances) for (Direction direction : directions) {
            var plan = new DiscardSitePlan(stance, direction, List.of());
            if (plan.clear(world) && preservesRoutes(connected, origin, stance, plan.pickupEnvelope(player))
                    && !blocksFrontier(world, connected, plan.pickupEnvelope(player))) return plan;
        }
        // 窄巷没有可绕行的位置时，把垃圾放到新开出的侧袋底部；不把原通道或储物机器算作可挖侧壁。
        for (BlockPos stance : stances) for (Direction direction : directions) {
            var cells = new ArrayList<BlockPos>(); boolean usable = true;
            for (int depth = 1; depth <= 4 && usable; depth++) {
                BlockPos feet = stance.relative(direction, depth);
                if (connected.contains(feet) || !floor(world, feet)) { usable = false; break; }
                for (BlockPos cell : List.of(feet.above(), feet)) {
                    if (!world.isLoaded(cell)) { usable = false; break; }
                    var state = world.getBlockState(cell);
                    if (!state.getFluidState().isEmpty() || state.hasBlockEntity() || state.getDestroySpeed(world, cell) < 0
                            || NavigationSafetyContext.protectsMutation(cell)) { usable = false; break; }
                    if (!state.isAir()) cells.add(cell);
                }
            }
            var plan = new DiscardSitePlan(stance, direction, cells);
            if (usable && !cells.isEmpty() && preservesRoutes(connected, origin, stance, plan.pickupEnvelope(player))
                    && !blocksFrontier(world, connected, plan.pickupEnvelope(player))) return plan;
        }
        return null;
    }

    public boolean clear(Level world) {
        for (int depth = 1; depth <= 4; depth++) if (!walkable(world, stance.relative(direction, depth))) return false;
        return true;
    }

    private LongSet pickupEnvelope(LocalPlayer player) {
        // 选点时以侧袋后段估计落点范围，再按拾取包围盒扩张；这是局部通行规划，真实碰撞、漂移仍由后续实体观察确认。
        Vec3 origin = Vec3.atBottomCenterOf(stance), along = Vec3.atLowerCornerOf(direction.getNormal());
        Vec3 near = origin.add(along.scale(2.5)), far = origin.add(along.scale(4));
        AABB items = new AABB(near, far).inflate(.3, .25, .3);
        var cells = new LongOpenHashSet();
        DiscardedItems.addPickupCells(cells, items, player.getBoundingBox().getXsize(), player.getBoundingBox().getYsize());
        return cells;
    }

    public static boolean preservesRoutes(Set<BlockPos> before, BlockPos origin, BlockPos stance, LongSet excluded) {
        if (excluded.contains(origin.asLong()) || excluded.contains(stance.asLong())) return false;
        var remaining = new LinkedHashSet<BlockPos>(); var queue = new ArrayDeque<BlockPos>(); queue.add(origin);
        while (!queue.isEmpty()) {
            BlockPos at = queue.removeFirst();
            if (!before.contains(at) || excluded.contains(at.asLong()) || !remaining.add(at)) continue;
            for (Direction side : Direction.Plane.HORIZONTAL) queue.add(at.relative(side));
        }
        return remaining.contains(stance) && before.stream().allMatch(pos -> excluded.contains(pos.asLong()) || remaining.contains(pos));
    }

    private static boolean blocksFrontier(Level world, Set<BlockPos> connected, LongSet excluded) {
        // 勘察半径和已加载区块的边缘仍可能通往远处；不能把局部扫描截断的走廊误当成可以堵住的死胡同。
        for (BlockPos cell : connected) if (excluded.contains(cell.asLong()))
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(side);
                if (!connected.contains(next) && (!world.isLoaded(next) || walkable(world, next))) return true;
                // 平面勘察也保留通向高低台阶的出口，不能把楼梯前唯一的脚位当成可堵住的边角。
                if (walkable(world, next.above()) || walkable(world, next.below())) return true;
            }
        return false;
    }

    static Set<BlockPos> connected(Level world, BlockPos origin) {
        var found = new LinkedHashSet<BlockPos>(); var queue = new ArrayDeque<BlockPos>(); queue.add(origin);
        while (!queue.isEmpty()) {
            BlockPos at = queue.removeFirst();
            if (Math.abs(at.getX() - origin.getX()) > RADIUS || Math.abs(at.getZ() - origin.getZ()) > RADIUS
                    || found.contains(at) || !walkable(world, at)) continue;
            found.add(at);
            for (Direction side : Direction.Plane.HORIZONTAL) queue.add(at.relative(side));
        }
        return Set.copyOf(found);
    }

    private static boolean walkable(Level world, BlockPos feet) {
        if (!floor(world, feet) || !world.isLoaded(feet.above()) || NavigationSafetyContext.forbidsBody(feet)) return false;
        for (BlockPos cell : List.of(feet, feet.above())) {
            var state = world.getBlockState(cell);
            if (!state.isAir()) return false;
        }
        return true;
    }
    private static boolean floor(Level world, BlockPos feet) {
        return world.isLoaded(feet) && world.isLoaded(feet.below())
                && world.getBlockState(feet.below()).isFaceSturdy(world, feet.below(), Direction.UP);
    }
}
