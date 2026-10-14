// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;

/**
 * 空中的路，纯函数：六个方向一格一格地找，身体占的两格（脚、头）都要是看过的空气——没加载的格不当空气，
 * 流体、岩浆、火这类危险格不进。找到后只留拐弯处，飞的时候一段一段直着飞。喷气背包这类会飞的出行方式用。
 */
public final class FlightRoutes {

    /**
     * 贴着地面、贴着头顶的格子每格多算这么多步：喷气背包贴地飞就是在地上蹭，绕树、上坎都得一点点挪；
     * 人会先升到开阔的空中再平飞。实机 10-14 林子里飞五十格，路线一路贴地，在树干边上蹭了四十秒没飞起来。
     */
    private static final int HUGGING_EXTRA = 2;

    private FlightRoutes() {}

    /**
     * 从 from（脚所在格）飞到 to（脚所在格）的一条路：按顺序的拐弯点，首尾都在里面；找不到为空。
     *
     * @param loaded 这一格看过没有（客户端加载了）
     * @param bounds 只在这个范围里找
     * @param budget 最多展开多少格；展开完还没找到算找不到
     */
    public static Optional<List<BlockPos>> find(BlockGetter view, Predicate<BlockPos> loaded, BlockPos from, BlockPos to,
            AABB bounds, int budget) {
        if (!open(view, loaded, to)) return Optional.empty();
        Long2IntOpenHashMap cost = new Long2IntOpenHashMap();
        cost.defaultReturnValue(Integer.MAX_VALUE);
        Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        long start = from.asLong();
        long goal = to.asLong();
        cost.put(start, 0);
        open.add(new long[]{start, distance(from, to)});
        int expanded = 0;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        while (!open.isEmpty() && expanded < budget) {
            long[] head = open.poll();
            long key = head[0];
            if (key == goal) return Optional.of(corners(trace(parent, start, goal)));
            BlockPos at = BlockPos.of(key);
            int here = cost.get(key);
            if (head[1] - distance(at, to) > here) continue;
            expanded++;
            for (Direction direction : Direction.values()) {
                next.setWithOffset(at, direction);
                if (!bounds.contains(next.getX() + 0.5, next.getY() + 0.5, next.getZ() + 0.5)) continue;
                long nextKey = next.asLong();
                // 贴地、贴顶的格子多算几步，路线就会先升到开阔处再平飞；起落两头免不了贴地，照算。
                int step = here + 1 + (hugging(view, next) ? HUGGING_EXTRA : 0);
                if (step >= cost.get(nextKey)) continue;
                if (!open(view, loaded, next)) continue;
                cost.put(nextKey, step);
                parent.put(nextKey, key);
                open.add(new long[]{nextKey, step + distance(next, to)});
            }
        }
        return Optional.empty();
    }

    /**
     * 这条路（拐弯点）上除去起落两头，有几格是飞不开的：头上一格就是实心（坑道、矿洞、低矮的屋里），
     * 或离脚下的实心不到两格（贴着地面蹭）。竖井、开阔的空中不算。喷气背包在这种地方得一点点挪，
     * 人一般走过去，或先升到开阔处再飞；出行方式估时用它把这几格算慢。
     */
    public static int slowCells(BlockGetter view, List<BlockPos> route) {
        if (route.isEmpty()) return 0;
        BlockPos start = route.getFirst();
        BlockPos end = route.getLast();
        int count = 0;
        for (int i = 1; i < route.size(); i++) {
            BlockPos from = route.get(i - 1);
            BlockPos to = route.get(i);
            // 相邻两个拐弯点之间是沿一根轴的直线：一格一格看过去。
            int steps = from.distManhattan(to);
            for (int step = 1; step <= steps; step++) {
                BlockPos cell = from.offset((to.getX() - from.getX()) * step / steps,
                        (to.getY() - from.getY()) * step / steps, (to.getZ() - from.getZ()) * step / steps);
                if (!cell.equals(start) && !cell.equals(end) && hugging(view, cell)) count++;
            }
        }
        return count;
    }

    // 这一格飞不开：头顶紧挨着实心方块，离脚下的实心不到两格（贴着地面、树根、坎子蹭），
    // 或身子旁边紧挨着树干、树叶、墙（在林子里穿行，喷气背包有惯性，一拐弯就蹭上）。
    // 实机 10-14 林子里贴着树冠底下飞，三十多个拐弯点，冲过头又蹭在树叶上停住，连着重找路。
    private static boolean hugging(BlockGetter view, BlockPos feet) {
        if (solid(view, feet.above(2)) || solid(view, feet.below()) || solid(view, feet.below(2))) return true;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (solid(view, feet.relative(side)) || solid(view, feet.above().relative(side))) return true;
        }
        return false;
    }

    private static boolean solid(BlockGetter view, BlockPos pos) {
        return !view.getBlockState(pos).getCollisionShape(view, pos).isEmpty();
    }

    /** 人能飘在这一格：脚、头两格都看过、没有碰撞、不是流体与危险格。 */
    public static boolean open(BlockGetter view, Predicate<BlockPos> loaded, BlockPos feet) {
        return clear(view, loaded, feet) && clear(view, loaded, feet.above());
    }

    private static boolean clear(BlockGetter view, Predicate<BlockPos> loaded, BlockPos cell) {
        if (!loaded.test(cell)) return false;
        BlockState state = view.getBlockState(cell);
        return state.getCollisionShape(view, cell).isEmpty() && state.getFluidState().isEmpty()
                && !BlockPassability.isHazard(view, cell);
    }

    private static long distance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    private static List<BlockPos> trace(Long2LongOpenHashMap parent, long start, long goal) {
        List<BlockPos> path = new ArrayList<>();
        long at = goal;
        path.add(BlockPos.of(at));
        while (at != start) {
            at = parent.get(at);
            path.add(BlockPos.of(at));
        }
        Collections.reverse(path);
        return path;
    }

    // 只留拐弯处：同一个方向上连着走的几格并成一段。
    static List<BlockPos> corners(List<BlockPos> path) {
        if (path.size() <= 2) return path;
        List<BlockPos> kept = new ArrayList<>();
        kept.add(path.getFirst());
        for (int i = 1; i < path.size() - 1; i++) {
            BlockPos before = path.get(i).subtract(path.get(i - 1));
            BlockPos after = path.get(i + 1).subtract(path.get(i));
            if (!before.equals(after)) kept.add(path.get(i));
        }
        kept.add(path.getLast());
        return kept;
    }
}
