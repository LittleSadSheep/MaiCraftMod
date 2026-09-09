// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Shared Fabric/NeoForge renderer. Entire frozen plans are retained; only visibility is bounded. */
public final class PreviewRenderer {
    private static final List<PreviewMeshSection> sections = new ArrayList<>();
    private static PreviewSession cached;
    private static int minY, maxY, refreshCursor;
    private static Object resourceManager;
    private static Set<BlockPos> centres = Set.of();
    private PreviewRenderer() {}

    public static void render(Camera camera, Matrix4f view, Matrix4f projection) {
        Minecraft minecraft = Minecraft.getInstance();
        PreviewController.tick(minecraft);
        PreviewSession session = PreviewController.current();
        if (session == null || session.decision() == PreviewSession.Decision.CANCELLED || minecraft.level == null) {
            clear(); return;
        }
        if (!session.visible() || !session.dimension().equals(minecraft.level.dimension().location().toString())) return;
        if (cached != session || minY != session.minY() || maxY != session.maxY()
                || resourceManager != minecraft.getBlockRenderer().getBlockModelShaper()) reset(session, minecraft);
        Vec3 position = camera.getPosition();
        Frustum frustum = new Frustum(view, projection);
        frustum.prepare(position.x, position.y, position.z);
        List<PreviewMeshSection> visible = sections.stream().filter(section ->
                section.bounds.maxY > session.minY() && section.bounds.minY <= session.maxY()
                && section.bounds.getCenter().distanceToSqr(position) < 128 * 128
                && frustum.isVisible(section.bounds)).sorted(Comparator.comparingDouble(section ->
                section.bounds.getCenter().distanceToSqr(position))).toList();
        long deadline = System.nanoTime() + 4_000_000;
        long now = System.currentTimeMillis();
        int remaining = 512;
        PreviewWorldView world = new PreviewWorldView(session, minecraft.level);
        // Fresh visible geometry has priority; every section eventually receives a refresh slot.
        for (PreviewMeshSection section : visible) {
            if (!section.built) {
                section.rebuild(minecraft, session, world, centres, position, now);
                remaining -= section.workSize();
                if (remaining <= 0 || System.nanoTime() >= deadline) break;
            }
        }
        for (int i = 0; i < visible.size() && remaining > 0 && System.nanoTime() < deadline; i++) {
            PreviewMeshSection section = visible.get(Math.floorMod(refreshCursor++, visible.size()));
            if (section.needsRefresh(minecraft, now)) {
                section.rebuild(minecraft, session, world, centres, position, now);
                remaining -= section.workSize();
            }
        }
        draw(visible, PreviewRenderTypes.SOLID, 0, view, projection, position);
        draw(visible, PreviewRenderTypes.CUTOUT, 1, view, projection, position);
        draw(visible, PreviewRenderTypes.PARTS, 3, view, projection, position);
        draw(visible, PreviewRenderTypes.TRANSLUCENT, 2, view, projection, position);
        draw(visible, PreviewRenderTypes.OUTLINE, 4, view, projection, position);
        long ready = visible.stream().filter(section -> section.built).count();
        int fallback = visible.stream().mapToInt(section -> section.fallbackModels).sum();
        minecraft.gui.setOverlayMessage(Component.literal("MaiCraft 蓝图 · " + session.title()
                + " · " + session.decision() + " · 可见区块 " + ready + "/" + visible.size()
                + (fallback == 0 ? "" : " · 特殊模型仅轮廓 " + fallback)
                + (session.parts().isEmpty() ? "" : " · AE部件为示意几何")
                + (session.designOnly() ? " · 只读设计 · /maicraft preview cancel | layer <Y>"
                : " · /maicraft preview confirm | cancel | layer <Y>")), false);
    }

    private static void draw(List<PreviewMeshSection> visible, RenderType type, int pass,
                             Matrix4f view, Matrix4f projection, Vec3 camera) {
        type.setupRenderState();
        try {
            // Opaque terrain establishes depth first; only real translucent blocks require back-to-front order.
            if (pass == 2) {
                for (int i = visible.size() - 1; i >= 0; i--) visible.get(i).draw(pass, view, projection, camera);
            } else for (PreviewMeshSection section : visible) section.draw(pass, view, projection, camera);
        } finally { VertexBuffer.unbind(); type.clearRenderState(); }
    }

    private static void reset(PreviewSession session, Minecraft minecraft) {
        clear(); cached = session; minY = session.minY(); maxY = session.maxY();
        resourceManager = minecraft.getBlockRenderer().getBlockModelShaper();
        Map<BlockPos, PreviewMeshSection> grouped = new LinkedHashMap<>();
        session.cells().entrySet().forEach(cell -> {
            BlockPos pos = cell.getKey();
            grouped.computeIfAbsent(sectionOrigin(pos), PreviewMeshSection::new).cells.add(cell);
        });
        session.parts().forEach(part -> grouped.computeIfAbsent(sectionOrigin(part.position()),
                PreviewMeshSection::new).parts.add(part));
        centres = session.parts().stream().filter(part -> part.side().equals("center") && session.includes(part.position()))
                .map(PreviewPart::position).collect(Collectors.toUnmodifiableSet());
        sections.addAll(grouped.values());
    }

    private static BlockPos sectionOrigin(BlockPos pos) {
        return new BlockPos(Math.floorDiv(pos.getX(), 4) * 4,
                Math.floorDiv(pos.getY(), 4) * 4, Math.floorDiv(pos.getZ(), 4) * 4);
    }

    public static void clear() {
        sections.forEach(PreviewMeshSection::close); sections.clear(); cached = null; refreshCursor = 0;
        centres = Set.of();
    }

    /** Called by client lifecycle hooks, including disconnect while no level can render. */
    public static void invalidate() {
        if (RenderSystem.isOnRenderThread()) clear(); else RenderSystem.recordRenderCall(PreviewRenderer::clear);
    }
}
