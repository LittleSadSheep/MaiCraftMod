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

/**
 * 把预览画在真实世界中。整份计划保留，实际只画相机范围内、距离小于 128 格且处于所选高度的分区。
 * 每个分区是四格见方的小立方体；重建和排序分帧进行，图形缓存不用每帧全部重做。
 */
public final class PreviewRenderer {
    private static final List<PreviewMeshSection> sections = new ArrayList<>();
    private static PreviewSession cached;
    private static int minY, maxY, refreshCursor;
    private static Object resourceManager;
    private static Set<BlockPos> centres = Set.of();
    private static PreviewOutlineGeometry outline;
    private PreviewRenderer() {}

    public static void render(Camera camera, Matrix4f view, Matrix4f projection) {
        Minecraft minecraft = Minecraft.getInstance();
        PreviewController.tick(minecraft);
        PreviewSession session = PreviewController.current();
        if (session == null || session.decision() == PreviewSession.Decision.CANCELLED || minecraft.level == null) {
            clear(); return;
        }
        if (!session.visible() || !session.dimension().equals(minecraft.level.dimension().location().toString())) return;
        // 切换方案、切层或检测到模型查询对象更换时，先清缓存并同步重算整张图的轮廓；这一步在下方帧预算之前。
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
        PreviewFrameBudget budget = new PreviewFrameBudget();
        long now = System.currentTimeMillis();
        PreviewWorldView world = new PreviewWorldView(session, minecraft.level);
        // 先生成还没画出的可见分区，再从上次游标继续轮询已经显示的分区，避免始终只刷新最前面几块。
        for (PreviewMeshSection section : visible) {
            if (!section.built()) {
                if (!budget.claim(PreviewSectionRefresh.Work.REBUILD, section.workSize())) break;
                section.rebuild(minecraft, session, world, centres, outline, position, now);
            }
        }
        for (int i = 0; i < visible.size() && budget.hasTime(); i++) {
            PreviewMeshSection section = visible.get(Math.floorMod(refreshCursor++, visible.size()));
            var work = section.refreshWork(minecraft, position, now);
            if (!budget.claim(work, section.workSize())) continue;
            if (work == PreviewSectionRefresh.Work.REBUILD)
                section.rebuild(minecraft, session, world, centres, outline, position, now);
            else section.resort(position);
        }
        draw(visible, PreviewRenderTypes.SOLID, 0, view, projection, position);
        draw(visible, PreviewRenderTypes.CUTOUT, 1, view, projection, position);
        draw(visible, PreviewRenderTypes.PARTS, 3, view, projection, position);
        draw(visible, PreviewRenderTypes.TRANSLUCENT, 2, view, projection, position);
        draw(visible, PreviewRenderTypes.OUTLINE, 4, view, projection, position);
        long ready = visible.stream().filter(PreviewMeshSection::built).count();
        int fallback = visible.stream().mapToInt(section -> section.fallbackModels).sum();
        minecraft.gui.setOverlayMessage(Component.literal("MaiCraft 蓝图 · " + session.title()
                + " · " + session.decision() + " · 可见区块 " + ready + "/" + visible.size()
                + (fallback == 0 ? "" : " · 特殊模型仅轮廓 " + fallback)
                + (session.parts().isEmpty() ? "" : " · AE部件为示意几何")
                + (session.designOnly() ? " · 只读设计 · /maicraft preview cancel | layer <Y>"
                : " · /maicraft preview confirm | cancel | layer <Y>")), false);
    }

    // 先设定这一类图形的渲染方式；填充模型按远到近混合，轮廓另画，结束后恢复之前的颜色和渲染状态。
    private static void draw(List<PreviewMeshSection> visible, RenderType type, int pass,
                             Matrix4f view, Matrix4f projection, Vec3 camera) {
        type.setupRenderState();
        float[] color = RenderSystem.getShaderColor().clone();
        try {
            if (pass != 4) RenderSystem.setShaderColor(color[0], color[1], color[2], color[3] * .45f);
            // 石头等普通实心块在预览中也要半透明，统一把透明度乘以 0.45。
            if (pass != 4) {
                for (int i = visible.size() - 1; i >= 0; i--) visible.get(i).draw(pass, view, projection, camera);
            } else for (PreviewMeshSection section : visible) section.draw(pass, view, projection, camera);
        } finally {
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            VertexBuffer.unbind(); type.clearRenderState();
        }
    }

    // 重算当前所选高度的整体轮廓，把方块和部件按四格分区，并收集中心部件供连接示意使用。
    private static void reset(PreviewSession session, Minecraft minecraft) {
        clear(); cached = session; minY = session.minY(); maxY = session.maxY();
        resourceManager = minecraft.getBlockRenderer().getBlockModelShaper();
        outline = new PreviewOutlineGeometry(session, new PreviewWorldView(session, minecraft.level));
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

    // 用向下取整分区，负坐标也保持每四格为一组，例如 -1 属于起点为 -4 的分区。
    private static BlockPos sectionOrigin(BlockPos pos) {
        return new BlockPos(Math.floorDiv(pos.getX(), 4) * 4,
                Math.floorDiv(pos.getY(), 4) * 4, Math.floorDiv(pos.getZ(), 4) * 4);
    }

    public static void clear() {
        sections.forEach(PreviewMeshSection::close); sections.clear(); cached = null; refreshCursor = 0;
        centres = Set.of(); outline = null;
    }

    /** 断线等场合也可释放图形；若不在渲染线程，就排到渲染线程执行，避免跨线程释放显卡资源。 */
    public static void invalidate() {
        if (RenderSystem.isOnRenderThread()) clear(); else RenderSystem.recordRenderCall(PreviewRenderer::clear);
    }
}
