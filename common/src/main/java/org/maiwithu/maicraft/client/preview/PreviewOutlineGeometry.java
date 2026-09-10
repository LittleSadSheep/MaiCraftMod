// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 把所选蓝图的方块外形合在一起，只画与外界相通的转折边，避免每块石头之间都出现网格线。
 * 这是整份所选计划的几何计算；红色待清除边框则用下方静态方法按现场方块单独绘制。
 */
final class PreviewOutlineGeometry {
    private static final double EPSILON = 1e-5;
    private final Map<BlockPos, List<AABB>> boxes;
    private final Map<BlockPos, List<Edge>> edges;
    private PreviewExteriorSpace exterior;

    private PreviewOutlineGeometry() { boxes = new HashMap<>(); edges = new LinkedHashMap<>(); }

    PreviewOutlineGeometry(PreviewSession session, BlockGetter world) {
        Builder builder = new Builder(session, world);
        while (!builder.done()) builder.step();
        boxes = builder.geometry.boxes; edges = builder.geometry.edges; exterior = builder.geometry.exterior;
    }

    /** 创建时不取形状；每次推进一格、一个跨格片段、一步空隙连通或一条候选边。 */
    static final class Builder {
        private enum Stage { SHAPES, BOXES, EXTERIOR, EDGES, DONE }
        private final PreviewOutlineGeometry geometry = new PreviewOutlineGeometry();
        private final Iterator<Map.Entry<BlockPos, BlockState>> input;
        private final BlockGetter world;
        private final int minY, maxY;
        private final Map<BlockPos, VoxelShape> shapes = new LinkedHashMap<>();
        private final Map<BlockPos, BlockPos> owners = new HashMap<>();
        private final Set<Edge> emitted = new HashSet<>();
        private Iterator<AABB> sourceBoxes = java.util.Collections.emptyIterator();
        private Iterator<Map.Entry<BlockPos, VoxelShape>> fragments;
        private Iterator<CandidateEdge> candidates = java.util.Collections.emptyIterator();
        private PreviewExteriorSpace.Builder exterior;
        private BlockPos sourcePos, edgePos;
        private AABB box;
        private int x, y, z;
        private Stage stage = Stage.SHAPES;

        Builder(PreviewSession session, BlockGetter world) {
            input = session.cells().entrySet().iterator(); this.world = world;
            minY = session.minY(); maxY = session.maxY();
        }
        boolean done() { return stage == Stage.DONE; }
        PreviewOutlineGeometry result() {
            if (!done()) throw new IllegalStateException("preview outline is not ready");
            return geometry;
        }

        void step() {
            switch (stage) {
                case SHAPES -> collectShape();
                case BOXES -> {
                    if (fragments.hasNext()) {
                        var entry = fragments.next(); geometry.boxes.put(entry.getKey(), entry.getValue().toAabbs());
                    } else { exterior = new PreviewExteriorSpace.Builder(shapes); stage = Stage.EXTERIOR; }
                }
                case EXTERIOR -> {
                    if (!exterior.done()) exterior.step();
                    else {
                        geometry.exterior = exterior.result(); exterior = null;
                        fragments = shapes.entrySet().iterator(); stage = Stage.EDGES;
                    }
                }
                case EDGES -> {
                    if (candidates.hasNext()) geometry.addEdge(edgePos, owners.get(edgePos), candidates.next(), emitted);
                    else if (fragments.hasNext()) {
                        var entry = fragments.next(); edgePos = entry.getKey();
                        List<CandidateEdge> next = new ArrayList<>();
                        entry.getValue().forAllEdges((a,b,c,d,e,f) -> next.add(new CandidateEdge(a,b,c,d,e,f)));
                        candidates = next.iterator();
                    } else stage = Stage.DONE;
                }
                case DONE -> { }
            }
        }

        private void collectShape() {
            if (box != null) {
                // 伸出本格的外形也逐片段推进，不能把整个跨格体积放进一次无预算的循环。
                BlockPos cell = sourcePos.offset(x, y, z);
                VoxelShape fragment = Shapes.box(Math.max(0, box.minX-x), Math.max(0, box.minY-y), Math.max(0, box.minZ-z),
                        Math.min(1, box.maxX-x), Math.min(1, box.maxY-y), Math.min(1, box.maxZ-z));
                shapes.merge(cell, fragment, Shapes::or); owners.putIfAbsent(cell, sourcePos);
                if (++z >= Math.ceil(box.maxZ)) {
                    z = (int)Math.floor(box.minZ);
                    if (++y >= Math.ceil(box.maxY)) {
                        y = (int)Math.floor(box.minY);
                        if (++x >= Math.ceil(box.maxX)) box = null;
                    }
                }
            } else if (sourceBoxes.hasNext()) {
                box = sourceBoxes.next();
                x = (int)Math.floor(box.minX); y = (int)Math.floor(box.minY); z = (int)Math.floor(box.minZ);
                if (x >= Math.ceil(box.maxX) || y >= Math.ceil(box.maxY) || z >= Math.ceil(box.maxZ)) box = null;
            } else if (input.hasNext()) {
                var entry = input.next(); sourcePos = entry.getKey(); BlockState state = entry.getValue();
                if (sourcePos.getY() < minY || sourcePos.getY() > maxY || state.isAir()) return;
                VoxelShape shape;
                try { shape = state.getShape(world, sourcePos); }
                catch (RuntimeException unsupportedShape) { shape = Shapes.block(); }
                if (shape.isEmpty()) shape = Shapes.block();
                sourceBoxes = shape.toAabbs().iterator();
            } else { fragments = shapes.entrySet().iterator(); stage = Stage.BOXES; }
        }
    }

    private record CandidateEdge(double a, double b, double c, double d, double e, double f) {}

    // 保留原来的切段、外部连通和去重规则，只把调用边界缩小到一条候选边。
    private void addEdge(BlockPos pos, BlockPos owner, CandidateEdge candidate, Set<Edge> emitted) {
        double[] start = {candidate.a+pos.getX(), candidate.b+pos.getY(), candidate.c+pos.getZ()};
        double[] end = {candidate.d+pos.getX(), candidate.e+pos.getY(), candidate.f+pos.getZ()};
        int axis = Math.abs(candidate.a-candidate.d) > EPSILON ? 0 : Math.abs(candidate.b-candidate.e) > EPSILON ? 1 : 2;
        if (end[axis] < start[axis]) { double[] swap = start; start = end; end = swap; }
        TreeSet<Double> cuts = new TreeSet<>(); cuts.add(start[axis]); cuts.add(end[axis]);
        for (BlockPos neighbor : quadrants(start, axis)) for (AABB box : boxes.getOrDefault(neighbor, List.of())) {
            double offset = coordinate(neighbor, axis);
            for (double cut : new double[]{box.min(Direction.Axis.values()[axis])+offset, box.max(Direction.Axis.values()[axis])+offset})
                if (cut > start[axis] && cut < end[axis]) cuts.add(cut);
        }
        Double previous = null;
        for (double cut : cuts) {
            if (previous != null && cut-previous > EPSILON) {
                double[] point = start.clone(); point[axis] = (previous+cut)/2;
                if (crease(point, axis)) {
                    double[] from = start.clone(), to = start.clone(); from[axis] = previous; to[axis] = cut;
                    Edge edge = new Edge(new Vec3(from[0], from[1], from[2]), new Vec3(to[0], to[1], to[2]));
                    if (emitted.add(edge)) edges.computeIfAbsent(owner, key -> new ArrayList<>()).add(edge);
                }
            }
            previous = cut;
        }
    }

    void emit(BlockPos pos, BlockPos origin, VertexConsumer lines) {
        for (Edge edge : edges.getOrDefault(pos, List.of())) {
            Vec3 normal = edge.to.subtract(edge.from).normalize();
            for (Vec3 point : List.of(edge.from, edge.to))
                lines.addVertex((float)(point.x-origin.getX()), (float)(point.y-origin.getY()), (float)(point.z-origin.getZ()))
                        .setColor(.2f, .65f, 1, .65f).setNormal((float)normal.x, (float)normal.y, (float)normal.z);
        }
    }

    // 在边的四周各取一点：只有一边或三边有实体，或两边呈对角接触时才形成转折；还必须能接触外部空气。
    private boolean crease(double[] point, int axis) {
        int occupied = 0; boolean outside = false;
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            double[] sample = sample(point, axis, quadrant);
            BlockPos pos = BlockPos.containing(sample[0], sample[1], sample[2]);
            boolean filled = boxes.getOrDefault(pos, List.of()).stream().anyMatch(box ->
                    box.contains(sample[0]-pos.getX(), sample[1]-pos.getY(), sample[2]-pos.getZ()));
            if (filled) occupied |= 1 << quadrant;
            else outside |= exterior.contains(sample[0], sample[1], sample[2]);
        }
        int count = Integer.bitCount(occupied);
        return outside && (count == 1 || count == 3 || occupied == 6 || occupied == 9);
    }

    // 找一条边周围四个方向所落入的格子，用来收集可能截断这条边的形状。
    private static Set<BlockPos> quadrants(double[] point, int axis) {
        Set<BlockPos> result = new HashSet<>();
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            double[] sample = sample(point, axis, quadrant); sample[axis] += EPSILON;
            result.add(BlockPos.containing(sample[0], sample[1], sample[2]));
        }
        return result;
    }

    private static double[] sample(double[] point, int axis, int quadrant) {
        double[] sample = point.clone();
        sample[(axis+1)%3] += (quadrant & 1) == 0 ? -EPSILON : EPSILON;
        sample[(axis+2)%3] += (quadrant & 2) == 0 ? -EPSILON : EPSILON;
        return sample;
    }
    private static int coordinate(BlockPos pos, int axis) { return axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ(); }
    private record Edge(Vec3 from, Vec3 to) {}

    // 现场红色清除轮廓先按原版面剔除规则判断哪些面露在外面，完全藏住的面不重复画。
    static int exposedFaces(BlockState state, BlockGetter world, BlockPos pos) {
        int mask = 0;
        for (Direction face : Direction.values())
            if (Block.shouldRenderFace(state, world, pos, face, pos.relative(face))) mask |= 1 << face.ordinal();
        return mask;
    }

    static void emit(BlockState state, BlockGetter world, BlockPos pos, BlockPos origin,
                     VertexConsumer lines, float r, float g, float b) {
        int faces = exposedFaces(state, world, pos);
        if (faces == 0) return;
        var shape = state.getShape(world, pos);
        if (shape.isEmpty()) shape = Shapes.block();
        BlockPos local = pos.subtract(origin);
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            int edgeFaces = planes(x1, x2, Direction.WEST, Direction.EAST)
                    | planes(y1, y2, Direction.DOWN, Direction.UP)
                    | planes(z1, z2, Direction.NORTH, Direction.SOUTH);
            if (edgeFaces != 0 && (edgeFaces & faces) == 0) return;
            double length = Math.sqrt((x2-x1)*(x2-x1) + (y2-y1)*(y2-y1) + (z2-z1)*(z2-z1));
            if (length < 1e-8) return;
            float nx = (float) ((x2-x1)/length), ny = (float) ((y2-y1)/length), nz = (float) ((z2-z1)/length);
            lines.addVertex(expand(x1) + local.getX(), expand(y1) + local.getY(), expand(z1) + local.getZ())
                    .setColor(r, g, b, .55f).setNormal(nx, ny, nz);
            lines.addVertex(expand(x2) + local.getX(), expand(y2) + local.getY(), expand(z2) + local.getZ())
                    .setColor(r, g, b, .55f).setNormal(nx, ny, nz);
        });
    }
    // 判断一条边是否位于方块的最小或最大边界面；用很小容差处理浮点误差。
    private static int planes(double a, double b, Direction low, Direction high) {
        if (Math.abs(a - b) > 1e-6) return 0;
        return Math.abs(a) < 1e-6 ? 1 << low.ordinal() : Math.abs(a - 1) < 1e-6 ? 1 << high.ordinal() : 0;
    }
    private static float expand(double value) { return (float) (.5 + (value - .5) * 1.004); }
}
