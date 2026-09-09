// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;

/** Client-thread review gate. The caller owns pausing construction and cleaning up its owner. */
public final class PreviewController {
    private static PreviewSession current;
    private static Object world;
    private static boolean initialized;
    private PreviewController() {}

    private static void initialize() {
        if (!initialized) {
            PreviewConfig.load(Minecraft.getInstance().gameDirectory.toPath());
            initialized = true;
        }
    }

    public static boolean enabled() { initialize(); return PreviewConfig.enabled(); }
    public static PreviewSession current() { return current; }
    public static boolean waitingReview() {
        return current != null && current.decision() == Decision.WAITING;
    }

    /** A repeated owner retains its frozen plan. Call release when the owning task ends. */
    public static Decision request(String owner, String dimension, String title,
                                   Map<BlockPos, BlockState> cells) {
        return request(owner, dimension, title, cells, List.of());
    }

    public static Decision request(String owner, String dimension, String title,
                                   Map<BlockPos, BlockState> cells, List<PreviewPart> parts) {
        initialize();
        tick(Minecraft.getInstance());
        if (current != null && current.designOnly()) release(current.owner());
        if (current != null) {
            if (current.owner().equals(owner)) return current.decision();
            return Decision.WAITING;
        }
        if (!PreviewConfig.enabled()) return Decision.DISABLED;
        if (Minecraft.getInstance().level == null
                || !Minecraft.getInstance().level.dimension().location().toString().equals(dimension))
            return Decision.CANCELLED;
        current = new PreviewSession(owner, dimension, title, cells, parts);
        world = Minecraft.getInstance().level;
        message("蓝图等待确认：" + title + " · " + cells.size() + " 格。"
                + " /maicraft preview confirm 开工；cancel 取消；layer <Y> 切片。", ChatFormatting.AQUA);
        return Decision.WAITING;
    }

    /** Explicit read-only display is independent of the automatic Dev construction gate. */
    public static boolean showDesign(PreviewSession session) {
        tick(Minecraft.getInstance());
        if (!session.designOnly() || Minecraft.getInstance().level == null
                || !session.dimension().equals(Minecraft.getInstance().level.dimension().location().toString())
                || current != null && !current.designOnly()) return false;
        current = session;
        world = Minecraft.getInstance().level;
        PreviewRenderer.invalidate();
        message("只读设计蓝图：" + session.title() + " · " + session.cells().size()
                + " 格。不会移动或施工；confirm 不会开工。cancel 关闭；layer <Y> 切片。", ChatFormatting.AQUA);
        return true;
    }

    public static void release(String owner) {
        if (current != null && current.owner().equals(owner)) {
            current = null; world = null; PreviewRenderer.invalidate();
        }
    }

    public static void shutdown() {
        current = null; world = null; PreviewRenderer.invalidate();
    }

    /** A level replacement (including same-dimension reconnect) must revoke old approval. */
    public static void tick(Minecraft minecraft) {
        if (current != null && world != minecraft.level) {
            current.cancel();
            world = minecraft.level;
            PreviewRenderer.invalidate();
        }
    }

    static int dev(boolean enabled) {
        initialize();
        if (!enabled && current != null && !current.designOnly()) {
            if (current.decision() == Decision.WAITING) {
                current.cancel(); PreviewRenderer.invalidate();
            }
            else current.visible(false);
        }
        try { PreviewConfig.enabled(enabled); }
        catch (IOException exception) {
            message("Dev " + (enabled ? "已开启" : "已关闭") + "，配置未能保存："
                    + exception.getMessage(), ChatFormatting.YELLOW);
            return 0;
        }
        message("Dev " + (enabled ? "已开启：施工前需要确认蓝图。" : "已关闭。"), ChatFormatting.GREEN);
        return 1;
    }

    static int confirm() {
        tick(Minecraft.getInstance());
        if (current != null && current.designOnly()) {
            message("这是只读设计，不能确认开工。需要施工时另行提交 maicraft:build。", ChatFormatting.YELLOW);
            return 0;
        }
        if (current == null || !current.confirm()) return unavailable();
        message("蓝图已确认，施工继续。", ChatFormatting.GREEN);
        return 1;
    }

    static int cancel() {
        if (current == null) return unavailable();
        current.cancel();
        PreviewRenderer.invalidate();
        message("蓝图已取消。", ChatFormatting.YELLOW);
        return 1;
    }

    static int visible(boolean value) {
        if (current == null) return unavailable();
        current.visible(value);
        return status();
    }

    static int layers(int min, int max) {
        if (current == null) return unavailable();
        if (min > max) { message("最低层必须小于等于最高层。", ChatFormatting.RED); return 0; }
        current.layers(min, max);
        return status();
    }

    static int status() {
        message("Dev " + (enabled() ? "on" : "off") + (current == null ? " · 无蓝图" : " · "
                + current.title() + " · " + current.decision() + " · " + current.cells().size()
                + " 格 / " + current.parts().size() + " 部件示意 · " + (current.visible() ? "显示" : "隐藏") + " · 层 "
                + (current.minY() == Integer.MIN_VALUE && current.maxY() == Integer.MAX_VALUE
                ? "全部" : current.minY() + ".." + current.maxY())), ChatFormatting.AQUA);
        message("半透明模型=待放置或需修正，蓝线=整体外轮廓，红色=需要清除。"
                + " all 显示全部层，range <最低Y> <最高Y> 显示区间，show/hide 切换显示。", ChatFormatting.GRAY);
        return 1;
    }

    private static int unavailable() {
        message("没有可确认的等待蓝图。", ChatFormatting.YELLOW); return 0;
    }

    static void message(String text, ChatFormatting color) {
        Minecraft.getInstance().gui.getChat().addMessage(
                Component.literal("[MaiCraft] " + text).withStyle(color));
    }
}
