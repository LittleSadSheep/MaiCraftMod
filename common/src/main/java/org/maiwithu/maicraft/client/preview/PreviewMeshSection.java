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
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
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
    private VertexBuffer models, outlines, partModels;
    private long nextCheck;
    private int worldHash;
    boolean built;
    int fallbackModels;

    PreviewMeshSection(BlockPos origin) {
        this.origin = origin;
        bounds = new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + 4, origin.getY() + 4, origin.getZ() + 4);
    }

    boolean needsRefresh(Minecraft minecraft, long now) {
        if (!built) return true;
        if (now < nextCheck) return false;
        nextCheck = now + 1000;
        return hash(minecraft) != worldHash;
    }

    private int hash(Minecraft minecraft) {
        int hash = 1;
        for (var cell : cells) hash = 31 * hash + (minecraft.level.isLoaded(cell.getKey())
                ? minecraft.level.getBlockState(cell.getKey()).hashCode() : 0);
        return hash;
    }

    int workSize() { return cells.size() + parts.size(); }

    void rebuild(Minecraft minecraft, PreviewSession session, PreviewWorldView view, Set<BlockPos> centres,
                 Vec3 camera, long now) {
        close();
        fallbackModels = 0;
        try (ByteBufferBuilder modelMemory = new ByteBufferBuilder(262144);
             ByteBufferBuilder partMemory = new ByteBufferBuilder(65536);
             ByteBufferBuilder lineMemory = new ByteBufferBuilder(65536)) {
            BufferBuilder model = new BufferBuilder(modelMemory, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            BufferBuilder line = new BufferBuilder(lineMemory, VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
            BufferBuilder partMesh = new BufferBuilder(partMemory, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            PreviewVertexConsumer ghost = new PreviewVertexConsumer(model);
            PoseStack pose = new PoseStack();
            RandomSource random = RandomSource.create(0);
            boolean modelUsable = true;
            for (var cell : cells) {
                BlockPos pos = cell.getKey();
                if (!session.includes(pos)) continue;
                BlockState desired = cell.getValue();
                int x = pos.getX() - origin.getX(), y = pos.getY() - origin.getY(), z = pos.getZ() - origin.getZ();
                if (!desired.isAir()) {
                    if (desired.getRenderShape() == RenderShape.MODEL && modelUsable) {
                        pose.pushPose();
                        pose.translate(x, y, z);
                        try {
                            minecraft.getBlockRenderer().renderBatched(desired, pos, view, pose, ghost, true, random);
                        } catch (RuntimeException unsupportedModel) {
                            // A mod renderer can fail midway through a vertex. Discard this entire
                            // section's model buffer, keep its independent outline buffer valid.
                            modelUsable = false; fallbackModels++;
                        }
                        finally { pose.popPose(); }
                    } else fallbackModels++;
                }
                if (!minecraft.level.isLoaded(pos)) {
                    box(pose, line, x, y, z, .7f, .3f, 1);
                    continue;
                }
                BlockState actual = minecraft.level.getBlockState(pos);
                if (desired.equals(actual)) continue;
                if (desired.isAir()) box(pose, line, x, y, z, 1, .2f, .25f);
                else if (actual.isAir() || actual.canBeReplaced()) box(pose, line, x, y, z, .2f, .65f, 1);
                else box(pose, line, x, y, z, 1, .65f, .15f);
                if (!desired.isAir() && desired.getRenderShape() != RenderShape.MODEL)
                    box(pose, line, x, y, z, .8f, .3f, 1);
            }
            for (PreviewPart part : parts) if (session.includes(part.position()))
                PreviewPartGeometry.emit(part, origin, centres, partMesh, line);
            MeshData mesh = modelUsable ? model.build() : null;
            if (mesh != null) {
                mesh.sortQuads(modelMemory, VertexSorting.byDistance((float) (camera.x - origin.getX()),
                        (float) (camera.y - origin.getY()), (float) (camera.z - origin.getZ())));
                models = upload(mesh);
            }
            MeshData edges = line.build();
            if (edges != null) outlines = upload(edges);
            MeshData partData = partMesh.build();
            if (partData != null) partModels = upload(partData);
        } finally { VertexBuffer.unbind(); }
        built = true;
        worldHash = hash(minecraft);
        nextCheck = now + 1000;
    }

    private static void box(PoseStack pose, BufferBuilder lines, int x, int y, int z, float r, float g, float b) {
        LevelRenderer.renderLineBox(pose, lines, x - .002, y - .002, z - .002,
                x + 1.002, y + 1.002, z + 1.002, r, g, b, .8f);
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
        VertexBuffer buffer = pass == 0 ? models : pass == 1 ? partModels : outlines;
        if (buffer == null) return;
        Matrix4f relative = new Matrix4f(view).translate((float) (origin.getX() - camera.x),
                (float) (origin.getY() - camera.y), (float) (origin.getZ() - camera.z));
        buffer.bind();
        buffer.drawWithShader(relative, projection, RenderSystem.getShader());
    }

    @Override public void close() {
        if (models != null) models.close();
        if (outlines != null) outlines.close();
        if (partModels != null) partModels.close();
        models = null; outlines = null; partModels = null; built = false;
    }
}
