// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.CellKind;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 在世界里画出一份蓝图给观众看：要放的方块是半透明白格，要清空的格是红格，要倒桶的格是蓝格，整体套一个蓝线包围盒。
 * 只画镜头附近的格，格数再多也有上限；没加载的区块照画，蓝图里的格不看世界。只读蓝图，不动角色。
 */
public final class BlueprintRenderer {
    private static final int BLOCK = 0x40FFFFFF;
    private static final int CLEAR = 0x60FF4040;
    private static final int FLUID = 0x603F8CFF;
    private static final int BOUNDS = 0xFF3F8CFF;
    /** 离镜头这么远的格不画：看不清，也白占绘制。 */
    private static final double DRAW_RANGE = 128;
    /** 一帧最多画这么多格，大蓝图不能把帧率拖垮。 */
    private static final int MAX_CELLS = 32_768;
    /** 格的面往里缩一点，和已经砌好的方块不打架。 */
    private static final float INSET = 0.02f;

    private BlueprintRenderer() {}

    public static void render(Camera camera, Matrix4f view, Matrix4f projection, Blueprint blueprint) {
        Vec3 eye = camera.getPosition();
        BufferBuilder faces = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int drawn = 0;
        double range = DRAW_RANGE * DRAW_RANGE;
        for (PlannedCell cell : blueprint.cells()) {
            if (drawn >= MAX_CELLS) break;
            if (cell.pos().distToCenterSqr(eye.x, eye.y, eye.z) > range) continue;
            box(faces, cell.pos(), eye, colorOf(cell.kind()));
            drawn++;
        }
        draw(faces.build(), OverlayRenderTypes.FILLED, DefaultVertexFormat.POSITION_COLOR, view, projection);
        BufferBuilder lines = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        outline(lines, blueprint.bounds(), eye);
        draw(lines.build(), OverlayRenderTypes.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL, view, projection);
    }

    private static int colorOf(CellKind kind) {
        return switch (kind) {
            case BLOCK -> BLOCK;
            case AIR -> CLEAR;
            case FLUID_SOURCE -> FLUID;
        };
    }

    // 用原版的临时绘制缓冲区画一批，不留跨帧的模型或世界引用。
    private static void draw(MeshData mesh, RenderType type, VertexFormat format, Matrix4f view, Matrix4f projection) {
        if (mesh == null) return;
        type.setupRenderState();
        try {
            VertexBuffer buffer = format.getImmediateDrawVertexBuffer();
            buffer.bind();
            buffer.upload(mesh);
            buffer.drawWithShader(view, projection, RenderSystem.getShader());
        } finally {
            VertexBuffer.unbind();
            type.clearRenderState();
        }
    }

    // 一格六个面，坐标换成相对镜头的位置。
    private static void box(BufferBuilder faces, BlockPos pos, Vec3 eye, int color) {
        float x0 = (float) (pos.getX() - eye.x) + INSET;
        float y0 = (float) (pos.getY() - eye.y) + INSET;
        float z0 = (float) (pos.getZ() - eye.z) + INSET;
        float x1 = x0 + 1 - 2 * INSET;
        float y1 = y0 + 1 - 2 * INSET;
        float z1 = z0 + 1 - 2 * INSET;
        quad(faces, color, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        quad(faces, color, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(faces, color, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(faces, color, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        quad(faces, color, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(faces, color, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
    }

    private static void quad(BufferBuilder faces, int color, float... xyz) {
        for (int i = 0; i < 12; i += 3) faces.addVertex(xyz[i], xyz[i + 1], xyz[i + 2]).setColor(color);
    }

    // 包围盒的十二条棱：最小角到最大角再加一格，正好盖住最远那一格。
    private static void outline(BufferBuilder lines, Blueprint.Bounds bounds, Vec3 eye) {
        Vec3 min = Vec3.atLowerCornerOf(bounds.min()).subtract(eye);
        Vec3 max = Vec3.atLowerCornerOf(bounds.max().offset(1, 1, 1)).subtract(eye);
        Vec3[] corners = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = new Vec3((i & 1) == 0 ? min.x : max.x, (i & 2) == 0 ? min.y : max.y, (i & 4) == 0 ? min.z : max.z);
        }
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((i & bit) == 0) segment(lines, corners[i], corners[i | bit]);
            }
        }
    }

    private static void segment(BufferBuilder lines, Vec3 from, Vec3 to) {
        Vec3 normal = to.subtract(from).normalize();
        lines.addVertex((float) from.x, (float) from.y, (float) from.z).setColor(BOUNDS)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
        lines.addVertex((float) to.x, (float) to.y, (float) to.z).setColor(BOUNDS)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
    }
}
