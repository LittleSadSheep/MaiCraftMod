// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.debug;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.preview.PreviewConfig;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import com.google.gson.JsonObject;

/**
 * F9 切换的常驻调试面板。每刻构建一次只读快照，渲染只画快照；本类不提交任何操作。
 * 行文与 /maicraft status 各自独立：status 是既有命令保持原样，面板按屏幕阅读自行裁剪。
 */
public final class DebugHudController {
    /** 面板单行的标签、取值和颜色；值必须是已经压平的短文本。 */
    public record Row(String label, String value, ChatFormatting color) {}

    /** 面板单行放不下的说明截断到 64 字符并以 … 结尾，让人看得出后面还有内容。 */
    private static final int TEXT_LIMIT = 64;

    private static volatile List<Row> snapshot = List.of();
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
    public static List<Row> snapshot() { return snapshot; }

    private static List<Row> buildSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        List<Row> rows = new ArrayList<>();

        boolean listening = ClientRuntime.isMcpRunning();
        rows.add(new Row("MCP", listening
                ? "127.0.0.1:" + ClientRuntime.mcpPort()
                        + " · 连接数 " + ClientRuntime.mcpSessionCount()
                : ClientRuntime.lastMcpError() == null ? "未监听"
                        : "未监听 · " + clamp(ClientRuntime.lastMcpError()),
                listening ? ChatFormatting.GREEN : ChatFormatting.RED));

        boolean inWorld = minecraft.player != null && minecraft.level != null
                && minecraft.gameMode != null && minecraft.getConnection() != null;
        rows.add(new Row("身体", (inWorld ? "就绪" : "等待世界") + " · " + controlState(inWorld),
                inWorld ? ChatFormatting.GREEN : ChatFormatting.YELLOW));

        // 从最近五十个任务里找第一个未结束的；没有时显示空闲，再查看最近二十个任务中的失败或超时。
        IntentTaskRecord active = IntentRuntime.get().tasks(50).stream()
                .filter(record -> !record.getState().isTerminal())
                .findFirst()
                .orElse(null);
        if (active == null) {
            rows.add(new Row("任务", "空闲", ChatFormatting.GRAY));
            appendLatestTerminalIssue(rows);
        } else {
            int total = Math.max(1, active.steps().size());
            int current = Math.min(active.stepIndex() + 1, total);
            rows.add(new Row("任务", taskTitle(active) + " · 步骤 " + current + "/" + total,
                    ChatFormatting.AQUA));
            if (active.decisionSnapshot() != null) {
                rows.add(new Row("等待决策", "等待大模型选择后才能继续", ChatFormatting.YELLOW));
            } else if (active.pauseSnapshot() != null) {
                rows.add(new Row("已暂停", clamp(active.pauseSnapshot().reason()),
                        ChatFormatting.YELLOW));
            }
            if (!active.attempts().isEmpty()) {
                rows.add(new Row("最近问题", clamp(active.attempts().getLast().message()),
                        ChatFormatting.RED));
            }
        }

        // 最近一次 UNCERTAIN 的消费可能已发生；面板只提示保留现场，核验仍以真实背包与方块为准。
        MenuReceipt.UncertainSnapshot uncertain = MenuReceipt.lastUncertain();
        rows.add(new Row("未定回执", uncertainText(uncertain),
                uncertain == null ? ChatFormatting.GRAY : ChatFormatting.RED));
        return List.copyOf(rows);
    }

    // 任务行以标题为主体：能力短名加目标陈述，状态前缀只标注它此刻在等什么。
    private static String taskTitle(IntentTaskRecord record) {
        String ability = record.goal().ability();
        int namespace = ability.indexOf(':');
        String shortAbility = namespace >= 0 ? ability.substring(namespace + 1) : ability;
        return statePrefix(record) + " · " + shortAbility + "：" + clamp(record.goal().outcome());
    }

    private static String statePrefix(IntentTaskRecord record) {
        if (record.decisionSnapshot() != null) return "待决策";
        if (record.paused()) return "已暂停";
        return switch (record.getState()) {
            case RUNNING -> "执行中";
            case PENDING -> "排队中";
            default -> record.getState().name().toLowerCase(Locale.ROOT);
        };
    }

    // 区分已经接管、申请接管但还没拿到控制权，以及仍由玩家操作；切换中读取失败就显示切换中。
    private static String controlState(boolean inWorld) {
        if (!inWorld) return "不可用";
        try {
            if (ClientRuntime.actor().body().automationOwnsControls()) return "自动操控";
            if (ClientRuntime.actor().automationControlRequested()) return "等待接管";
            return "玩家操控";
        } catch (RuntimeException ignored) {
            return "切换中";
        }
    }

    private static void appendLatestTerminalIssue(List<Row> rows) {
        IntentTaskRecord failed = IntentRuntime.get().tasks(20).stream()
                .filter(record -> record.getState() == TaskState.FAILED
                        || record.getState() == TaskState.TIMEOUT)
                .findFirst()
                .orElse(null);
        if (failed == null) return;
        String message = failed.terminalSnapshot() == null
                ? failed.getState().name().toLowerCase(Locale.ROOT)
                : jsonMessage(failed.terminalSnapshot().result(), failed.getState());
        rows.add(new Row("最近问题", clamp(message), ChatFormatting.RED));
    }

    private static String jsonMessage(JsonObject result, TaskState fallback) {
        if (result != null && result.has("message") && result.get("message").isJsonPrimitive()) {
            return result.get("message").getAsString();
        }
        return fallback.name().toLowerCase(Locale.ROOT);
    }

    // 菜单回执停在"无法证明服务器是否已执行"时进入 UNCERTAIN：消费可能已发生，先对账真实世界再决定。
    private static String uncertainText(MenuReceipt.UncertainSnapshot uncertain) {
        if (uncertain == null) return "无";
        String kind = switch (uncertain.kind()) {
            case CLICK -> "点击";
            case SWAP_TO_HOTBAR -> "换快捷栏";
            case PLACE_RECIPE -> "摆配方";
            case CLOSE -> "关闭界面";
            case BUTTON -> "按钮";
        };
        return kind + (uncertain.slot() >= 0 ? " 槽" + uncertain.slot() : "")
                + " · 第" + uncertain.submittedTick() + "刻提交 · 预算" + uncertain.timeoutTicks()
                + "刻 · " + clamp(uncertain.detail());
    }

    private static String clamp(String value) {
        if (value == null || value.isBlank()) return "未知";
        String singleLine = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").strip();
        return singleLine.length() <= TEXT_LIMIT
                ? singleLine : singleLine.substring(0, TEXT_LIMIT - 1) + "…";
    }

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

    private static void message(Minecraft minecraft, String text, ChatFormatting color) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text).withStyle(color), true);
        }
    }
}
