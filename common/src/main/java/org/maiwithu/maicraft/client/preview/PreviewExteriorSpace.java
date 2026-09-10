// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 判断某个空隙是否连到蓝图外面，让完全封闭的内部空腔不产生外轮廓。
 * 沿 x 把长段空白压成一个区间，不为每个空气格创建对象，因此相距很远的两块也不必填满中间体积。
 */
final class PreviewExteriorSpace {
    private final Map<BlockPos, Column> columns;
    private final Map<BlockPos, List<Node>> partial;

    private PreviewExteriorSpace() { columns = new HashMap<>(); partial = new HashMap<>(); }

    // 同步入口与分帧入口使用同一套步骤，避免两套空腔判断逐渐分歧。
    PreviewExteriorSpace(Map<BlockPos, VoxelShape> shapes) {
        Builder builder = new Builder(shapes);
        while (!builder.done()) builder.step();
        columns = builder.space.columns; partial = builder.space.partial;
    }

    /** 每步只处理一个格子、一个空隙或一对候选邻接；长行的连通扫描也能让出本帧。 */
    static final class Builder {
        private enum Stage { INDEX, VOIDS, WITHIN_ROWS, BETWEEN_ROWS, DONE }
        private static final List<Direction> SIDES = List.of(Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH);
        private final PreviewExteriorSpace space = new PreviewExteriorSpace();
        private final Map<BlockPos, TreeMap<Integer, VoxelShape>> occupied = new HashMap<>();
        private final Iterator<Map.Entry<BlockPos, VoxelShape>> input;
        private Iterator<Map.Entry<BlockPos, TreeMap<Integer, VoxelShape>>> rows;
        private Iterator<Map.Entry<Integer, VoxelShape>> blocks = java.util.Collections.emptyIterator();
        private Iterator<Map.Entry<BlockPos, Column>> columns;
        private Iterator<Node> outside;
        private BlockPos rowPosition;
        private Column row;
        private int start, sideIndex;
        private Connection connection;
        private Stage stage = Stage.INDEX;

        Builder(Map<BlockPos, VoxelShape> shapes) { input = shapes.entrySet().iterator(); }
        boolean done() { return stage == Stage.DONE; }
        PreviewExteriorSpace result() {
            if (!done()) throw new IllegalStateException("exterior connectivity is not ready");
            return space;
        }

        void step() {
            switch (stage) {
                case INDEX -> {
                    if (input.hasNext()) {
                        var entry = input.next(); BlockPos pos = entry.getKey();
                        occupied.computeIfAbsent(column(pos), key -> new TreeMap<>()).put(pos.getX(), entry.getValue());
                    } else { rows = occupied.entrySet().iterator(); stage = Stage.VOIDS; }
                }
                case VOIDS -> buildVoids();
                case WITHIN_ROWS -> {
                    if (connection != null && !connection.done()) connection.step();
                    else if (columns.hasNext()) {
                        Column next = columns.next().getValue(); connection = new Connection(next, next);
                    } else {
                        columns = space.columns.entrySet().iterator(); connection = null;
                        row = null; stage = Stage.BETWEEN_ROWS;
                    }
                }
                case BETWEEN_ROWS -> connectRows();
                case DONE -> { }
            }
        }

        private void buildVoids() {
            if (blocks.hasNext()) {
                var entry = blocks.next(); int x = entry.getKey();
                if (start < x) row.gap(start, x, rowPosition);
                BlockPos pos = new BlockPos(x, rowPosition.getY(), rowPosition.getZ());
                List<Node> voids = new ArrayList<>(); space.partial.put(pos, voids);
                Shapes.join(Shapes.block(), entry.getValue(), BooleanOp.ONLY_FIRST).forAllBoxes((a,b,c,d,e,f) ->
                        voids.add(new Node(new AABB(a+x, b+pos.getY(), c+pos.getZ(), d+x, e+pos.getY(), f+pos.getZ()))));
                // 格子已按 x 排序，只需排这一格的空隙，避免在单帧排序整条长行。
                voids.sort(Comparator.comparingDouble(node -> node.box.minX)); row.nodes.addAll(voids);
                start = x + 1;
            } else if (row != null) {
                row.gap(start, Integer.MAX_VALUE, rowPosition); row = null;
            } else if (rows.hasNext()) {
                var next = rows.next(); rowPosition = next.getKey(); row = new Column();
                space.columns.put(rowPosition, row); start = Integer.MIN_VALUE;
                blocks = next.getValue().entrySet().iterator();
            } else { columns = space.columns.entrySet().iterator(); stage = Stage.WITHIN_ROWS; }
        }

        private void connectRows() {
            if (connection != null) {
                if (!connection.done()) connection.step();
                else { connection = null; sideIndex++; }
                return;
            }
            if (outside != null) {
                if (outside.hasNext()) {
                    Node node = outside.next();
                    double face = switch (SIDES.get(sideIndex)) {
                        case DOWN -> node.box.minY - rowPosition.getY();
                        case UP -> node.box.maxY - rowPosition.getY() - 1;
                        case NORTH -> node.box.minZ - rowPosition.getZ();
                        default -> node.box.maxZ - rowPosition.getZ() - 1;
                    };
                    if (Math.abs(face) < 1e-8) node.root().outside = true;
                } else { outside = null; sideIndex++; }
                return;
            }
            if (row == null || sideIndex == SIDES.size()) {
                if (!columns.hasNext()) { stage = Stage.DONE; return; }
                var next = columns.next(); rowPosition = next.getKey(); row = next.getValue(); sideIndex = 0;
                return;
            }
            Direction side = SIDES.get(sideIndex);
            Column neighbor = space.columns.get(rowPosition.relative(side));
            if (neighbor == null) outside = row.nodes.iterator();
            else if (side == Direction.UP || side == Direction.SOUTH) connection = new Connection(row, neighbor);
            else sideIndex++;
        }
    }

    // 先找到点所属的行与格内空隙或长空段，再检查这一组空隙是否已连到外界；不存在该行说明那里是外部空间。
    boolean contains(double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        Column column = columns.get(column(pos));
        if (column == null) return true;
        List<Node> voids = partial.get(pos);
        if (voids != null) return voids.stream().anyMatch(node -> node.box.contains(x, y, z) && node.root().outside);
        var gap = column.gaps.floorEntry(pos.getX());
        return gap != null && gap.getValue().box.contains(x, y, z) && gap.getValue().root().outside;
    }

    private static BlockPos column(BlockPos pos) { return new BlockPos(0, pos.getY(), pos.getZ()); }

    private static final class Connection {
        private final Column left, right;
        private int index, first, candidate = -1;
        Connection(Column left, Column right) { this.left = left; this.right = right; }
        boolean done() { return index == left.nodes.size(); }
        void step() {
            Node a = left.nodes.get(index);
            if (candidate < 0) {
                if (first < right.nodes.size() && right.nodes.get(first).box.maxX < a.box.minX) first++;
                else candidate = first;
            } else if (candidate < right.nodes.size() && right.nodes.get(candidate).box.minX <= a.box.maxX) {
                Node b = right.nodes.get(candidate++);
                if (a != b && adjacent(a.box, b.box)) a.join(b);
            } else { index++; candidate = -1; }
        }
    }

    // 只有通过有面积的共同面才算连通；仅在一点或一条边接触，不当作空气通道。
    private static boolean adjacent(AABB a, AABB b) {
        double x = Math.min(a.maxX, b.maxX) - Math.max(a.minX, b.minX);
        double y = Math.min(a.maxY, b.maxY) - Math.max(a.minY, b.minY);
        double z = Math.min(a.maxZ, b.maxZ) - Math.max(a.minZ, b.minZ);
        return Math.abs(x) < 1e-8 && y > 1e-8 && z > 1e-8
                || Math.abs(y) < 1e-8 && x > 1e-8 && z > 1e-8
                || Math.abs(z) < 1e-8 && x > 1e-8 && y > 1e-8;
    }

    private static final class Column {
        final List<Node> nodes = new ArrayList<>();
        final TreeMap<Integer, Node> gaps = new TreeMap<>();
        void gap(int start, int end, BlockPos pos) {
            Node node = new Node(new AABB(start, pos.getY(), pos.getZ(), end, pos.getY()+1, pos.getZ()+1));
            node.outside = start == Integer.MIN_VALUE || end == Integer.MAX_VALUE;
            nodes.add(node); gaps.put(start, node);
        }
    }

    private static final class Node {
        final AABB box;
        Node parent = this;
        int rank;
        boolean outside;
        Node(AABB box) { this.box = box; }
        // 同一片连通空隙共用一个代表节点；查询时压短查找链，避免以后每次绕很多层。
        Node root() {
            if (parent != this) parent = parent.root();
            return parent;
        }
        // 合并相通的两组空隙，任意一组通往外界就让合并后的整组也通往外界。
        void join(Node other) {
            Node a = root(), b = other.root();
            if (a == b) return;
            if (a.rank < b.rank) { Node swap = a; a = b; b = swap; }
            b.parent = a; a.outside |= b.outside;
            if (a.rank == b.rank) a.rank++;
        }
    }
}
