// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

/** Creases of the selected blueprint's union, independent of individual block model seams. */
final class PreviewOutlineGeometry {
    private static final double EPSILON = 1e-5;
    private final Map<BlockPos, List<AABB>> boxes = new HashMap<>();
    private final Map<BlockPos, List<Edge>> edges = new LinkedHashMap<>();
    private final PreviewExteriorSpace exterior;

    PreviewOutlineGeometry(PreviewSession session, BlockGetter world) {
        Map<BlockPos, VoxelShape> shapes = new LinkedHashMap<>();
        Map<BlockPos, BlockPos> owners = new HashMap<>();
        session.cells().forEach((pos, state) -> {
            if (!session.includes(pos) || state.isAir()) return;
            VoxelShape shape;
            try { shape = state.getShape(world, pos); }
            catch (RuntimeException unsupportedShape) { shape = Shapes.block(); }
            if (shape.isEmpty()) shape = Shapes.block();
            // Some selection shapes protrude into adjacent cells (fences, for example).
            // Partition their union into cells so empty-space connectivity stays exact.
            for (AABB box : shape.toAabbs()) {
                for (int x = (int)Math.floor(box.minX); x < Math.ceil(box.maxX); x++)
                    for (int y = (int)Math.floor(box.minY); y < Math.ceil(box.maxY); y++)
                        for (int z = (int)Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                            BlockPos cell = pos.offset(x, y, z);
                            VoxelShape fragment = Shapes.box(Math.max(0, box.minX-x), Math.max(0, box.minY-y), Math.max(0, box.minZ-z),
                                    Math.min(1, box.maxX-x), Math.min(1, box.maxY-y), Math.min(1, box.maxZ-z));
                            shapes.merge(cell, fragment, Shapes::or); owners.putIfAbsent(cell, pos);
                        }
            }
        });
        shapes.forEach((pos, shape) -> boxes.put(pos, shape.toAabbs()));
        exterior = new PreviewExteriorSpace(shapes);
        Set<Edge> emitted = new HashSet<>();
        shapes.forEach((pos, shape) -> shape.forAllEdges((a,b,c,d,e,f) -> {
            double[] start = {a+pos.getX(), b+pos.getY(), c+pos.getZ()};
            double[] end = {d+pos.getX(), e+pos.getY(), f+pos.getZ()};
            int axis = Math.abs(a-d) > EPSILON ? 0 : Math.abs(b-e) > EPSILON ? 1 : 2;
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
                        if (emitted.add(edge)) edges.computeIfAbsent(owners.get(pos), key -> new ArrayList<>()).add(edge);
                    }
                }
                previous = cut;
            }
        }));
    }

    void emit(BlockPos pos, BlockPos origin, VertexConsumer lines) {
        for (Edge edge : edges.getOrDefault(pos, List.of())) {
            Vec3 normal = edge.to.subtract(edge.from).normalize();
            for (Vec3 point : List.of(edge.from, edge.to))
                lines.addVertex((float)(point.x-origin.getX()), (float)(point.y-origin.getY()), (float)(point.z-origin.getZ()))
                        .setColor(.2f, .65f, 1, .65f).setNormal((float)normal.x, (float)normal.y, (float)normal.z);
        }
    }

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
    private static int planes(double a, double b, Direction low, Direction high) {
        if (Math.abs(a - b) > 1e-6) return 0;
        return Math.abs(a) < 1e-6 ? 1 << low.ordinal() : Math.abs(a - 1) < 1e-6 ? 1 << high.ordinal() : 0;
    }
    private static float expand(double value) { return (float) (.5 + (value - .5) * 1.004); }
}
