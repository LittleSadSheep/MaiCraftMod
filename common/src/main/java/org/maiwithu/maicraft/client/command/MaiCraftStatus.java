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

/**
 * 整理 /maicraft status 的本地说明：MCP 是否监听、角色控制权、当前任务和最近问题，不改变任务状态。
 */
public final class MaiCraftStatus {
    private static final int TEXT_LIMIT = 160;

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
        Minecraft minecraft = Minecraft.getInstance();
        List<Component> lines = new ArrayList<>();

        boolean listening = ClientRuntime.isMcpRunning();
        String endpoint = listening
                ? "127.0.0.1:" + ClientRuntime.mcpPort()
                : ClientRuntime.lastMcpError() == null
                ? "stopped" : "stopped · " + compact(ClientRuntime.lastMcpError());
        lines.add(line("MCP", listening
                ? endpoint + " · sessions " + ClientRuntime.mcpSessionCount()
                : endpoint, listening ? ChatFormatting.GREEN : ChatFormatting.RED));

        boolean inWorld = minecraft.player != null && minecraft.level != null
                && minecraft.gameMode != null && minecraft.getConnection() != null;
        String control = controlState(inWorld);
        lines.add(line("Runtime", (inWorld ? "ready" : "waiting for a world")
                + " · control " + control,
                inWorld ? ChatFormatting.GREEN : ChatFormatting.YELLOW));

        // 从最近五十个任务里找第一个未结束的；没有时显示空闲，再查看最近二十个任务中的失败或超时。
        IntentTaskRecord active = IntentRuntime.get().tasks(50).stream()
                .filter(record -> !record.getState().isTerminal())
                .findFirst()
                .orElse(null);
        if (active == null) {
            lines.add(line("Task", "idle", ChatFormatting.GRAY));
            appendLatestTerminalIssue(lines);
            return List.copyOf(lines);
        }

        String state = publicState(active);
        int total = Math.max(1, active.steps().size());
        int current = Math.min(active.stepIndex() + 1, total);
        lines.add(line("Task", state + " · step " + current + "/" + total
                + " · " + compact(active.goal().outcome()), ChatFormatting.AQUA));

        if (active.decisionSnapshot() != null) {
            lines.add(line("Blocked", "waiting for LLM decision",
                    ChatFormatting.YELLOW));
        } else if (active.pauseSnapshot() != null) {
            lines.add(line("Paused", compact(active.pauseSnapshot().reason()),
                    ChatFormatting.YELLOW));
        }
        if (!active.attempts().isEmpty()) {
            IntentTaskRecord.AttemptSnapshot attempt = active.attempts().getLast();
            lines.add(line("Last issue", compact(attempt.message()), ChatFormatting.RED));
        }
        return List.copyOf(lines);
    }

    // 区分已经接管、申请接管但还没拿到控制权，以及仍由玩家操作；切换中读取失败就显示 transitioning。
    private static String controlState(boolean inWorld) {
        if (!inWorld) return "unavailable";
        try {
            if (ClientRuntime.actor().body().automationOwnsControls()) return "MaiCraft";
            if (ClientRuntime.actor().automationControlRequested()) return "takeover pending";
            return "player";
        } catch (RuntimeException ignored) {
            return "transitioning";
        }
    }

    private static String publicState(IntentTaskRecord record) {
        if (record.decisionSnapshot() != null) return "waiting_for_decision";
        if (record.paused()) return "paused";
        return record.getState().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void appendLatestTerminalIssue(List<Component> lines) {
        IntentTaskRecord failed = IntentRuntime.get().tasks(20).stream()
                .filter(record -> record.getState() == TaskState.FAILED
                        || record.getState() == TaskState.TIMEOUT)
                .findFirst()
                .orElse(null);
        if (failed == null) return;
        String message = failed.terminalSnapshot() == null
                ? failed.getState().name().toLowerCase(java.util.Locale.ROOT)
                : jsonMessage(failed.terminalSnapshot().result(), failed.getState());
        lines.add(line("Last issue", compact(message), ChatFormatting.RED));
    }

    private static String jsonMessage(com.google.gson.JsonObject result, TaskState fallback) {
        if (result != null && result.has("message") && result.get("message").isJsonPrimitive()) {
            return result.get("message").getAsString();
        }
        return fallback.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static Component line(String label, String value, ChatFormatting valueColor) {
        return Component.literal("[MaiCraft] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(value).withStyle(valueColor));
    }

    // 把多行和连续空白压成一行，最长显示 160 个字符，避免一条错误刷满聊天区域。
    private static String compact(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String singleLine = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").strip();
        return singleLine.length() <= TEXT_LIMIT
                ? singleLine : singleLine.substring(0, TEXT_LIMIT - 1) + "…";
    }
}
