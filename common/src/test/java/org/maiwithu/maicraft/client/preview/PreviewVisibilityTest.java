// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Actual Minecraft occlusion/voxel geometry with a frozen blueprint and a changing real-world view. */
public final class PreviewVisibilityTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int x = 3; x < 6; x++) for (int y = 0; y < 3; y++) for (int z = 3; z < 6; z++)
            blocks.put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        PreviewSession session = new PreviewSession("visibility", "minecraft:overworld", "solid cube", blocks);
        World real = new World(); PreviewWorldView view = new PreviewWorldView(session, real);
        BlockPos center = new BlockPos(4, 1, 4); BlockState stone = Blocks.STONE.defaultBlockState();
        int faces = blocks.keySet().stream().mapToInt(pos -> Integer.bitCount(PreviewOutlineGeometry.exposedFaces(stone, view, pos))).sum();
        check(faces == 54, "27 cubes retain only the 54 exterior faces, including across section boundaries");
        Counter lines = new Counter(); PreviewOutlineGeometry.emit(stone, view, center, BlockPos.ZERO, lines, 1, 1, 1);
        check(lines.vertices == 0, "fully enclosed target emits no internal outline");
        session.layers(1, 1);
        int exposed = PreviewOutlineGeometry.exposedFaces(stone, view, center);
        check(exposed == (1 << Direction.UP.ordinal() | 1 << Direction.DOWN.ordinal()), "slicing exposes the new top and bottom surfaces");
        real.blocks.put(center.above(), stone);
        check(PreviewOutlineGeometry.exposedFaces(stone, view, center) == 1 << Direction.DOWN.ordinal(), "actual world ceiling still occludes a sliced blueprint");
        BlockPos outside = new BlockPos(2, 1, 4); real.blocks.put(outside, stone);
        check(view.getBlockState(outside).equals(stone), "undeclared neighbors use real world occlusion");
        real.blocks.remove(outside);
        check(view.getBlockState(outside).isAir(), "removing real neighbor exposes the preview without changing its frozen plan");
        Map<BlockPos, BlockState> slab = Map.of(BlockPos.ZERO, Blocks.OAK_SLAB.defaultBlockState());
        PreviewWorldView slabView = new PreviewWorldView(new PreviewSession("slab", "minecraft:overworld", "slab", slab), new World());
        Counter slabLines = new Counter(); PreviewOutlineGeometry.emit(slab.get(BlockPos.ZERO), slabView, BlockPos.ZERO,
                BlockPos.ZERO, slabLines, 1, 1, 1);
        check(slabLines.vertices > 0 && slabLines.maxY <= .501f, "slab outline follows the model shape rather than a full block cage");
        check(session.cells().equals(blocks), "visibility never modifies the construction blueprint");
        System.out.println("PreviewVisibilityTest: passed");
    }
    private static final class Counter implements VertexConsumer {
        int vertices; float maxY = -Float.MAX_VALUE;
        public VertexConsumer addVertex(float x, float y, float z) { vertices++; maxY = Math.max(maxY, y); return this; }
        public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        public VertexConsumer setUv(float u, float v) { return this; }
        public VertexConsumer setUv1(int u, int v) { return this; }
        public VertexConsumer setUv2(int u, int v) { return this; }
        public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
    private static final class World implements BlockAndTintGetter {
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
        public float getShade(Direction direction, boolean shade) { return 1; }
        public LevelLightEngine getLightEngine() { throw new AssertionError("geometry does not query lighting"); }
        public int getBlockTint(BlockPos pos, ColorResolver resolver) { return 0xffffff; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
