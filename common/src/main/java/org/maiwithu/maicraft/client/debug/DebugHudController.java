// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.debug;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.command.MaiCraftStatus;
import org.maiwithu.maicraft.client.preview.PreviewConfig;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.scan.TargetIndex;

/**
 * F9 切换的常驻调试面板。每刻构建一次只读快照，渲染只画快照；本类不提交任何操作，
 * 核心状态行与 /maicraft status 共用同一份聚合数据。
 */
public final class DebugHudController {
    private static volatile List<MaiCraftStatus.StatusRow> snapshot = List.of();
    private static boolean toggleWasDown;
    private DebugHudController() {}

    public static void tick(Minecraft minecraft) {
        // F9 与 F8 一样直接轮询窗口按键：界面打开时键盘事件进不了 KeyMapping，而菜单流调试恰恰需要此刻可用。
        long window = minecraft.getWindow().getWindow();
        boolean down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F9) == GLFW.GLFW_PRESS;
        boolean toggled = down && !toggleWasDown;
        toggleWasDown = down;
        if (toggled) toggle(minecraft);
        snapshot = minecraft.player == null
                || !PreviewConfig.hudVisible(minecraft.gameDirectory.toPath())
                ? List.of() : buildSnapshot();
    }

    /** 渲染层每帧读取的最近一次快照；不可见时是空列表，渲染器据此不画。 */
    public static List<MaiCraftStatus.StatusRow> snapshot() { return snapshot; }

    private static void toggle(Minecraft minecraft) {
        // Dev 是调试子系统的总闸：总闸关闭时 F9 只提示，不单独打开面板。
        if (!PreviewConfig.enabled(minecraft.gameDirectory.toPath())) {
            message(minecraft, "Dev 未开启：先用 /maicraft dev on，再按 F9 显示调试面板。",
                    ChatFormatting.YELLOW);
            return;
        }
        boolean show = !PreviewConfig.hudVisible(minecraft.gameDirectory.toPath());
        try {
            PreviewConfig.hudVisible(show);
        } catch (IOException failure) {
            message(minecraft, "调试面板 " + (show ? "已显示" : "已隐藏")
                    + "，但配置保存失败：" + failure.getMessage(), ChatFormatting.YELLOW);
            return;
        }
        message(minecraft, show ? "调试面板已显示，再按 F9 隐藏。" : "调试面板已隐藏。",
                ChatFormatting.GREEN);
    }

    private static List<MaiCraftStatus.StatusRow> buildSnapshot() {
        List<MaiCraftStatus.StatusRow> rows = new ArrayList<>(MaiCraftStatus.rows());
        String menu;
        try {
            menu = ClientRuntime.actor().menuDiagnostic();
        } catch (RuntimeException failure) {
            menu = "transitioning";
        }
        rows.add(new MaiCraftStatus.StatusRow("Menu", "none".equals(menu) ? "无" : menu,
                "none".equals(menu) ? ChatFormatting.GRAY : ChatFormatting.AQUA));
        // 最近一次 UNCERTAIN 的消费可能已发生；面板只提示保留现场，核验仍以真实背包与方块为准。
        MenuReceipt.UncertainSnapshot uncertain = MenuReceipt.lastUncertain();
        rows.add(new MaiCraftStatus.StatusRow("Uncertain", uncertain == null ? "无记录"
                        : uncertain.kind() + (uncertain.slot() >= 0 ? " slot=" + uncertain.slot() : "")
                        + " @t" + uncertain.submittedTick() + " · " + clamp(uncertain.detail()),
                uncertain == null ? ChatFormatting.GRAY : ChatFormatting.RED));
        // 扫描截断计数大于零说明最近有"预算内没扫完"的查询；缺席结论要先看这里。
        TargetIndex.BudgetCounters scan = TargetIndex.budgetCounters();
        boolean truncated = scan.wallClockExhausted() > 0 || scan.buildBudgetExhausted() > 0;
        rows.add(new MaiCraftStatus.StatusRow("Scan", "墙钟超限 " + scan.wallClockExhausted()
                        + " · 构建预算耗尽 " + scan.buildBudgetExhausted(),
                truncated ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        return List.copyOf(rows);
    }

    // 面板单行放不下的原因说明截断到 96 字符；完整文本在回执的审计日志里。
    private static String clamp(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() <= 96 ? value : value.substring(0, 95) + "…";
    }

    private static void message(Minecraft minecraft, String text, ChatFormatting color) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text).withStyle(color), true);
        }
    }
}
