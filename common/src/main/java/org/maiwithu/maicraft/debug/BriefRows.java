// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskProgress;

/**
 * 简要档：给人看的。目标、此刻两块常驻；一切正常时就这两块，出事时按要紧程度往下加提醒行。
 * 连接、服务端、死亡、F8、在等回答五种总是显示，其余最多再显示三行，多出来的合成一行。
 */
final class BriefRows {
    /** 简要档可能出现的全部标签；标签栏宽度按它们里最宽的定，免得内容一变正文就左右跳。 */
    static final List<String> LABELS = List.of("目标", "此刻", "连接", "在等", "提醒");
    /** 简要档里一行内容最多折两行。 */
    static final int ROW_LINES = 2;
    /** 不是必显的提醒最多几条。 */
    static final int OPTIONAL_ALERTS = 3;

    private BriefRows() {}

    static List<Row> rows(Moment moment) {
        List<Row> rows = new ArrayList<>(goal(moment));
        Row now = now(moment);
        if (now != null) rows.add(now);
        rows.addAll(alerts(moment));
        return rows;
    }

    // 目标块：第一行只放短字段，purpose 另起一行，最多两行；手上没目标时说在等什么。
    private static List<Row> goal(Moment moment) {
        StatusSnapshot.GoalLine goal = moment.mainGoal();
        if (goal == null) {
            String idle = moment.status().connection().control().inWorld() ? "空闲，等 LLM 下达目标" : "不在世界里";
            return List.of(Row.of("目标", 1, idle, PanelColor.MINOR));
        }
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of("目标", 1, StatusSentences.goalHead(moment, goal, false)));
        String purpose = StatusSentences.purpose(goal);
        if (!purpose.isBlank()) rows.add(Row.of("", ROW_LINES, purpose, PanelColor.TEXT));
        return rows;
    }

    /**
     * 此刻：角色这一刻在听的那个任务说的话。临时任务在推进时以需求名开头、整行标黄；
     * 主任务在等回答、暂停时不写此刻，免得和"在等"那行说同一件事。
     */
    static Row now(Moment moment) {
        StatusSnapshot.Layer top = moment.topLayer();
        if (moment.loop().state() != StatusSnapshot.LoopState.WORKING || top == null) return null;
        boolean temporary = top.needName() != null;
        StatusSnapshot.GoalLine goal = moment.mainGoal();
        if (!temporary && goal != null && goal.state() != GoalRunState.RUNNING) return null;
        TaskProgress progress = moment.progress();
        String doing = progress != null ? progress.doing() : top.doing();
        return temporary
                ? Row.of("此刻", ROW_LINES, StatusSentences.layer(top, doing, null, true), PanelColor.WAITING)
                : Row.of("此刻", ROW_LINES, doing, PanelColor.DOING);
    }

    // 提醒行：必显的五种在前，其余按要紧程度最多三条，多出来的合成一行"还有 N 条"。
    private static List<Row> alerts(Moment moment) {
        List<Row> rows = new ArrayList<>();
        connection(moment, rows);
        death(moment, rows);
        control(moment, rows);
        if (moment.status().goals().question() != null) {
            rows.addAll(StatusSentences.question(moment, "在等", moment.status().goals().question(),
                    FirstSeenTimes.goalState(moment.mainGoal())));
        }
        List<Row> optional = optionalAlerts(moment);
        rows.addAll(optional.subList(0, Math.min(OPTIONAL_ALERTS, optional.size())));
        if (optional.size() > OPTIONAL_ALERTS) {
            rows.add(Row.of("提醒", 1, "还有 " + (optional.size() - OPTIONAL_ALERTS) + " 条，按 F9 看详细", PanelColor.MINOR));
        }
        return rows;
    }

    private static void connection(Moment moment, List<Row> rows) {
        StatusSnapshot.Connection connection = moment.status().connection();
        if (!connection.mcpListening()) {
            String why = connection.mcpStartFailure() == null ? "" : "：" + connection.mcpStartFailure();
            rows.add(Row.of("连接", ROW_LINES, "MCP 没在监听" + why, PanelColor.PROBLEM));
        } else if (connection.hosts() == 0) {
            rows.add(Row.of("连接", ROW_LINES, "MCP 在 127.0.0.1:" + connection.mcpPort() + " 监听，还没有宿主连上",
                    PanelColor.PROBLEM));
        }
        StatusSentences.serverLink(connection).ifPresent(piece -> rows.add(Row.of("连接", ROW_LINES, List.of(piece))));
    }

    // 角色死了：说等了多久；挂出了死亡恢复的问题就把问题和选项接着写全。
    private static void death(Moment moment, List<Row> rows) {
        StatusSnapshot.Goals goals = moment.status().goals();
        boolean dead = moment.loop().state() == StatusSnapshot.LoopState.WAITING_RESPAWN;
        if (!dead && goals.deathQuestion() == null) return;
        rows.add(Row.of("在等", ROW_LINES, "角色死了，等重生 · " + PanelWords.duration(moment.ageMillis(FirstSeenTimes.DEATH)),
                PanelColor.PROBLEM));
        if (goals.deathQuestion() != null) {
            rows.addAll(StatusSentences.question(moment, "", goals.deathQuestion(), FirstSeenTimes.DEATH));
        }
    }

    // 角色不在自动化手上：人按了 F8 就说怎么交回；自动化要了还没拿到就说在等交接、为什么。
    private static void control(Moment moment, List<Row> rows) {
        StatusSnapshot.Control control = moment.status().connection().control();
        if (!control.inWorld() || control.automationOwns()) return;
        if (control.humanTookOver()) {
            rows.add(Row.of("在等", ROW_LINES, "角色在玩家手上（F8），再按 F8 交回", PanelColor.WAITING));
        } else if (control.automationRequested()) {
            String why = control.unavailableReason() == null ? "" : "：" + control.unavailableReason();
            rows.add(Row.of("在等", ROW_LINES, "等控制权交接" + why, PanelColor.WAITING));
        }
    }

    // 不是必显的提醒，按要紧程度排：暂停、停在半路、快卡住、生存需求、刚失败、接连报错、掉速。
    private static List<Row> optionalAlerts(Moment moment) {
        List<Row> rows = new ArrayList<>();
        StatusSnapshot.GoalLine goal = moment.mainGoal();
        if (goal != null && goal.state() == GoalRunState.PAUSED) {
            rows.add(Row.of("在等", ROW_LINES, "已暂停，等 LLM 恢复 · "
                    + PanelWords.duration(moment.ageMillis(FirstSeenTimes.goalState(goal))), PanelColor.WAITING));
        }
        if (moment.loop().state() == StatusSnapshot.LoopState.PARKED) {
            String where = moment.loop().parkedWhere() == null ? "位置不明" : moment.loop().parkedWhere();
            rows.add(Row.of("在等", ROW_LINES, "主任务停在半路：" + where + "，等 LLM 换活", PanelColor.WAITING));
        }
        if (moment.loop().state() == StatusSnapshot.LoopState.WORKING && StatusSentences.nearlyStuck(moment.progress())) {
            rows.add(Row.of("提醒", ROW_LINES, StatusSentences.stalled(moment.progress()), PanelColor.WAITING));
        }
        StatusSnapshot.HeldBack held = moment.loop().heldBack();
        if (held != null && !StatusSentences.alsoWaitingToRetry(moment, held)) {
            rows.add(Row.of("提醒", ROW_LINES, StatusSentences.heldBack(held), PanelColor.WAITING));
        }
        StatusSentences.recentUnhandledNeed(moment).ifPresent(event -> rows.add(Row.of("提醒", ROW_LINES,
                event.message() + " · " + PanelWords.duration(moment.ageMillis(FirstSeenTimes.event(event))) + " 前",
                PanelColor.WAITING)));
        recentFailure(moment, rows);
        callErrors(moment, rows);
        StatusSnapshot.Performance performance = moment.status().connection().performance();
        if (performance != null && performance.msPerTick() > performance.targetMsPerTick()) {
            rows.add(Row.of("提醒", 1, List.of(new PanelLine.Piece("掉速：", PanelColor.WAITING),
                    StatusSentences.performance(performance))));
        }
        return rows;
    }

    // 刚失败：最近下达的那个目标失败了，一分钟内提醒；下一个目标一开始就收起。
    private static void recentFailure(Moment moment, List<Row> rows) {
        List<StatusSnapshot.GoalLine> recent = moment.status().goals().recent().stream()
                .filter(goal -> goal.id() >= 0).toList();
        if (recent.isEmpty()) return;
        StatusSnapshot.GoalLine latest = recent.getFirst();
        if (latest.state() != GoalRunState.FINISHED || latest.result() == null
                || latest.result().status() != TaskResult.Status.FAILED) return;
        long age = moment.ageMillis(FirstSeenTimes.goalState(latest));
        if (age > StatusSentences.RECENT_MILLIS) return;
        rows.add(Row.of("提醒", ROW_LINES, StatusSentences.failure(latest) + " · " + PanelWords.duration(age) + " 前",
                PanelColor.PROBLEM));
    }

    // LLM 的调用接连报错：同一个工具连着两次以上，说清最近一次错在哪；下一次成功就不再提醒。
    private static void callErrors(Moment moment, List<Row> rows) {
        List<StatusSnapshot.ToolCall> calls = moment.status().calls();
        StatusSnapshot.ToolCall latestError = null;
        for (int i = calls.size() - 1; i >= 0 && latestError == null; i--) {
            if (!calls.get(i).running() && !calls.get(i).succeeded()) latestError = calls.get(i);
        }
        if (latestError == null) return;
        int inARow = StatusSentences.errorsInARow(calls, latestError.tool());
        if (inARow < 2) return;
        rows.add(Row.of("提醒", ROW_LINES, latestError.tool() + " 接连 " + inARow + " 次报错："
                + StatusSentences.error(latestError), PanelColor.PROBLEM));
    }
}
