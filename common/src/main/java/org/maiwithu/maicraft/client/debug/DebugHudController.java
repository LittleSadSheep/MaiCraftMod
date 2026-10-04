// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.debug;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.maiwithu.maicraft.client.preview.PreviewConfig;
import org.maiwithu.maicraft.client.preview.PreviewController;
import org.maiwithu.maicraft.client.preview.PreviewSession;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.mcp.McpActivityTrace;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskState;
import com.google.gson.JsonObject;

/**
 * F9 切换的常驻调试面板。每刻构建一次只读快照，渲染只画快照；本类不提交任何操作。
 * 布局分两段：上方固定状态行（标签行文与 /maicraft status 各自独立），下方聊天框式事件区，
 * 长消息按面板宽度自动换行，新事件把旧事件挤出预算，固定行布局不受事件多少影响。
 */
public final class DebugHudController {
    /** 固定区一行：短标签、当前值和取值颜色；值必须是已经压平的短文本。 */
    public record Row(String label, String value, ChatFormatting color) {}

    /** 事件行的一个着色片段：时间、类型、内容各用各的颜色，渲染时按顺序拼在同行。 */
    public record Segment(String text, ChatFormatting color) {}

    /** 事件区一行，由一个或多个着色片段组成；换行产生的续行只有内容片段。 */
    public record EventLine(List<Segment> segments) {}

    /** 每刻快照：固定状态行加事件区换行结果；面板不可见时两段皆空。 */
    public record Snapshot(List<Row> rows, List<EventLine> events) {}

    /** 面板单行放不下的说明截断到 64 字符并以 … 结尾，让人看得出后面还有内容。 */
    private static final int TEXT_LIMIT = 64;
    /** 事件区换行后的总行数预算：占满后更旧的事件整体让位，形成聊天框式滚动。 */
    private static final int EVENT_LINE_BUDGET = 12;
    /** 单条事件换行后最多 4 行，仍然超长在行尾补 …。 */
    private static final int EVENT_MAX_LINES = 4;

    private static final DateTimeFormatter EVENT_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static volatile Snapshot snapshot = new Snapshot(List.of(), List.of());
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
                ? new Snapshot(List.of(), List.of()) : buildSnapshot(minecraft);
    }

    /** 渲染层每帧读取的最近一次快照；不可见时两段皆空，渲染器据此不画。 */
    public static Snapshot snapshot() { return snapshot; }

    private static Snapshot buildSnapshot(Minecraft minecraft) {
        List<Row> rows = new ArrayList<>();

        boolean listening = ClientRuntime.isMcpRunning();
        rows.add(new Row("MCP", listening
                ? "127.0.0.1:" + ClientRuntime.mcpPort()
                        + " · 会话数 " + ClientRuntime.mcpSessionCount()
                : ClientRuntime.lastMcpError() == null ? "未监听"
                        : "未监听 · " + clamp(ClientRuntime.lastMcpError()),
                listening ? ChatFormatting.GREEN : ChatFormatting.RED));

        // LLM 反复感知而不提交目标就是效率问题；计数自上次 execute 起算，视图是它最近一次看的。
        String views = McpActivityTrace.lastPerceiveViews();
        if (views != null) {
            rows.add(new Row("感知", (views == null || views.isBlank() ? "默认" : views)
                    + " · 距上次行动 " + McpActivityTrace.perceivesSinceAction() + " 次",
                    ChatFormatting.GRAY));
        }

        boolean inWorld = minecraft.player != null && minecraft.level != null
                && minecraft.gameMode != null && minecraft.getConnection() != null;
        rows.add(new Row("身体", (inWorld ? "就绪" : "等待世界") + " · " + controlState(inWorld),
                inWorld ? ChatFormatting.GREEN : ChatFormatting.YELLOW));

        // 施工任务停在半途的最常见原因是预览还在等确认；蓝图行让这个等待可见。
        PreviewSession preview = PreviewController.current();
        if (preview != null) {
            rows.add(new Row("蓝图", blueprintText(preview), blueprintColor(preview)));
        }

        // 从最近五十个任务里找第一个未结束的；没有时显示空闲，再查看最近二十个任务中的失败或超时。
        List<IntentTaskRecord> open = IntentRuntime.get().tasks(50).stream()
                .filter(record -> !record.getState().isTerminal())
                .toList();
        IntentTaskRecord active = open.isEmpty() ? null : open.getFirst();
        if (active == null) {
            rows.add(new Row("任务", "空闲", ChatFormatting.GRAY));
            appendLatestTerminalIssue(rows);
            // 没有语义任务时反射（进食、休息、自救）仍可能占用身体；这种错位值得单独看见。
            String action = CompanionTickDispatcher.bodyAction();
            if (action != null) {
                rows.add(new Row("动作", clamp(action), ChatFormatting.AQUA));
            }
        } else {
            // 单步目标的"步骤 1/1"没有信息量，只在清单有多步时报进度；"步骤"一词留给语义清单专用。
            int total = active.steps().size();
            String progress = total > 1
                    ? " · 步骤 " + Math.min(active.stepIndex() + 1, total) + "/" + total : "";
            // 尝试次数与排队数并入任务行，不单列；重试循环和积压都该在这行一眼看到。
            rows.add(new Row("任务", taskTitle(active) + progress
                            + (active.attempts().isEmpty() ? "" : " · 尝试 " + active.attempts().size())
                            + (open.size() > 1 ? " · 队列 " + (open.size() - 1) : ""),
                    ChatFormatting.AQUA));
            rows.add(new Row("动作", actionText(), ChatFormatting.AQUA));
            if (active.decisionSnapshot() != null) {
                rows.add(new Row("等待决策", decisionText(active.decisionSnapshot()),
                        ChatFormatting.YELLOW));
            } else if (active.pauseSnapshot() != null) {
                rows.add(new Row("已暂停", clamp(active.pauseSnapshot().reason()),
                        ChatFormatting.YELLOW));
            }
            if (!active.attempts().isEmpty()) {
                rows.add(new Row("最近问题", clamp(active.attempts().getLast().message()),
                        ChatFormatting.RED));
            }
        }
        return new Snapshot(List.copyOf(rows), eventLines(minecraft.font, rows));
    }

    // 事件区换行宽度随固定行的宽度走，整块面板保持一个矩形；事件少时固定行布局纹丝不动。
    private static List<EventLine> eventLines(Font font, List<Row> rows) {
        List<IntentRuntime.AttentionItem> events = IntentRuntime.get().recentAttention(8);
        if (events.isEmpty()) return List.of();
        int contentWidth = 0;
        for (Row row : rows) {
            contentWidth = Math.max(contentWidth,
                    font.width(row.label().isBlank() ? "" : row.label() + ": ")
                            + font.width(row.value()));
        }
        List<EventLine> newestFirst = new ArrayList<>();
        int used = 0;
        for (int i = events.size() - 1; i >= 0; i--) {
            EventLine[] lines = wrapEvent(font, events.get(i), Math.max(160, contentWidth));
            if (used + lines.length > EVENT_LINE_BUDGET) break;
            for (int j = lines.length - 1; j >= 0; j--) newestFirst.addFirst(lines[j]);
            used += lines.length;
        }
        return List.copyOf(newestFirst);
    }

    // 时间与类型着色后占首行行首，内容从剩余宽度换行；灰类型是 world.* 降权事件。
    private static EventLine[] wrapEvent(Font font, IntentRuntime.AttentionItem event, int contentWidth) {
        String time = EVENT_TIME.format(event.timestamp().atZone(ZoneId.systemDefault())) + " ";
        ChatFormatting priorityColor = switch (event.priority()) {
            case "important" -> ChatFormatting.YELLOW;
            case "task" -> ChatFormatting.AQUA;
            default -> ChatFormatting.GRAY;
        };
        String head = event.type() + ": ";
        Segment timeSegment = new Segment(time, ChatFormatting.GRAY);
        Segment typeSegment = new Segment(head, priorityColor);
        int messageWidth = Math.max(80, contentWidth - font.width(time) - font.width(head));
        List<String> messageLines = wrap(font, event.message(), messageWidth);
        EventLine[] lines = new EventLine[messageLines.size()];
        for (int i = 0; i < messageLines.size(); i++) {
            lines[i] = i == 0
                    ? new EventLine(List.of(timeSegment, typeSegment,
                            new Segment(messageLines.getFirst(), ChatFormatting.WHITE)))
                    : new EventLine(List.of(new Segment(messageLines.get(i), ChatFormatting.WHITE)));
        }
        return lines;
    }

    /** 按字形宽度把内容折行：超过上限在最后一行行尾补 …；由字体分词器断行，中英文都不断在词中间。 */
    private static List<String> wrap(Font font, String text, int width) {
        String singleLine = text.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").strip();
        List<String> lines = new ArrayList<>();
        for (FormattedText line : font.getSplitter().splitLines(singleLine, width, Style.EMPTY)) {
            lines.add(line.getString());
        }
        if (lines.size() > EVENT_MAX_LINES) {
            lines = new ArrayList<>(lines.subList(0, EVENT_MAX_LINES));
            lines.set(EVENT_MAX_LINES - 1, lines.getLast() + "…");
        }
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    // 任务行以标题为主体：能力短名加目标陈述，状态前缀只标注它此刻在等什么。
    private static String taskTitle(IntentTaskRecord record) {
        return statePrefix(record) + " · " + abilityTitle(record.goal());
    }

    // 动作行回答"身体此刻在干什么"：调度层当前持有者的描述，反射自救与临时动作都在这里显形。
    // 语义任务在跑而这里显示"无"，说明身体空转（等预览确认、等决策），本身就是要看见的状态。
    private static String actionText() {
        String action = CompanionTickDispatcher.bodyAction();
        return action == null ? "无" : clamp(action);
    }

    private static String abilityTitle(Goal goal) {
        String ability = goal.ability();
        int namespace = ability.indexOf(':');
        String shortAbility = namespace >= 0 ? ability.substring(namespace + 1) : ability;
        return shortAbility + "：" + clamp(goal.outcome());
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

    // 决策行要说出在等什么：问题摘要加可选项；只重复"在等决策"没有信息量。
    private static String decisionText(IntentTaskRecord.DecisionSnapshot decision) {
        String choices = decision.options().stream()
                .map(IntentTaskRecord.DecisionOption::choice)
                .collect(Collectors.joining("/"));
        String text = clamp(decision.question(), 40)
                + (choices.isBlank() ? "" : " · 选项 " + clamp(choices, 24));
        return clamp(text);
    }

    // 蓝图行回答三件事：这是什么预览、它多大、施工卡在哪个决定上；隐藏与切片也值得看见。
    private static String blueprintText(PreviewSession preview) {
        String text = (preview.designOnly() ? "只读设计" : "施工")
                + " · " + clamp(preview.title(), 32)
                + " · " + preview.cells().size() + " 格 · " + blueprintState(preview);
        if (!preview.visible()) text += " · 已隐藏";
        if (preview.minY() != Integer.MIN_VALUE) {
            text += " · 层 " + preview.minY() + ".." + preview.maxY();
        }
        return clamp(text);
    }

    private static String blueprintState(PreviewSession preview) {
        return switch (preview.decision()) {
            case WAITING -> "等待确认";
            case CONFIRMED -> "已确认开工";
            case CANCELLED -> "已取消";
            case DISABLED -> "Dev 未开启";
            case DESIGN_ONLY -> "仅查看";
        };
    }

    private static ChatFormatting blueprintColor(PreviewSession preview) {
        return switch (preview.decision()) {
            case WAITING -> ChatFormatting.YELLOW;
            case CANCELLED -> ChatFormatting.RED;
            default -> ChatFormatting.AQUA;
        };
    }

    private static String clamp(String value) {
        if (value == null || value.isBlank()) return "未知";
        return clamp(value, TEXT_LIMIT);
    }

    private static String clamp(String value, int limit) {
        if (value == null || value.isBlank()) return "未知";
        String singleLine = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").strip();
        return singleLine.length() <= limit
                ? singleLine : singleLine.substring(0, limit - 1) + "…";
    }

    private static void toggle(Minecraft minecraft) {
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
