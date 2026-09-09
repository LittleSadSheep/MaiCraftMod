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

/**
 * 管理当前这一份预览和玩家的选择。这里只维护预览状态，真正暂停、继续和结束施工由请求它的任务处理。
 */
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

    /**
     * 施工任务申请预览。同一个 owner 再申请时沿用原方案和选择结果；结束时应按这个 owner 释放。
     */
    public static Decision request(String owner, String dimension, String title,
                                   Map<BlockPos, BlockState> cells) {
        return request(owner, dimension, title, cells, List.of());
    }

    public static Decision request(String owner, String dimension, String title,
                                   Map<BlockPos, BlockState> cells, List<PreviewPart> parts) {
        initialize();
        tick(Minecraft.getInstance());
        // 新的施工预览可以替换只读设计；已有另一个任务的施工预览时只返回等待，不替它确认或取消。
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

    /**
     * 显示只读设计，不要求打开 Dev 开关，也不能用 confirm 启动施工。已有施工预览时不会把它顶掉。
     */
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

    // 只允许匹配的 owner 清掉当前预览，避免旧任务结束时误关掉新任务的蓝图。
    public static void release(String owner) {
        if (current != null && current.owner().equals(owner)) {
            current = null; world = null; PreviewRenderer.invalidate();
        }
    }

    public static void shutdown() {
        current = null; world = null; PreviewRenderer.invalidate();
    }

    /**
     * 世界对象换了就取消原预览，包括同维度断线重连；以前的确认不能继续用于新的世界。
     */
    public static void tick(Minecraft minecraft) {
        if (current != null && world != minecraft.level) {
            current.cancel();
            world = minecraft.level;
            PreviewRenderer.invalidate();
        }
    }

    // 关闭 Dev 时，尚未确认的施工预览会取消；已经确认的只隐藏。只读设计继续保留。
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

    // 先核对世界是否变化，再只接受正在等待的施工预览；只读设计和已结束的选择不能再次确认。
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

    // 显示开关只影响绘制，不代表继续或暂停施工。
    static int visible(boolean value) {
        if (current == null) return unavailable();
        current.visible(value);
        return status();
    }

    // 高度筛选只改变预览可见内容，原计划格子保留；最小高度超过最大高度时不给应用。
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
