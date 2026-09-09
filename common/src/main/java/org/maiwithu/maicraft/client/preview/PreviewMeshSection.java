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

/**
 * 保存一个四乘四乘四小分区的模型和边框。方块改变时重建，只有相机移动时可只重排透明面的顺序。
 */
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

    // 读取本分区目标及其六个邻格的方块状态，合成变化摘要；没有把模型资源版本或方块实体数据加入摘要。
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

    // 先释放旧缓存，按普通、镂空、透明三类生成方块模型，并分别生成部件示意与轮廓。
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
                // 只有完整状态一模一样才省掉半透明模型；这比施工器的完成比较更严格，例如门被打开仍可能显示待修正模型。
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
                            // 模组模型可能只写了半个顶点就失败，整个分区的这一类模型缓存都不再使用，独立轮廓仍保留。
                            // fallbackModels 只加这次失败和后续跳过的项，之前已画入但一同丢弃的模型没有补计。
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
    // 模型顶点保留，只更新各面从远到近的绘制顺序；不再次调用方块模型生成器。
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

    // 上传失败时关闭刚分配的显卡缓冲区再抛出，避免本次分配泄漏。
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

    // 把分区坐标转换成相机附近的小坐标再绘制，减少世界坐标很大时的浮点精度损失。
    void draw(int pass, Matrix4f view, Matrix4f projection, Vec3 camera) {
        VertexBuffer buffer = pass < 3 ? models[pass] : pass == 3 ? partModels : outlines;
        if (buffer == null) return;
        Matrix4f relative = new Matrix4f(view).translate((float) (origin.getX() - camera.x),
                (float) (origin.getY() - camera.y), (float) (origin.getZ() - camera.z));
        buffer.bind();
        buffer.drawWithShader(relative, projection, RenderSystem.getShader());
    }

    // 逐个释放三类模型、轮廓和部件缓存，并把刷新状态改成未构建，供下次重新生成。
    @Override public void close() {
        for (int i = 0; i < models.length; i++) {
            if (models[i] != null) models[i].close(); models[i] = null; sortStates[i] = null;
        }
        if (outlines != null) outlines.close();
        if (partModels != null) partModels.close();
        outlines = null; partModels = null; refresh.reset();
    }
}
