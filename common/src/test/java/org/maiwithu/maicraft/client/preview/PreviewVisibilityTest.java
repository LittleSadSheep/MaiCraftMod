// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
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
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        incrementalExterior();
        lazyOutline();
        preparedFrames();
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
        wholeStructureOutlines(blocks);
        System.out.println("PreviewVisibilityTest: passed");
    }

    private static void wholeStructureOutlines(Map<BlockPos, BlockState> cube) {
        PreviewSession session = new PreviewSession("shell", "minecraft:overworld", "shell", cube);
        Counter solid = outline(session);
        check(Math.abs(solid.length - 36) < 1e-6, "3x3x3 cube has only its twelve outer edges, without coplanar block seams");
        check(solid.vertices == 72, "outline segments shared by cells are emitted once across mesh sections");
        Map<BlockPos, BlockState> hollow = new LinkedHashMap<>(cube);
        hollow.remove(new BlockPos(4, 1, 4));
        Counter shell = outline(new PreviewSession("hollow", "minecraft:overworld", "hollow", hollow));
        check(shell.segments.equals(solid.segments), "sealed interior air contributes no outline, even with translucent walls");
        hollow.put(new BlockPos(4, 2, 4), Blocks.OAK_SLAB.defaultBlockState());
        Counter closedSlabRoof = outline(new PreviewSession("slab roof", "minecraft:overworld", "slab roof", hollow));
        check(closedSlabRoof.length > shell.length, "a slab roof adds a visible inset on its exterior side");
        check(closedSlabRoof.segments.stream().noneMatch(line -> line.contains("[4.0, 1.0, 4.0]")),
                "a half-height roof still seals the interior cavity");
        hollow.remove(new BlockPos(4, 2, 4));
        Counter open = outline(new PreviewSession("open", "minecraft:overworld", "open", hollow));
        check(open.length > shell.length, "opening the roof makes the cavity connected to exterior air");
        session.layers(1, 1);
        check(Math.abs(outline(session).length - 28) < 1e-6, "layer slicing recomputes the selected slab's exterior perimeter");
        Map<BlockPos, BlockState> slabs = Map.of(BlockPos.ZERO, Blocks.OAK_SLAB.defaultBlockState(),
                BlockPos.ZERO.east(), Blocks.OAK_SLAB.defaultBlockState());
        Counter slab = outline(new PreviewSession("slabs", "minecraft:overworld", "slabs", slabs));
        check(Math.abs(slab.length - 14) < 1e-6 && slab.maxY == .5f, "joined slabs preserve their half-height outline and remove the shared seam");
        Counter step = outline(new PreviewSession("step", "minecraft:overworld", "step",
                Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), BlockPos.ZERO.east(), Blocks.OAK_SLAB.defaultBlockState())));
        check(Math.abs(step.length - 18) < 1e-6, "different neighboring shapes split their shared edges at the half-height crease");
        Counter stair = outline(new PreviewSession("stair", "minecraft:overworld", "stair",
                Map.of(BlockPos.ZERO, Blocks.OAK_STAIRS.defaultBlockState())));
        check(Math.abs(stair.length - 14) < 1e-6, "stair geometry retains its step rather than collapsing to a bounding box");
        Map<BlockPos, BlockState> sparse = Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState(),
                new BlockPos(1_000_000, 1_000_000, 1_000_000), Blocks.STONE.defaultBlockState());
        check(Math.abs(outline(new PreviewSession("sparse", "minecraft:overworld", "sparse", sparse)).length - 24) < 1e-6,
                "disconnected sparse components do not require traversing their bounding volume");
    }

    private static void incrementalExterior() {
        Map<BlockPos, net.minecraft.world.phys.shapes.VoxelShape> shell = new LinkedHashMap<>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++)
            if (x != 1 || y != 1 || z != 1) shell.put(new BlockPos(x, y, z), net.minecraft.world.phys.shapes.Shapes.block());
        for (boolean open : new boolean[]{false, true}) {
            if (open) shell.remove(new BlockPos(1, 1, 0));
            var builder = new PreviewExteriorSpace.Builder(shell);
            check(!builder.done(), "creating an exterior job must not calculate its connectivity");
            try { builder.result(); throw new AssertionError("partial connectivity was exposed"); }
            catch (IllegalStateException expected) { }
            int steps = 0;
            while (!builder.done() && steps++ < 10_000) builder.step();
            check(builder.done() && steps > shell.size(), "connectivity must advance through resumable stages");
            check(builder.result().contains(1.5, 1.5, 1.5) == open, "opening one wall must connect the inner air to outside");
            check(builder.result().contains(4, 1.5, 1.5), "unbounded space beside the shell remains outside");
        }
    }

    private static Counter outline(PreviewSession session) {
        PreviewOutlineGeometry geometry = new PreviewOutlineGeometry(session, new PreviewWorldView(session, new World()));
        Counter counter = new Counter();
        session.cells().keySet().forEach(pos -> geometry.emit(pos, BlockPos.ZERO, counter));
        return counter;
    }

    private static void lazyOutline() throws Exception {
        int[] reads = {0};
        var session = new PreviewSession("lazy", "minecraft:overworld", "cross-cell shape",
                Map.of(BlockPos.ZERO, crossCellState(reads)));
        var builder = new PreviewOutlineGeometry.Builder(session, new PreviewWorldView(session, new World()));
        check(reads[0] == 0 && !builder.done(), "constructing the outline job must not evaluate any block shape");
        try { builder.result(); throw new AssertionError("an unfinished outline was exposed"); }
        catch (IllegalStateException expected) { }
        builder.step();
        check(reads[0] == 1 && !builder.done(), "one source read must not complete clipping and connectivity too");
        int steps = 1;
        while (!builder.done() && steps++ < 1000) builder.step();
        Counter counter = new Counter(); builder.result().emit(BlockPos.ZERO, BlockPos.ZERO, counter);
        check(builder.done() && steps > 10 && reads[0] == 1, "resuming must not repeat the source shape callback");
        check(Math.abs(counter.length - 16) < 1e-6, "fragmented two-cell shapes keep their exact joined perimeter");
    }

    private static void preparedFrames() throws Exception {
        int[] reads = {0}; BlockState state = crossCellState(reads);
        BlockPos low = new BlockPos(-1, 0, 0), high = new BlockPos(8, 4, 0), side = new BlockPos(-5, 0, 0);
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>(); cells.put(low, state); cells.put(high, state);
        var parts = List.of(new PreviewPart(low, "ae2:glass_cable", "center"),
                new PreviewPart(high, "ae2:glass_cable", "center"), new PreviewPart(side, "ae2:terminal", "east"));
        var session = new PreviewSession("frames", "minecraft:overworld", "two layers", cells, parts);
        var preparation = new PreviewRenderer.Preparation(session, new World());
        preparation.advance(new PreviewFrameBudget(() -> 0, 0, 10_000, 64, 1));
        check(reads[0] == 0, "an expired frame cannot start a source shape query");
        notReady(preparation);
        // The first shape consumes the time left in this frame; the second must wait for another frame.
        preparation.advance(new PreviewFrameBudget(() -> reads[0] == 0 ? 0 : 100, 100, 10_000, 64, 1));
        check(reads[0] == 1, "shape preparation yields when the shared frame deadline is reached");
        notReady(preparation);
        int frames = finish(preparation);
        var ready = preparation.result();
        check(frames > 10 && reads[0] == 2, "one-step frames resume every stage without rereading earlier source shapes");
        check(ready.sections().stream().map(section -> section.origin).toList().equals(
                List.of(new BlockPos(-4, 0, 0), new BlockPos(8, 4, 0), new BlockPos(-8, 0, 0))),
                "incremental grouping preserves cell/part order and negative four-block section boundaries");
        check(ready.sections().stream().mapToInt(section -> section.cells.size()).sum() == 2
                && ready.sections().stream().mapToInt(section -> section.parts.size()).sum() == 3,
                "all planned cells and multipart entries survive grouping");
        check(ready.centres().equals(java.util.Set.of(low, high)), "only centre parts join the connection set");
        Counter both = new Counter(); cells.keySet().forEach(pos -> ready.outline().emit(pos, BlockPos.ZERO, both));
        check(Math.abs(both.length - 32) < 1e-6, "published geometry retains both separated cross-cell outlines");

        var superseded = new PreviewRenderer.Preparation(session, new World());
        superseded.advance(new PreviewFrameBudget(() -> 0, 100, 6, 64, 1));
        int beforeSlice = reads[0]; session.layers(0, 0);
        superseded.advance(new PreviewFrameBudget(() -> 0, 100, 10_000, 64, 1));
        notReady(superseded);
        check(reads[0] == beforeSlice, "changing layers stops the superseded preparation before another shape query");
        notReady(preparation);
        var sliced = new PreviewRenderer.Preparation(session, new World()); finish(sliced);
        var slice = sliced.result(); Counter outline = new Counter();
        cells.keySet().forEach(pos -> slice.outline().emit(pos, BlockPos.ZERO, outline));
        check(reads[0] == beforeSlice + 1 && Math.abs(outline.length - 16) < 1e-6
                        && slice.centres().equals(java.util.Set.of(low)),
                "replacement preparation uses only the selected layer for outlines and centre connections");
        check(session.decision() == PreviewSession.Decision.WAITING, "preparation never grants construction approval");
        var cancelled = new PreviewRenderer.Preparation(session, new World());
        cancelled.advance(new PreviewFrameBudget(() -> 0, 100, 6, 64, 1));
        int beforeCancel = reads[0]; session.cancel();
        cancelled.advance(new PreviewFrameBudget(() -> 0, 100, 10_000, 64, 1));
        notReady(cancelled);
        sliced.advance(new PreviewFrameBudget(() -> 0, 100, 10_000, 64, 1));
        notReady(sliced);
        check(reads[0] == beforeCancel && session.decision() == PreviewSession.Decision.CANCELLED,
                "cancelled previews cannot resume or publish either partial or completed preparation");
        var multipart = new PreviewRenderer.Preparation(new PreviewSession("parts", "minecraft:overworld",
                "parts only", Map.of(), parts), new World());
        finish(multipart);
        Counter empty = new Counter(); multipart.result().outline().emit(low, BlockPos.ZERO, empty);
        check(multipart.result().sections().size() == 3 && empty.vertices == 0,
                "multipart-only previews finish even when there are no block shapes or outline edges");
    }

    private static int finish(PreviewRenderer.Preparation preparation) {
        int frames = 0;
        while (!preparation.done() && frames++ < 10_000)
            preparation.advance(new PreviewFrameBudget(() -> 0, 100, 1, 64, 1));
        check(preparation.done(), "preparation must finish when subsequent frames supply work");
        return frames;
    }
    private static void notReady(PreviewRenderer.Preparation preparation) {
        check(!preparation.done(), "unfinished or invalidated preparation is not ready");
        try { preparation.result(); throw new AssertionError("unfinished or invalidated geometry was published"); }
        catch (IllegalStateException expected) { }
    }

    private static BlockState crossCellState(int[] reads) throws Exception {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var holders = net.minecraft.core.MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
        var frozen = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
        holders.setAccessible(true); frozen.setAccessible(true);
        Object priorHolders = holders.get(registry); boolean priorFrozen = frozen.getBoolean(registry);
        // Give this unregistered fixture a private holder, then restore the global registry before testing.
        try {
            holders.set(registry, new java.util.IdentityHashMap<>()); frozen.setBoolean(registry, false);
            return new net.minecraft.world.level.block.Block(
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().dynamicShape()) {
                @Override public net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,
                        net.minecraft.world.level.BlockGetter world, BlockPos pos,
                        net.minecraft.world.phys.shapes.CollisionContext context) {
                    reads[0]++;
                    return net.minecraft.world.phys.shapes.Shapes.box(0, 0, 0, 2, 1, 1);
                }
            }.defaultBlockState();
        } finally {
            holders.set(registry, priorHolders); frozen.setBoolean(registry, priorFrozen);
        }
    }
    private static final class Counter implements VertexConsumer {
        int vertices; float maxY = -Float.MAX_VALUE;
        double length; float[] start;
        final List<String> segments = new ArrayList<>();
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices++; maxY = Math.max(maxY, y);
            if ((vertices & 1) == 1) start = new float[]{x, y, z};
            else {
                length += Math.sqrt((x-start[0])*(x-start[0]) + (y-start[1])*(y-start[1]) + (z-start[2])*(z-start[2]));
                segments.add(java.util.Arrays.toString(start) + ":" + java.util.Arrays.toString(new float[]{x,y,z}));
            }
            return this;
        }
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
