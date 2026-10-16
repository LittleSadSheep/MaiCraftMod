// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * 铁轨线路的只读判断，纯函数：四种铁轨（普通、动力、探测、激活）连不连得成一条线，
 * 一辆车能不能沿着线路被送到头。连通按原版的轨形来——每种轨形只有两个接口，丁字路口的第三根
 * 物理相邻的铁轨不算连着；坡道接口在上下一格；拐弯按原版四个弯的接口。
 * 能不能送到头按一个保守的动量估计：通电的动力轨把车提到满速（满速的车平路上能滑很远），
 * 没通电的动力轨是刹车（车到那里一格格内停住），下坡回动量、上坡费动量，起步没电靠人推只够滑几格。
 * 动力轨通没电读它自己的 POWERED 方块状态：红石供了电这个状态就是真的，不用再猜电路。
 */
public final class RailLine {

    /** 一格方块状态的读端：没加载的格没有证据，给空。 */
    public interface ReadsCells {
        Optional<BlockState> stateAt(BlockPos cell);
    }

    /** 满速的车在平路普通铁轨上还能滑多少格（保守估计：动力轨间隔三四十格仍能保住高速，取更宽裕的值）。 */
    static final double BOOSTED = 64.0;
    /** 人从后面推一下，车只够滑这么几格，之后就得靠通电的动力轨或下坡。 */
    static final double PUSHED = 6.0;
    /** 每往下一格，动量按这么多格的平路补回（保守估计，实际坡道加速比这更足）。 */
    static final double DOWNHILL_REGAIN = 24.0;
    /** 每往上一格，除正常消耗外再额外费这么多格的动量。 */
    static final double CLIMB_EXTRA = 16.0;

    private RailLine() {}

    /** 送到头的结论。 */
    public sealed interface Delivery {
        /** 能送到：线路走了哪些格、一共多长。 */
        record Deliverable(List<BlockPos> route) implements Delivery {}

        /** 送不到头：一句原因。 */
        record Blocked(String reason) implements Delivery {}
    }

    /** 这一格是不是四种铁轨之一。 */
    public static boolean isRail(BlockState state) {
        return state.is(Blocks.RAIL) || state.is(Blocks.POWERED_RAIL)
                || state.is(Blocks.DETECTOR_RAIL) || state.is(Blocks.ACTIVATOR_RAIL);
    }

    /** 这一格是不是动力铁轨。 */
    static boolean isPoweredRail(BlockState state) {
        return state.is(Blocks.POWERED_RAIL);
    }

    /** 动力铁轨通没通电：非动力铁轨一律不算通电。 */
    static boolean powered(BlockState state) {
        return state.is(Blocks.POWERED_RAIL)
                && Boolean.TRUE.equals(state.getValue(BlockStateProperties.POWERED));
    }

    /** 这一格的轨形；不是铁轨给空。 */
    static Optional<RailShape> shapeOf(BlockState state) {
        if (state.is(Blocks.RAIL)) {
            return Optional.of(state.getValue(BlockStateProperties.RAIL_SHAPE));
        }
        // 动力、探测、激活铁轨只有直的，轨形属性是另一张（没有弯的）。
        if (state.is(Blocks.POWERED_RAIL) || state.is(Blocks.DETECTOR_RAIL) || state.is(Blocks.ACTIVATOR_RAIL)) {
            return Optional.of(state.getValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT));
        }
        return Optional.empty();
    }

    /** 这一格铁轨的两个接口（按轨形；坡道高端在上下一格，拐弯在平层）。 */
    static List<BlockPos> connections(BlockPos cell, RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH -> List.of(cell.north(), cell.south());
            case EAST_WEST -> List.of(cell.east(), cell.west());
            // 坡道朝某方向"上升"：高端在那个方向的上格，低端在反方向的平层。
            case ASCENDING_EAST -> List.of(cell.east().above(), cell.west());
            case ASCENDING_WEST -> List.of(cell.west().above(), cell.east());
            case ASCENDING_NORTH -> List.of(cell.north().above(), cell.south());
            case ASCENDING_SOUTH -> List.of(cell.south().above(), cell.north());
            case NORTH_WEST -> List.of(cell.north(), cell.west());
            case NORTH_EAST -> List.of(cell.north(), cell.east());
            case SOUTH_WEST -> List.of(cell.south(), cell.west());
            case SOUTH_EAST -> List.of(cell.south(), cell.east());
        };
    }

    /** 这一格铁轨在平层上的两个朝向（坡道也占水平方向的两头）：看对面那格在不在它的水平延长线上。 */
    private static Set<Direction> flatSides(BlockPos cell, RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> Set.of(Direction.NORTH, Direction.SOUTH);
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> Set.of(Direction.EAST, Direction.WEST);
            case NORTH_WEST -> Set.of(Direction.NORTH, Direction.WEST);
            case NORTH_EAST -> Set.of(Direction.NORTH, Direction.EAST);
            case SOUTH_WEST -> Set.of(Direction.SOUTH, Direction.WEST);
            case SOUTH_EAST -> Set.of(Direction.SOUTH, Direction.EAST);
        };
    }

    // 两格铁轨连不连：先看这格的接口里有没有对面；坡顶坡底的接缝对面在水平方向上对得着就算连上——
    // 平层相接要对面接口也对得上（丁字路口的第三根不在轨形接口里，不连）；
    // 这格是坡顶或平台、对面低一格时，对面得是一块朝着这头的坡（或同轴的平轨），车才滑得下去。
    static boolean linked(BlockPos from, RailShape fromShape, BlockPos to, RailShape toShape) {
        if (!connections(from, fromShape).contains(to)) {
            if (from.getY() - to.getY() != 1) return false;
            return flatSides(to, toShape).contains(horizontalOf(from.subtract(to)));
        }
        if (connections(to, toShape).contains(from)) return true;
        if (to.getY() - from.getY() == 1) {
            return flatSides(to, toShape).contains(horizontalOf(from.subtract(to)));
        }
        return false;
    }

    /** 水平位移换成朝向（只看水平分量，斜位移按分量大的靠）。 */
    private static Direction horizontalOf(BlockPos delta) {
        if (Math.abs(delta.getX()) >= Math.abs(delta.getZ())) {
            return delta.getX() >= 0 ? Direction.EAST : Direction.WEST;
        }
        return delta.getZ() >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * 两格铁轨之间找一条线路（广度优先，最短的一条）：两端都按轨形接口接。
     * 找不到或步数超过上限给空；途中有没加载的格按不通往处理（线路没读完，报价那头会再说）。
     */
    public static Optional<List<BlockPos>> route(ReadsCells cells, BlockPos start, BlockPos end, int maxCells) {
        Map<BlockPos, Optional<RailShape>> shapes = new HashMap<>();
        if (shape(cells, shapes, start).isEmpty() || shape(cells, shapes, end).isEmpty()) return Optional.empty();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        queue.add(start);
        cameFrom.put(start, start);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (current.equals(end)) return Optional.of(trace(cameFrom, start, end));
            if (cameFrom.size() > maxCells) return Optional.empty();
            for (BlockPos next : neighbors(cells, shapes, current)) {
                if (!cameFrom.containsKey(next)) {
                    cameFrom.put(next, current);
                    queue.add(next);
                }
            }
        }
        return Optional.empty();
    }

    // 一格铁轨按轨形接口接得上的相邻铁轨格：候选是自己的接口，加上低一格的四方——
    // 坡顶或平台这格的接口不含低一格的那块坡，得从对面那头凑过来判（linked 认得这种接缝）。
    private static List<BlockPos> neighbors(ReadsCells cells, Map<BlockPos, Optional<RailShape>> shapes,
            BlockPos cell) {
        RailShape cellShape = shape(cells, shapes, cell).orElse(null);
        Set<BlockPos> candidates = new LinkedHashSet<>(connections(cell, cellShape));
        for (Direction side : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            candidates.add(cell.relative(side).below());
        }
        List<BlockPos> joined = new ArrayList<>();
        for (BlockPos candidate : candidates) {
            shape(cells, shapes, candidate).ifPresent(sideShape -> {
                if (linked(cell, cellShape, candidate, sideShape)) joined.add(candidate);
            });
        }
        return joined;
    }

    private static Optional<RailShape> shape(ReadsCells cells, Map<BlockPos, Optional<RailShape>> cache,
            BlockPos cell) {
        Optional<RailShape> known = cache.get(cell);
        if (known != null) return known;
        Optional<RailShape> fresh = cells.stateAt(cell).flatMap(RailLine::shapeOf);
        cache.put(cell, fresh);
        return fresh;
    }

    private static List<BlockPos> trace(Map<BlockPos, BlockPos> cameFrom, BlockPos start, BlockPos end) {
        List<BlockPos> route = new ArrayList<>();
        BlockPos current = end;
        while (!current.equals(start)) {
            route.addFirst(current);
            current = cameFrom.get(current);
        }
        route.addFirst(start);
        return List.copyOf(route);
    }

    /**
     * 这条线路能不能把车送到头：从起点沿线路逐格数动量。
     * 起点是通电的动力轨就满速起步，否则算人推的一小段；路上通电的动力轨把动量补满，
     * 没通电的动力轨是刹车（除非就在终点那一格，车本来就要停），下坡补动量、上坡费动量，
     * 动量不够走到下一个补的地方就送不到头，按在哪一格卡住写一句原因。
     */
    public static Delivery delivery(List<BlockPos> route, ReadsCells cells) {
        if (route.size() < 2) return new Delivery.Deliverable(route);
        double momentum = cells.stateAt(route.getFirst()).filter(RailLine::powered).isPresent() ? BOOSTED : PUSHED;
        for (int i = 1; i < route.size(); i++) {
            BlockPos cell = route.get(i);
            Optional<BlockState> state = cells.stateAt(cell);
            if (state.isEmpty()) {
                return new Delivery.Blocked("线路在 " + cell.toShortString() + " 那一段没加载，还看不清能不能送到头");
            }
            if (isPoweredRail(state.get())) {
                if (powered(state.get())) {
                    momentum = BOOSTED;
                } else if (i < route.size() - 1) {
                    return new Delivery.Blocked("线路中间 " + cell.toShortString() + " 有没通电的动力铁轨，"
                            + "车到那里一格格内就会被刹停");
                }
            }
            momentum -= 1.0;
            if (cell.getY() < route.get(i - 1).getY()) {
                momentum = Math.min(BOOSTED, momentum + DOWNHILL_REGAIN);
            }
            if (cell.getY() > route.get(i - 1).getY()) {
                momentum -= CLIMB_EXTRA;
            }
            if (momentum < 0) {
                return new Delivery.Blocked("从 " + cell.toShortString() + " 往后这一段既没有通电的动力铁轨也没有下坡，"
                        + "车滑不到头");
            }
        }
        return new Delivery.Deliverable(route);
    }
}
