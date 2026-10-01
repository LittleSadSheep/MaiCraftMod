// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.command;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * 整理 /maicraft status 的本地说明：MCP 是否监听、身体控制权、当前任务和最近问题，不改变任务状态。
 * 状态行先聚合成 {@link StatusRow}，聊天命令与 F9 调试面板各自格式化，保证两个出口只有一份数据。
 * 面板与游戏内提示的读者是人，取值用中文；协议专名（MCP、端口号）保留原文。
 */
public final class MaiCraftStatus {
    /** 面板单行与聊天单行共用同一上限；被截断的值以 … 结尾，全文在任务 attempts 与日志里。 */
    private static final int TEXT_LIMIT = 64;

    /** 一条状态说明：短标签、当前值和取值颜色；值必须是已经压平的短文本。 */
    public record StatusRow(String label, String value, ChatFormatting color) {}

    private MaiCraftStatus() {}

    /**
     * 直接把状态文字放进本地聊天显示区，不把自己的状态说明重新当作外部聊天事件。
     */
    public static int showInChat() {
        Minecraft minecraft = Minecraft.getInstance();
        lines().forEach(minecraft.gui.getChat()::addMessage);
        return 1;
    }

    public static List<Component> lines() {
        List<Component> lines = new ArrayList<>();
        for (StatusRow row : rows()) lines.add(line(row));
        return List.copyOf(lines);
    }

    /** 只读聚合当前运行状态；调用方自行决定渲染成聊天行还是调试面板行。 */
    public static List<StatusRow> rows() {
        Minecraft minecraft = Minecraft.getInstance();
        List<StatusRow> rows = new ArrayList<>();

        boolean listening = ClientRuntime.isMcpRunning();
        rows.add(new StatusRow("MCP", listening
                ? "监听 127.0.0.1:" + ClientRuntime.mcpPort()
                        + " · AI 连接 " + ClientRuntime.mcpSessionCount()
                : ClientRuntime.lastMcpError() == null ? "未监听"
                        : "未监听 · " + compact(ClientRuntime.lastMcpError()),
                listening ? ChatFormatting.GREEN : ChatFormatting.RED));

        boolean inWorld = minecraft.player != null && minecraft.level != null
                && minecraft.gameMode != null && minecraft.getConnection() != null;
        rows.add(new StatusRow("身体", (inWorld ? "就绪" : "等待世界") + " · " + controlState(inWorld),
                inWorld ? ChatFormatting.GREEN : ChatFormatting.YELLOW));

        // 从最近五十个任务里找第一个未结束的；没有时显示空闲，再查看最近二十个任务中的失败或超时。
        IntentTaskRecord active = IntentRuntime.get().tasks(50).stream()
                .filter(record -> !record.getState().isTerminal())
                .findFirst()
                .orElse(null);
        if (active == null) {
            rows.add(new StatusRow("任务", "空闲", ChatFormatting.GRAY));
            appendLatestTerminalIssue(rows);
            return List.copyOf(rows);
        }

        int total = Math.max(1, active.steps().size());
        int current = Math.min(active.stepIndex() + 1, total);
        rows.add(new StatusRow("任务", taskTitle(active) + " · 步骤 " + current + "/" + total,
                ChatFormatting.AQUA));

        if (active.decisionSnapshot() != null) {
            rows.add(new StatusRow("等待决策", "等待大模型选择后才能继续", ChatFormatting.YELLOW));
        } else if (active.pauseSnapshot() != null) {
            rows.add(new StatusRow("已暂停", compact(active.pauseSnapshot().reason()),
                    ChatFormatting.YELLOW));
        }
        if (!active.attempts().isEmpty()) {
            IntentTaskRecord.AttemptSnapshot attempt = active.attempts().getLast();
            rows.add(new StatusRow("最近问题", compact(attempt.message()), ChatFormatting.RED));
        }
        return List.copyOf(rows);
    }

    // 任务行以标题为主体：能力短名加目标陈述，状态前缀只标注它此刻在等什么。
    private static String taskTitle(IntentTaskRecord record) {
        String state = publicState(record);
        String ability = record.goal().ability();
        int namespace = ability.indexOf(':');
        String shortAbility = namespace >= 0 ? ability.substring(namespace + 1) : ability;
        return state + " · " + shortAbility + "：" + compact(record.goal().outcome());
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

    private static String publicState(IntentTaskRecord record) {
        if (record.decisionSnapshot() != null) return "待决策";
        if (record.paused()) return "已暂停";
        return switch (record.getState()) {
            case RUNNING -> "执行中";
            case PENDING -> "排队中";
            default -> record.getState().name().toLowerCase(Locale.ROOT);
        };
    }

    private static void appendLatestTerminalIssue(List<StatusRow> rows) {
        IntentTaskRecord failed = IntentRuntime.get().tasks(20).stream()
                .filter(record -> record.getState() == TaskState.FAILED
                        || record.getState() == TaskState.TIMEOUT)
                .findFirst()
                .orElse(null);
        if (failed == null) return;
        String message = failed.terminalSnapshot() == null
                ? failed.getState().name().toLowerCase(Locale.ROOT)
                : jsonMessage(failed.terminalSnapshot().result(), failed.getState());
        rows.add(new StatusRow("最近问题", compact(message), ChatFormatting.RED));
    }

    private static String jsonMessage(JsonObject result, TaskState fallback) {
        if (result != null && result.has("message") && result.get("message").isJsonPrimitive()) {
            return result.get("message").getAsString();
        }
        return fallback.name().toLowerCase(Locale.ROOT);
    }

    private static Component line(StatusRow row) {
        return Component.literal("[MaiCraft] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(row.label() + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(row.value()).withStyle(row.color()));
    }

    // 把多行和连续空白压成一行；超限截断以 … 结尾，让人看得出后面还有内容。
    private static String compact(String value) {
        if (value == null || value.isBlank()) return "未知";
        String singleLine = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").strip();
        return singleLine.length() <= TEXT_LIMIT
                ? singleLine : singleLine.substring(0, TEXT_LIMIT - 1) + "…";
    }
}
