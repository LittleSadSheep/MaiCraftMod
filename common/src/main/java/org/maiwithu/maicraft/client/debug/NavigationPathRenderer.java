// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import java.util.OptionalDouble;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.maiwithu.maicraft.client.preview.PreviewConfig;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.debug.NavigationPathSnapshot;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;

/** Dev-only route lines. Reads the selected paths; it cannot request movement or world actions. */
public final class NavigationPathRenderer {
    private static final int GROUND = 0xCC35D9FF, FLIGHT = 0xCCBA77FF;
    private static final int STEERING = 0xFFFFD641, DESTINATION = 0xFF55FF88;
    private NavigationPathRenderer() {}

    public static void render(Camera camera, Matrix4f view, Matrix4f projection) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options.hideGui
                || !PreviewConfig.enabled(minecraft.gameDirectory.toPath())) return;
        NavigationPathSnapshot ground = EmbeddedBaritoneRuntime.debugPath();
        NavigationPathSnapshot flight = TransportRuntime.debugPath();
        if (ground == null && flight == null) return;
        Vec3 cameraPosition = camera.getPosition();
        BufferBuilder lines = Tesselator.getInstance().begin(
                VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        route(lines, ground, cameraPosition, GROUND);
        route(lines, flight, cameraPosition, FLIGHT);
        var mesh = lines.build();
        if (mesh == null) return;
        Lines.INSTANCE.setupRenderState();
        try {
            // Reuse Minecraft's immediate VBO; no persistent meshes or world references to clean up.
            VertexBuffer buffer = DefaultVertexFormat.POSITION_COLOR_NORMAL.getImmediateDrawVertexBuffer();
            buffer.bind();
            buffer.upload(mesh);
            buffer.drawWithShader(view, projection, RenderSystem.getShader());
        } finally {
            VertexBuffer.unbind();
            Lines.INSTANCE.clearRenderState();
        }
    }

    private static void route(BufferBuilder lines, NavigationPathSnapshot route, Vec3 camera, int color) {
        if (route == null) return;
        List<Vec3> points = route.points();
        for (int i = 1; i < points.size(); i++) segment(lines, points.get(i - 1), points.get(i), camera, color);
        marker(lines, route.destination(), camera, DESTINATION, 0.35);
        marker(lines, route.steeringTarget(), camera, STEERING, 0.22);
    }

    private static void marker(BufferBuilder lines, Vec3 point, Vec3 camera, int color, double size) {
        if (point == null) return;
        segment(lines, point.add(-size, 0, 0), point.add(size, 0, 0), camera, color);
        segment(lines, point.add(0, -size, 0), point.add(0, size, 0), camera, color);
        segment(lines, point.add(0, 0, -size), point.add(0, 0, size), camera, color);
    }

    private static void segment(BufferBuilder lines, Vec3 from, Vec3 to, Vec3 camera, int color) {
        if (from.distanceToSqr(camera) > 128 * 128 && to.distanceToSqr(camera) > 128 * 128) return;
        Vec3 direction = to.subtract(from);
        if (direction.lengthSqr() < 1.0E-8) return;
        Vec3 normal = direction.normalize();
        vertex(lines, from.subtract(camera), normal, color);
        vertex(lines, to.subtract(camera), normal, color);
    }

    private static void vertex(BufferBuilder lines, Vec3 point, Vec3 normal, int color) {
        lines.addVertex((float) point.x, (float) point.y, (float) point.z).setColor(color)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    /** Through-world developer lines use the main target and never write scene depth. */
    private static final class Lines extends RenderType {
        static final RenderType INSTANCE = new Lines(List.of(RENDERTYPE_LINES_SHADER,
                new RenderStateShard.LineStateShard(OptionalDouble.of(2.5)), TRANSLUCENT_TRANSPARENCY,
                NO_CULL, COLOR_WRITE, new RenderStateShard("maicraft_route_no_depth",
                        RenderSystem::disableDepthTest, RenderSystem::enableDepthTest) {}, MAIN_TARGET));

        private Lines(List<RenderStateShard> states) {
            super("maicraft_navigation_path", DefaultVertexFormat.POSITION_COLOR_NORMAL,
                    VertexFormat.Mode.LINES, 32768, false, false,
                    () -> states.forEach(RenderStateShard::setupRenderState),
                    () -> states.forEach(RenderStateShard::clearRenderState));
        }
    }
}
