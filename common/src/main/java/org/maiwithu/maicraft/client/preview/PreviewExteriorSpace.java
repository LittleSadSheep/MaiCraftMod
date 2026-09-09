// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final Map<BlockPos, Column> columns = new HashMap<>();
    private final Map<BlockPos, List<Node>> partial = new HashMap<>();

    // 按固定 y/z 整理每一行。整格之间保存长空段，半砖等格子内部再保存剩余空隙，最后把面相接的空隙连起来。
    PreviewExteriorSpace(Map<BlockPos, VoxelShape> shapes) {
        Map<BlockPos, TreeMap<Integer, VoxelShape>> occupied = new HashMap<>();
        shapes.forEach((pos, shape) -> occupied.computeIfAbsent(column(pos), key -> new TreeMap<>()).put(pos.getX(), shape));
        occupied.forEach((key, blocks) -> {
            Column column = new Column(); columns.put(key, column);
            int start = Integer.MIN_VALUE;
            for (var entry : blocks.entrySet()) {
                int x = entry.getKey();
                if (start < x) column.gap(start, x, key);
                BlockPos pos = new BlockPos(x, key.getY(), key.getZ());
                List<Node> voids = new ArrayList<>(); partial.put(pos, voids);
                Shapes.join(Shapes.block(), entry.getValue(), BooleanOp.ONLY_FIRST).forAllBoxes((a,b,c,d,e,f) -> {
                    Node node = new Node(new AABB(a+x, b+pos.getY(), c+pos.getZ(), d+x, e+pos.getY(), f+pos.getZ()));
                    voids.add(node); column.nodes.add(node);
                });
                start = x + 1;
            }
            column.gap(start, Integer.MAX_VALUE, key);
            column.nodes.sort(Comparator.comparingDouble(node -> node.box.minX));
            connect(column, column);
        });
        // 相邻行存在就连通共同开口；相邻行完全没有蓝图实体时，朝那边露出的空隙直接算通往外界。
        columns.forEach((pos, column) -> {
            for (Direction side : List.of(Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH)) {
                Column neighbor = columns.get(pos.relative(side));
                if (neighbor != null) {
                    if (side == Direction.UP || side == Direction.SOUTH) connect(column, neighbor);
                } else for (Node node : column.nodes) {
                    double face = switch (side) {
                        case DOWN -> node.box.minY - pos.getY();
                        case UP -> node.box.maxY - pos.getY() - 1;
                        case NORTH -> node.box.minZ - pos.getZ();
                        default -> node.box.maxZ - pos.getZ() - 1;
                    };
                    if (Math.abs(face) < 1e-8) node.root().outside = true;
                }
            }
        });
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

    // 两行的空隙按 x 排好序，只检查可能相碰的区间，减少逐个两两比较。
    private static void connect(Column left, Column right) {
        int first = 0;
        for (Node a : left.nodes) {
            while (first < right.nodes.size() && right.nodes.get(first).box.maxX < a.box.minX) first++;
            for (int i = first; i < right.nodes.size(); i++) {
                Node b = right.nodes.get(i);
                if (b.box.minX > a.box.maxX) break;
                if (a != b && adjacent(a.box, b.box)) a.join(b);
            }
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
