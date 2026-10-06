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

/** 有打火石先检查当前位置的投掷通道；其余候选依次为同层已加载平面的空地走廊、原地直接丢弃、四格深两格高的侧袋。 */
public record DiscardSitePlan(BlockPos stance, Direction direction, List<BlockPos> excavation, boolean requiresBurn, boolean inPlace) {
    private static final int RADIUS = 12;
    // 出口绕行搜索的勘察上限；超出仍没接回已勘察区域时按堵死通道处理。
    private static final int ESCAPE_RADIUS = 8;
    public DiscardSitePlan { stance = stance.immutable(); excavation = List.copyOf(excavation); }
    public DiscardSitePlan(BlockPos stance, Direction direction, List<BlockPos> excavation) { this(stance, direction, excavation, false, false); }
    public DiscardSitePlan(BlockPos stance, Direction direction, List<BlockPos> excavation, boolean requiresBurn) { this(stance, direction, excavation, requiresBurn, false); }

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
        // 走廊形态全线不成立时，开阔平地仍可直接原地丢：走廊净空服务投掷弹道，不是丢弃成立的前提。
        // 避让区盖住站位是原地形态的预期（物品不阻挡行走）；其余已勘察区域仍须互相连通，
        // chokepoint 否决照常生效，狭窄走廊不会因兜底绕过侧袋。
        if (!rejected.contains(origin)) for (Direction direction : directions) {
            var plan = new DiscardSitePlan(origin, direction, List.of(), false, true);
            var envelope = plan.pickupEnvelope(player);
            if (keepsRegionConnected(connected, envelope) && !blocksFrontier(world, connected, envelope)) return plan;
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
        var cells = new LongOpenHashSet();
        // 选点时按拾取包围盒估计落点避让范围；这是局部通行规划，真实碰撞、漂移仍由后续实体观察确认。
        if (inPlace()) {
            // 原地丢弃的物品会被前方障碍弹回，落点在站位邻域内不定；走廊形态的前置弹道带不再计入，
            // 避让范围取站位整格邻域，通道堵死判定按这片范围评估。
            DiscardedItems.addPickupCells(cells, new AABB(stance), player.getBoundingBox().getXsize(), player.getBoundingBox().getYsize());
            return cells;
        }
        Vec3 origin = Vec3.atBottomCenterOf(stance), along = Vec3.atLowerCornerOf(direction.getNormal());
        Vec3 near = origin.add(along.scale(2.5)), far = origin.add(along.scale(4));
        AABB items = new AABB(near, far).inflate(.3, .25, .3);
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

    /** 原地丢弃的避让区盖住站位本身是预期形态；除避让区外的已勘察格子必须仍互相连通，丢弃带不得把区域切成避让死区。 */
    static boolean keepsRegionConnected(Set<BlockPos> before, LongSet excluded) {
        BlockPos seed = null;
        for (BlockPos pos : before) if (!excluded.contains(pos.asLong())) { seed = pos; break; }
        if (seed == null) return true;
        var remaining = new LinkedHashSet<BlockPos>(); var queue = new ArrayDeque<BlockPos>(); queue.add(seed);
        while (!queue.isEmpty()) {
            BlockPos at = queue.removeFirst();
            if (!before.contains(at) || excluded.contains(at.asLong()) || !remaining.add(at)) continue;
            for (Direction side : Direction.Plane.HORIZONTAL) queue.add(at.relative(side));
        }
        return before.stream().allMatch(pos -> excluded.contains(pos.asLong()) || remaining.contains(pos));
    }

    private static boolean blocksFrontier(Level world, Set<BlockPos> connected, LongSet excluded) {
        // 拾取范围只有堵住勘察范围之外区域的唯一入口才算边界；开阔地形上零星台阶与 BFS 半径截断
        // 都能绕行，不因邻接就整片拒绝候选。未加载区块之外无从核验，仍按边界处理。
        for (BlockPos cell : connected) if (excluded.contains(cell.asLong()))
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(side);
                if (!world.isLoaded(next)) return true;
                if (connected.contains(next)) continue;
                for (BlockPos exit : List.of(next, next.above(), next.below()))
                    if (walkable(world, exit) && !escapesAround(world, connected, excluded, exit)) return true;
            }
        return false;
    }

    /** 出口能否不穿过拾取范围就回到已勘察区域；出不去说明丢弃会堵死这条通往远处的通道。 */
    private static boolean escapesAround(Level world, Set<BlockPos> connected, LongSet excluded, BlockPos exit) {
        var seen = new LongOpenHashSet(); var queue = new ArrayDeque<BlockPos>();
        seen.add(exit.asLong()); queue.add(exit);
        while (!queue.isEmpty()) {
            BlockPos at = queue.removeFirst();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos nb = at.relative(side);
                if (connected.contains(nb)) { if (!excluded.contains(nb.asLong())) return true; continue; }
                if (!world.isLoaded(nb) || !seen.add(nb.asLong())
                        || Math.abs(nb.getX() - exit.getX()) > ESCAPE_RADIUS || Math.abs(nb.getZ() - exit.getZ()) > ESCAPE_RADIUS)
                    continue;
                for (BlockPos feet : List.of(nb, nb.above(), nb.below()))
                    if (walkable(world, feet)) { queue.add(feet); break; }
            }
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
