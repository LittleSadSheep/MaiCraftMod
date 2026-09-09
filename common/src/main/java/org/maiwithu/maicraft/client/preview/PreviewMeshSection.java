// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Small (at most 64-cell) rebuild unit; finished GPU buffers stay visible across frame budgets. */
final class PreviewMeshSection implements AutoCloseable {
    final BlockPos origin;
    final AABB bounds;
    final List<Map.Entry<BlockPos, BlockState>> cells = new ArrayList<>();
    final List<PreviewPart> parts = new ArrayList<>();
    private final VertexBuffer[] models = new VertexBuffer[3];
    private final MeshData.SortState[] sortStates = new MeshData.SortState[3];
    private final PreviewSectionRefresh refresh = new PreviewSectionRefresh();
    private VertexBuffer outlines, partModels;
    int fallbackModels;

    PreviewMeshSection(BlockPos origin) {
        this.origin = origin;
        bounds = new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + 4, origin.getY() + 4, origin.getZ() + 4);
    }

    boolean built() { return refresh.built(); }

    PreviewSectionRefresh.Work refreshWork(Minecraft minecraft, Vec3 camera, long now) {
        return refresh.required(now, camera, () -> hash(minecraft));
    }

    private int hash(Minecraft minecraft) {
        int hash = 1;
        for (var cell : cells) {
            hash = 31 * hash + stateHash(minecraft, cell.getKey());
            for (Direction face : Direction.values()) hash = 31 * hash + stateHash(minecraft, cell.getKey().relative(face));
        }
        return hash;
    }

    private static int stateHash(Minecraft minecraft, BlockPos pos) {
        return minecraft.level.isLoaded(pos) ? minecraft.level.getBlockState(pos).hashCode() : 0;
    }

    int workSize() { return cells.size() + parts.size(); }

    void rebuild(Minecraft minecraft, PreviewSession session, PreviewWorldView view, Set<BlockPos> centres, PreviewOutlineGeometry outline,
                 Vec3 camera, long now) {
        close();
        fallbackModels = 0;
        try (ByteBufferBuilder solidMemory = new ByteBufferBuilder(262144);
             ByteBufferBuilder cutoutMemory = new ByteBufferBuilder(65536);
             ByteBufferBuilder translucentMemory = new ByteBufferBuilder(65536);
             ByteBufferBuilder partMemory = new ByteBufferBuilder(65536);
             ByteBufferBuilder lineMemory = new ByteBufferBuilder(65536)) {
            BufferBuilder[] model = {
                    new BufferBuilder(solidMemory, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK),
                    new BufferBuilder(cutoutMemory, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK),
                    new BufferBuilder(translucentMemory, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)};
            BufferBuilder line = new BufferBuilder(lineMemory, VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
            BufferBuilder partMesh = new BufferBuilder(partMemory, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            PoseStack pose = new PoseStack();
            RandomSource random = RandomSource.create(0);
            boolean[] modelUsable = {true, true, true};
            for (var cell : cells) {
                BlockPos pos = cell.getKey();
                if (!session.includes(pos)) continue;
                BlockState desired = cell.getValue();
                if (!minecraft.level.isLoaded(pos)) continue;
                outline.emit(pos, origin, line);
                BlockState actual = minecraft.level.getBlockState(pos);
                if (desired.equals(actual)) continue;
                int x = pos.getX() - origin.getX(), y = pos.getY() - origin.getY(), z = pos.getZ() - origin.getZ();
                if (!desired.isAir()) {
                    RenderType layer = ItemBlockRenderTypes.getChunkRenderType(desired);
                    int pass = layer == RenderType.translucent() ? 2 : layer == RenderType.solid() ? 0 : 1;
                    if (desired.getRenderShape() == RenderShape.MODEL && modelUsable[pass]) {
                        pose.pushPose();
                        pose.translate(x, y, z);
                        try {
                            minecraft.getBlockRenderer().renderBatched(desired, pos, view, pose, model[pass], true, random);
                        } catch (RuntimeException unsupportedModel) {
                            // A mod renderer can fail midway through a vertex. Discard this entire
                            // section's model buffer, keep its independent outline buffer valid.
                            modelUsable[pass] = false; fallbackModels++;
                        }
                        finally { pose.popPose(); }
                    } else fallbackModels++;
                }
                if (desired.isAir()) PreviewOutlineGeometry.emit(actual, minecraft.level, pos, origin, line, 1, .2f, .25f);
            }
            for (PreviewPart part : parts) if (session.includes(part.position()))
                PreviewPartGeometry.emit(part, origin, centres, partMesh, line);
            for (int pass = 0; pass < model.length; pass++) {
                MeshData mesh = modelUsable[pass] ? model[pass].build() : null;
                if (mesh == null) continue;
                ByteBufferBuilder memory = pass == 0 ? solidMemory : pass == 1 ? cutoutMemory : translucentMemory;
                sortStates[pass] = mesh.sortQuads(memory, sorting(origin, camera));
                models[pass] = upload(mesh);
            }
            MeshData edges = line.build();
            if (edges != null) outlines = upload(edges);
            MeshData partData = partMesh.build();
            if (partData != null) partModels = upload(partData);
        } finally { VertexBuffer.unbind(); }
        refresh.rebuilt(hash(minecraft), camera, now);
    }

    /** Camera movement changes draw order, not block models, outline vertices or GPU buffer identity. */
    void resort(Vec3 camera) {
        for (int pass = 0; pass < models.length; pass++) {
            if (models[pass] == null || sortStates[pass] == null) continue;
            try (ByteBufferBuilder memory = new ByteBufferBuilder(4096)) {
                models[pass].bind();
                // uploadIndexBuffer consumes the Result; the retained centroids own no native memory.
                models[pass].uploadIndexBuffer(sortStates[pass].buildSortedIndexBuffer(memory, sorting(origin, camera)));
            } finally { VertexBuffer.unbind(); }
        }
        refresh.sorted(camera);
    }

    static VertexSorting sorting(BlockPos origin, Vec3 camera) {
        return VertexSorting.byDistance((float) (camera.x - origin.getX()),
                (float) (camera.y - origin.getY()), (float) (camera.z - origin.getZ()));
    }

    private static VertexBuffer upload(MeshData mesh) {
        VertexBuffer result = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try {
            result.bind(); result.upload(mesh);
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    void draw(int pass, Matrix4f view, Matrix4f projection, Vec3 camera) {
        VertexBuffer buffer = pass < 3 ? models[pass] : pass == 3 ? partModels : outlines;
        if (buffer == null) return;
        Matrix4f relative = new Matrix4f(view).translate((float) (origin.getX() - camera.x),
                (float) (origin.getY() - camera.y), (float) (origin.getZ() - camera.z));
        buffer.bind();
        buffer.drawWithShader(relative, projection, RenderSystem.getShader());
    }

    @Override public void close() {
        for (int i = 0; i < models.length; i++) {
            if (models[i] != null) models[i].close(); models[i] = null; sortStates[i] = null;
        }
        if (outlines != null) outlines.close();
        if (partModels != null) partModels.close();
        outlines = null; partModels = null; refresh.reset();
    }
}
