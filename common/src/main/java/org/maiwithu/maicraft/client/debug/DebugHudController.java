// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.debug;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
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
 * F9 切换的常驻调试面板，F9+H 在状态页与任务列表页间切换，F9+P 切换导航路线显示。
 * 每刻构建一次只读快照，渲染只画快照；本类不提交任何操作。布局分两段：上方固定状态行
 * （标签行文与 /maicraft status 各自独立），下方聊天框式事件区，长消息按面板宽度自动换行，
 * 新事件把旧事件挤出预算，固定行布局不受事件多少影响。
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
    /** 列表页最多展示的任务条数；更旧的仍在内存与检查点里，可从 MCP task list 完整读取。 */
    private static final int TASK_LIST_ROWS = 10;
    /** 列表页统计总数时读取的任务条数上限，与检查点容量一致。 */
    private static final int TASK_LIST_TOTAL_LIMIT = 256;

    private static final DateTimeFormatter EVENT_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static volatile Snapshot snapshot = new Snapshot(List.of(), List.of());
    private static boolean toggleWasDown;
    private static boolean comboWasDown;
    private static boolean pathComboWasDown;
    /** 面板当前页：false 为状态页（任务/动作等固定行），true 为任务列表页；面板隐藏期间保留。 */
    private static boolean listMode;
    private DebugHudController() {}

    public static void tick(Minecraft minecraft) {
        // F9 与 F8 一样直接轮询窗口按键：界面打开时键盘事件进不了 KeyMapping，而菜单流调试恰恰需要此刻可用。
        long window = minecraft.getWindow().getWindow();
        boolean f9Down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F9) == GLFW.GLFW_PRESS;
        boolean hDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_H) == GLFW.GLFW_PRESS;
        boolean pDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_P) == GLFW.GLFW_PRESS;
        boolean listCombo = f9Down && hDown;
        boolean pathCombo = f9Down && pDown;
        if (listCombo && !comboWasDown) {
            // F9+H 是无条件手势：面板没开就先打开，保证任何时候一步就能看到任务列表。
            listMode = !listMode;
            if (!PreviewConfig.hudVisible(minecraft.gameDirectory.toPath())) toggle(minecraft);
        }
        if (pathCombo && !pathComboWasDown) {
            togglePathLines(minecraft);
        }
        // 和弦按住的每一刻都记下 F9 已按下：字母键后松开不会误触发单独 F9 的显隐，
        // 单独 F9 也只在没有任何和弦时生效，避免 F9+P 第一刻同时翻转面板和路线。
        if (f9Down && !toggleWasDown && !listCombo && !pathCombo) {
            toggle(minecraft);
        }
        toggleWasDown = f9Down;
        comboWasDown = listCombo;
        pathComboWasDown = pathCombo;
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

        if (listMode) {
            appendTaskListPage(rows);
        } else {
            appendTaskStatusRows(rows, minecraft);
        }
        return new Snapshot(List.copyOf(rows), eventLines(minecraft.font, rows));
    }

    // 状态页的任务区：当前任务详情加身体动作，只看"此刻"。
    private static void appendTaskStatusRows(List<Row> rows, Minecraft minecraft) {
        // 从最近五十个任务里找第一个未结束的；没有时显示空闲，再查看最近二十个任务中的失败或超时。
        List<IntentTaskRecord> open = IntentRuntime.get().tasks(50).stream()
                .filter(record -> !record.getState().isTerminal())
                .toList();
        IntentTaskRecord active = open.isEmpty() ? null : open.getFirst();
        if (active == null) {
            rows.add(new Row("任务", "空闲", ChatFormatting.GRAY));
            appendLatestTerminalIssue(rows, minecraft.font);
            // 没有语义任务时反射（进食、休息、自救）仍可能占用身体；这种错位值得单独看见。
            CompanionTickDispatcher.BodyAction action = CompanionTickDispatcher.bodyAction();
            if (action != null && action.currentAction() != null) {
                rows.add(new Row("行动", clamp(action.currentAction()), actionColor(action)));
            }
        } else {
            // 尝试次数与排队数并入任务行，不单列；重试循环和积压都该在这行一眼看到。
            rows.add(new Row("任务", taskTitle(active)
                            + (active.attempts().isEmpty() ? "" : " · 尝试 " + active.attempts().size())
                            + (open.size() > 1 ? " · 队列 " + (open.size() - 1) : ""),
                    ChatFormatting.AQUA));
            // 等了多久和等什么同样重要：等 5 秒是在等模型，等 5 分钟大概率是卡死。
            long nowGameTime = minecraft.level == null ? 0 : minecraft.level.getGameTime();
            CompanionTickDispatcher.BodyAction action = CompanionTickDispatcher.bodyAction();
            appendMilestoneRow(rows, active, action);
            appendActionRow(rows, action);
            if (active.decisionSnapshot() != null) {
                appendDecisionRows(rows, active, nowGameTime);
            } else if (active.pauseSnapshot() != null) {
                rows.add(new Row("已暂停", clamp(active.pauseSnapshot().reason())
                                + " · 已暂停 " + formatDuration(nowGameTime - active.pauseSnapshot().gameTime()),
                        ChatFormatting.YELLOW));
            }
            if (!active.attempts().isEmpty()) {
                appendErrorRows(rows, minecraft.font, repeatSummary(active.attempts()));
            }
        }
    }

    // 进度行汇报任务单上的持久里程碑：目标计数来自当前干活者的任务单，步骤进度来自总任务单。
    // 都缺失显示"无"——缺口可见，催促对应执行器补齐汇报，不在这里编造退路。
    private static void appendMilestoneRow(List<Row> rows, IntentTaskRecord active,
                                           CompanionTickDispatcher.BodyAction action) {
        Map<String, Object> milestones = action == null ? Map.of() : action.milestones();
        List<String> parts = new ArrayList<>();
        if (milestones.get("done") instanceof Number done && milestones.get("total") instanceof Number total) {
            parts.add(done.intValue() + "/" + total.intValue());
        }
        int steps = active.steps().size();
        if (steps > 1) parts.add("步骤 " + Math.min(active.stepIndex() + 1, steps) + "/" + steps);
        rows.add(new Row("进度", parts.isEmpty() ? "无" : String.join(" · ", parts), ChatFormatting.AQUA));
    }

    // 行动行回答"这一刻身体在干什么"：执行器或反射自答的一句话，缺则"无"。
    // 反射自救时整行黄色——身体换了主人，行动内容与任务无关。
    private static void appendActionRow(List<Row> rows, CompanionTickDispatcher.BodyAction action) {
        String sentence = action == null || action.currentAction() == null ? "无" : clamp(action.currentAction());
        rows.add(new Row("行动", sentence, actionColor(action)));
    }

    private static ChatFormatting actionColor(CompanionTickDispatcher.BodyAction action) {
        return action != null && action.reflex() ? ChatFormatting.YELLOW : ChatFormatting.AQUA;
    }

    // 任务列表页回答"这段时间它都干了什么"：与 MCP task list 同源，含最近的终态记录。
    // 标题行标注页面身份与返回手势——列表页长得不像状态页，没有这行会被当成面板残缺。
    private static void appendTaskListPage(List<Row> rows) {
        List<IntentTaskRecord> all = IntentRuntime.get().tasks(TASK_LIST_TOTAL_LIMIT);
        int shown = Math.min(all.size(), TASK_LIST_ROWS);
        rows.add(new Row("任务列表", (all.size() > TASK_LIST_ROWS
                        ? "共 " + all.size() + " 条 · 最近 " + shown + " 条" : "共 " + all.size() + " 条")
                        + " · F9+H 返回",
                ChatFormatting.GREEN));
        if (all.isEmpty()) {
            rows.add(new Row("", "暂无任务记录", ChatFormatting.GRAY));
            return;
        }
        for (IntentTaskRecord record : all.subList(0, shown)) {
            rows.add(new Row("", listEntry(record), entryColor(record)));
        }
    }

    // 列表行复用状态页的状态前缀与标题；终态附结果消息，进行中附步骤进度。
    private static String listEntry(IntentTaskRecord record) {
        String entry = statePrefix(record) + " · " + abilityTitle(record.goal());
        if (record.getState().isTerminal()) {
            entry += " · " + terminalMessage(record);
        } else {
            int total = record.steps().size();
            if (total > 1) entry += " · 步骤 " + Math.min(record.stepIndex() + 1, total) + "/" + total;
        }
        return clamp(entry);
    }

    // 终态消息与"最近问题"行同源：终态快照 result.message 优先，缺失退回终态名。
    private static String terminalMessage(IntentTaskRecord record) {
        return record.terminalSnapshot() == null
                ? record.getState().name().toLowerCase(Locale.ROOT)
                : jsonMessage(record.terminalSnapshot().result(), record.getState());
    }

    // 配色沿用面板既有语义：青=进行，黄=等输入，红=问题，绿=成功，灰=取消等中性终态。
    private static ChatFormatting entryColor(IntentTaskRecord record) {
        if (record.decisionSnapshot() != null || record.paused()) return ChatFormatting.YELLOW;
        return switch (record.getState()) {
            case RUNNING, PENDING -> ChatFormatting.AQUA;
            case FAILED, TIMEOUT -> ChatFormatting.RED;
            case SUCCESS -> ChatFormatting.GREEN;
            case CANCELLED -> ChatFormatting.GRAY;
        };
    }

    // 事件区换行宽度随固定行的宽度走，整块面板保持一个矩形；事件少时固定行布局纹丝不动。
    private static List<EventLine> eventLines(Font font, List<Row> rows) {
        List<IntentRuntime.AttentionItem> events = IntentRuntime.get().recentAttention(8);
        if (events.isEmpty()) return List.of();
        int contentWidth = panelWidth(rows, font.getSplitter());
        List<EventLine> newestFirst = new ArrayList<>();
        int used = 0;
        for (int i = events.size() - 1; i >= 0; i--) {
            EventLine[] lines = wrapEvent(font, events.get(i), contentWidth);
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
        return wrap(font.getSplitter(), text, width);
    }

    private static List<String> wrap(StringSplitter splitter, String text, int width) {
        String singleLine = text.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").strip();
        List<String> lines = new ArrayList<>();
        for (FormattedText line : splitter.splitLines(singleLine, width, Style.EMPTY)) {
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

    private static void appendLatestTerminalIssue(List<Row> rows, Font font) {
        IntentTaskRecord failed = IntentRuntime.get().tasks(20).stream()
                .filter(record -> record.getState() == TaskState.FAILED
                        || record.getState() == TaskState.TIMEOUT)
                .findFirst()
                .orElse(null);
        if (failed == null) return;
        String message = failed.terminalSnapshot() == null
                ? failed.getState().name().toLowerCase(Locale.ROOT)
                : jsonMessage(failed.terminalSnapshot().result(), failed.getState());
        appendErrorRows(rows, font, message);
    }

    // 最新报错不再截成一行：宽度跟随其余固定行撑起的面板宽度（首行扣除标签），与事件区
    // 相同的 4 行上限，放不下的尾部以 … 收尾；空白报错没有可显示内容，落"未知"保持行可见。
    private static void appendErrorRows(List<Row> rows, Font font, String message) {
        StringSplitter splitter = font.getSplitter();
        rows.addAll(errorRows(message, panelWidth(rows, splitter), splitter));
    }

    /** 折行与行拆分单独成纯函数：StringSplitter 可脱离游戏实例构造，回归用假宽度函数即可覆盖。 */
    static List<Row> errorRows(String message, int panelWidth, StringSplitter splitter) {
        String safe = message == null || message.isBlank() ? "未知" : message;
        String label = "最新报错: ";
        int lineWidth = Math.max(80, panelWidth - (int) splitter.stringWidth(label));
        List<Row> rows = new ArrayList<>();
        List<String> lines = wrap(splitter, safe, lineWidth);
        for (int i = 0; i < lines.size(); i++) {
            rows.add(new Row(i == 0 ? "最新报错" : "", lines.get(i), ChatFormatting.RED));
        }
        return rows;
    }

    // 面板宽度由已确定的固定行撑起；最新报错行与事件区共用这个宽度，不各自把面板加宽。
    static int panelWidth(List<Row> rows, StringSplitter splitter) {
        int width = 0;
        for (Row row : rows) {
            width = Math.max(width, (row.label().isBlank() ? 0
                    : (int) splitter.stringWidth(row.label() + ": "))
                    + (int) splitter.stringWidth(row.value()));
        }
        return Math.max(160, width);
    }

    private static String jsonMessage(JsonObject result, TaskState fallback) {
        if (result != null && result.has("message") && result.get("message").isJsonPrimitive()) {
            return result.get("message").getAsString();
        }
        return fallback.name().toLowerCase(Locale.ROOT);
    }

    // 决策是卡点：问题与选项是应答依据，完整显示并拆成两行，单行截断会把依据截掉。
    private static void appendDecisionRows(List<Row> rows, IntentTaskRecord active, long nowGameTime) {
        IntentTaskRecord.DecisionSnapshot decision = active.decisionSnapshot();
        String question = decision.question() == null ? "未知"
                : decision.question().replace('\r', ' ').replace('\n', ' ')
                        .replaceAll("\\s+", " ").strip();
        IntentTaskRecord.PauseSnapshot pause = active.pauseSnapshot();
        String waiting = pause == null ? ""
                : " · 已等 " + formatDuration(nowGameTime - pause.gameTime());
        rows.add(new Row("等待决策", question + waiting, ChatFormatting.YELLOW));
        String choices = decision.options().stream()
                .map(IntentTaskRecord.DecisionOption::choice)
                .collect(Collectors.joining(" / "));
        rows.add(new Row("选项", choices.isBlank() ? "未知" : choices, ChatFormatting.YELLOW));
    }

    // 游戏刻换算时长（20 刻/秒）；秒与分级够定位卡死，超过一小时不再显示秒。
    private static String formatDuration(long ticks) {
        long seconds = Math.max(0, ticks / 20);
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m" + (seconds % 60) + "s";
        return (seconds / 3600) + "h" + (seconds % 3600 / 60) + "m";
    }

    // 连续相同的失败只报一次附次数：重试循环里"×N"比同一句话重复出现更能说明卡死。
    // 完整报文交给最新报错行折行展示，这里不再按单行截断。
    private static String repeatSummary(List<IntentTaskRecord.AttemptSnapshot> attempts) {
        String last = attempts.getLast().message();
        if (last == null || last.isBlank()) return "未知";
        int repeats = 1;
        for (int i = attempts.size() - 2; i >= 0 && last.equals(attempts.get(i).message()); i--) {
            repeats++;
        }
        return repeats > 1 ? last + " ×" + repeats : last;
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
        message(minecraft, show ? "调试面板已显示，再按 F9 隐藏；F9+H 切任务列表页，F9+P 切导航路线。"
                : "调试面板已隐藏。", ChatFormatting.GREEN);
    }

    // F9+P 只切导航路线的独立开关：与 Dev 分离，看路线不牵动施工预览。
    private static void togglePathLines(Minecraft minecraft) {
        boolean show = !PreviewConfig.pathLines(minecraft.gameDirectory.toPath());
        try {
            PreviewConfig.pathLines(show);
        } catch (IOException failure) {
            message(minecraft, "导航路线 " + (show ? "已显示" : "已隐藏")
                    + "，但配置保存失败：" + failure.getMessage(), ChatFormatting.YELLOW);
            return;
        }
        message(minecraft, show ? "导航路线已显示，再按 F9+P 隐藏。" : "导航路线已隐藏。",
                ChatFormatting.GREEN);
    }

    private static void message(Minecraft minecraft, String text, ChatFormatting color) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text).withStyle(color), true);
        }
    }
}
