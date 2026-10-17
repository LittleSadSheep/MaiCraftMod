// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 照明的范围：radius 给了是以目标为圆心的圆内各列；不给是从目标那格出发的连通可行走空间
 * （一间屋、一条矿道），沿能站住的格泛洪，只数已加载的，到没加载的边界停下来等走过去再看。
 *
 * <p>泛洪分几刻走：一刻只扩一小段，费时的搜索不占满一个刻。
 */
public final class LightRegion {

    /** 半径的防呆上限：控规模用小半径分段，不靠预算掐断。 */
    public static final int MAX_RADIUS = 32;

    /** 连通空间的泛洪上限：到了就当作量到这里为止，结果里写明；无边的露天平地不能一路量到视距外。 */
    public static final int MAX_FLOOD_COLUMNS = 4096;

    private LightRegion() {}

    /** 圆内各列（按 XZ 距离算，含边界），按离圆心从近到远排。 */
    public static List<BlockPos> circleColumns(BlockPos center, int radius) {
        List<BlockPos> columns = new ArrayList<>();
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= r2) {
                    columns.add(new BlockPos(center.getX() + dx, center.getY(), center.getZ() + dz));
                }
            }
        }
        columns.sort((a, b) -> Double.compare(a.distSqr(center), b.distSqr(center)));
        return columns;
    }

    /**
     * 连通可行走空间的分刻泛洪：从起点那格沿能站住的格（脚与头能占、脚下结实）向外扩，
     * 上下各一格的台阶也算连通。没加载的边界记下来不穿过去；扩到上限就把剩余边界当作"量到这里为止"。
     */
    public static final class Flood {

        /** 一刻最多扩多少格：泛洪不是这一刻的正事，别把刻占满。 */
        static final int CELLS_PER_TICK = 256;

        private final LightingWorld world;
        private final Deque<BlockPos> frontier = new ArrayDeque<>();
        private final Set<BlockPos> seen = new LinkedHashSet<>();
        private final Set<BlockPos> columns = new LinkedHashSet<>();
        private final Set<BlockPos> unloaded = new LinkedHashSet<>();
        private boolean capped;

        public Flood(LightingWorld world, BlockPos start) {
            this.world = world;
            if (world.loaded(start)) {
                frontier.add(start);
                seen.add(start);
            } else {
                unloaded.add(start);
            }
        }

        /** 推进一刻；泛洪走完返回 true。 */
        public boolean tick() {
            int budget = CELLS_PER_TICK;
            while (budget-- > 0 && !frontier.isEmpty()) {
                BlockPos current = frontier.pollFirst();
                columns.add(current);
                if (columns.size() >= MAX_FLOOD_COLUMNS) {
                    capped = true;
                    frontier.clear();
                    return true;
                }
                for (BlockPos next : neighbors(current)) {
                    if (seen.contains(next) || unloaded.contains(next)) continue;
                    if (!world.loaded(next)) {
                        // 没加载的边界先记下来：走过去让它加载之后，下一刻会从这里接着扩。
                        unloaded.add(next);
                        continue;
                    }
                    if (!standable(next)) continue;
                    seen.add(next);
                    frontier.add(next);
                }
            }
            return frontier.isEmpty();
        }

        /** 泛洪走完没有。 */
        public boolean complete() {
            return frontier.isEmpty();
        }

        /** 走完后的可行走列；没走完时是已扩到的部分。 */
        public List<BlockPos> columns() {
            return List.copyOf(columns);
        }

        /** 没加载的边界格：走得到就过去让它加载，走不到就量到这里为止。 */
        public Set<BlockPos> unloadedFrontier() {
            return Set.copyOf(unloaded);
        }

        /** 是否扩到了上限：结果里要写明连通空间只量到这一片。 */
        public boolean capped() {
            return capped;
        }

        /** 一格能不能站住：脚与头能占，脚下那面结实。 */
        private boolean standable(BlockPos feet) {
            if (!SampleRules.passable(world.state(feet)) || !SampleRules.passable(world.state(feet.above()))) {
                return false;
            }
            return world.sturdy(feet.below(), Direction.UP);
        }

        /** 四个水平方向的上下各一格：平走与一格台阶。 */
        private static List<BlockPos> neighbors(BlockPos pos) {
            List<BlockPos> out = new ArrayList<>(12);
            for (Direction direction : new Direction[] {
                    Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                for (int dy = -1; dy <= 1; dy++) {
                    out.add(pos.offset(direction.getStepX(), dy, direction.getStepZ()));
                }
            }
            return out;
        }
    }
}
