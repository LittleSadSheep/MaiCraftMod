// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;

/** Follows Litematica's visible-side/model-outline approach, using Minecraft 1.21.1 voxel edges. */
final class PreviewOutlineGeometry {
    private PreviewOutlineGeometry() {}

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
