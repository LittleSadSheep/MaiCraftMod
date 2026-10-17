// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskProgress;

/**
 * 详细档的"此刻"页：连接、目标、任务、生存需求、结果、最近发生的六段，段与段之间画一条分隔线。
 * 正常时不说话的行（服务端握好了手、没有生存需求在等）整行不出现。
 */
final class FullRows {
    /** 详细档可能出现的全部标签；时间线的时刻和它们一样宽。标签栏按最宽的定，免得正文左右跳。 */
    static final List<String> LABELS = List.of("MCP", "服务端", "控制权", "性能", "目标", "提问", "许可",
            "任务", "阶段", "进展", "生存需求", "上一个", "失败", "00:00:00");
    /** 详细档里一行内容最多折三行。 */
    static final int ROW_LINES = 3;
    /** 阶段一行最多列几个阶段名。 */
    static final int PHASES_SHOWN = 6;

    private FullRows() {}

    static List<Row> rows(Moment moment) {
        List<Row> rows = new ArrayList<>(connection(moment));
        rows.add(Row.dividerLine());
        rows.addAll(goal(moment));
        rows.add(Row.dividerLine());
        rows.addAll(tasks(moment));
        List<Row> needs = survivalNeeds(moment);
        if (!needs.isEmpty()) {
            rows.add(Row.dividerLine());
            rows.addAll(needs);
        }
        List<Row> results = results(moment);
        if (!results.isEmpty()) {
            rows.add(Row.dividerLine());
            rows.addAll(results);
        }
        List<Row> timeline = Timeline.rows(moment);
        if (!timeline.isEmpty()) {
            rows.add(Row.dividerLine());
            rows.addAll(timeline);
        }
        return rows;
    }

    private static List<Row> connection(Moment moment) {
        StatusSnapshot.Connection connection = moment.status().connection();
        List<Row> rows = new ArrayList<>();
        if (connection.mcpListening()) {
            rows.add(Row.of("MCP", ROW_LINES, "127.0.0.1:" + connection.mcpPort() + " · 宿主 " + connection.hosts() + " 个"
                    + waitingCall(moment), connection.hosts() > 0 ? PanelColor.GOOD : PanelColor.PROBLEM));
        } else {
            rows.add(Row.of("MCP", ROW_LINES, "没在监听" + (connection.mcpStartFailure() == null ? ""
                    : "：" + connection.mcpStartFailure()), PanelColor.PROBLEM));
        }
        StatusSentences.serverLink(connection).ifPresent(piece -> rows.add(Row.of("服务端", ROW_LINES, List.of(piece))));
        rows.add(control(connection.control()));
        rows.add(connection.performance() == null
                ? Row.of("性能", 1, "外部服务端，测不了", PanelColor.MINOR)
                : Row.of("性能", 1, List.of(StatusSentences.performance(connection.performance()))));
        return rows;
    }

    // 正挂着的调用：宿主挂着 events 等新事件时说"宿主正在等事件"，这是宿主还在听的证据。
    private static String waitingCall(Moment moment) {
        for (StatusSnapshot.ToolCall call : moment.status().calls()) {
            if (!call.running()) continue;
            String waited = PanelWords.duration(moment.nowMillis() - call.startedAtMillis());
            // events 是读任务事件的 MCP 工具：它挂着就是宿主在等角色这边的消息。
            return "events".equals(call.tool()) ? " · 宿主正在等事件 " + waited : " · " + call.tool() + " 处理中 " + waited;
        }
        return "";
    }

    private static Row control(StatusSnapshot.Control control) {
        if (!control.inWorld()) return Row.of("控制权", 1, "不在世界里", PanelColor.MINOR);
        if (control.automationOwns()) return Row.of("控制权", 1, "自动化", PanelColor.GOOD);
        if (control.humanTookOver()) return Row.of("控制权", 1, "玩家（按过 F8），再按 F8 交回", PanelColor.WAITING);
        if (control.automationRequested()) {
            return Row.of("控制权", ROW_LINES, "等交接" + (control.unavailableReason() == null ? ""
                    : "：" + control.unavailableReason()), PanelColor.WAITING);
        }
        return Row.of("控制权", 1, "玩家（自动化没要控制权）", PanelColor.MINOR);
    }

    private static List<Row> goal(Moment moment) {
        StatusSnapshot.Goals goals = moment.status().goals();
        StatusSnapshot.GoalLine goal = goals.main();
        List<Row> rows = new ArrayList<>();
        if (goal == null) {
            rows.add(Row.of("目标", 1, "空闲，等 LLM 下达目标", PanelColor.MINOR));
        } else {
            rows.add(Row.of("目标", 1, StatusSentences.goalHead(moment, goal, true)));
            String purpose = StatusSentences.purpose(goal);
            if (!purpose.isBlank()) rows.add(Row.of("", ROW_LINES, purpose, PanelColor.TEXT));
            if (goals.question() != null) {
                rows.addAll(StatusSentences.question(moment, "提问", goals.question(), FirstSeenTimes.goalState(goal)));
            }
            if (!goals.permissionChanges().isEmpty()) {
                rows.add(Row.of("许可", ROW_LINES, "和默认不同：" + String.join("、", goals.permissionChanges()),
                        PanelColor.MINOR));
            }
        }
        if (goals.deathQuestion() != null) {
            rows.addAll(StatusSentences.question(moment, "提问", goals.deathQuestion(), FirstSeenTimes.DEATH));
        }
        return rows;
    }

    // 任务段：运行栈从上往下画，第一行是角色此刻在听的任务，下面缩进写被它压着的；再写它的阶段与进展。
    private static List<Row> tasks(Moment moment) {
        StatusSnapshot.Loop loop = moment.loop();
        String instead = switch (loop.state()) {
            case NOT_IN_WORLD -> "不在世界里";
            case NOT_AUTOMATED -> "角色不在自动化手上，控制循环没在推进";
            case IDLE -> "手上没有任务";
            case WAITING_RESPAWN -> "角色死了，等重生；主任务停在原地，重生后接着做";
            case WORKING, PARKED -> null;
        };
        List<StatusSnapshot.Layer> layers = loop.layers();
        if (instead != null || layers.isEmpty()) {
            boolean dead = loop.state() == StatusSnapshot.LoopState.WAITING_RESPAWN;
            return List.of(Row.of("任务", ROW_LINES, instead == null ? "手上没有任务" : instead,
                    dead ? PanelColor.PROBLEM : PanelColor.MINOR));
        }
        List<Row> rows = new ArrayList<>();
        TaskProgress progress = moment.progress();
        for (int i = layers.size() - 1; i >= 0; i--) {
            StatusSnapshot.Layer layer = layers.get(i);
            boolean top = i == layers.size() - 1;
            String doing = top && progress != null ? progress.doing() : layer.doing();
            String sentence = StatusSentences.layer(layer, doing, loop.parkedWhere(), top);
            if (top) {
                rows.add(Row.of("任务", ROW_LINES, sentence, layer.needName() == null && !layer.parked()
                        ? PanelColor.DOING : PanelColor.WAITING));
            } else {
                rows.add(Row.of("", ROW_LINES, "└ " + sentence, PanelColor.MINOR));
            }
        }
        if (progress != null) {
            rows.add(Row.of("阶段", ROW_LINES, phases(progress), PanelColor.MINOR));
            rows.add(Row.of("进展", ROW_LINES, progressSentence(progress),
                    StatusSentences.nearlyStuck(progress) ? PanelColor.WAITING : PanelColor.DOING));
        }
        return rows;
    }

    // 阶段：最近走过的几个阶段，最后一个是现在的；在两个阶段之间来回跳一眼就看得出来。
    private static String phases(TaskProgress progress) {
        List<String> names = new ArrayList<>();
        if (!progress.recentPhases().isEmpty()) names.add(progress.recentPhases().getFirst().from());
        for (TaskProgress.PhaseChange change : progress.recentPhases()) names.add(change.to());
        if (names.isEmpty()) names.add(progress.phase());
        List<String> shown = names.subList(Math.max(0, names.size() - PHASES_SHOWN), names.size());
        return String.join(" → ", shown) + "（现在）";
    }

    // 进展：上一次真实进展是什么、多久前；无进展时限多长（到了就判卡住）；快到最多能做的时长时再提一句。
    private static String progressSentence(TaskProgress progress) {
        StringBuilder text = new StringBuilder();
        boolean noneYet = progress.ticksSinceProgress() == progress.activeTicks() && "开始".equals(progress.lastProgress());
        if (noneYet) {
            text.append("开始 ").append(PanelWords.ticks(progress.activeTicks())).append(" 了，还没有真实进展");
        } else {
            text.append(PanelWords.ticks(progress.ticksSinceProgress())).append(" 前：").append(progress.lastProgress());
        }
        if (progress.stuckAfterTicks() < Long.MAX_VALUE) {
            text.append(" · ").append(PanelWords.ticks(progress.stuckAfterTicks())).append(" 无进展时限");
        }
        if (progress.maxTicks() < Long.MAX_VALUE && progress.activeTicks() * 2 >= progress.maxTicks()) {
            text.append(" · 最多做 ").append(PanelWords.ticks(progress.maxTicks()))
                    .append("，已做 ").append(PanelWords.ticks(progress.activeTicks()));
        }
        return text.toString();
    }

    // 生存需求段：只在有需求想插没插、或上次失败正等着再试时出现，每个需求一句整话。
    private static List<Row> survivalNeeds(Moment moment) {
        List<String> sentences = new ArrayList<>();
        StatusSnapshot.HeldBack held = moment.loop().heldBack();
        if (held != null && !StatusSentences.alsoWaitingToRetry(moment, held)) sentences.add(StatusSentences.heldBack(held));
        for (StatusSnapshot.RetryWait wait : moment.loop().retryWaits()) sentences.add(StatusSentences.retryWait(wait));
        List<Row> rows = new ArrayList<>();
        for (String sentence : sentences) {
            rows.add(Row.of(rows.isEmpty() ? "生存需求" : "", ROW_LINES, sentence, PanelColor.WAITING));
        }
        return rows;
    }

    // 结果段：上一个结束的目标和最近一次失败；两者是同一个目标时只写失败那一行。
    private static List<Row> results(Moment moment) {
        List<StatusSnapshot.GoalLine> finished = moment.status().goals().recent().stream()
                .filter(goal -> goal.id() >= 0 && goal.state() == GoalRunState.FINISHED && goal.result() != null)
                .toList();
        if (finished.isEmpty()) return List.of();
        List<Row> rows = new ArrayList<>();
        StatusSnapshot.GoalLine last = finished.getFirst();
        StatusSnapshot.GoalLine failed = finished.stream()
                .filter(goal -> goal.result().status() == TaskResult.Status.FAILED).findFirst().orElse(null);
        if (failed != last) {
            rows.add(Row.of("上一个", ROW_LINES, "#" + last.id() + " " + PanelWords.ability(last.ability()) + " "
                    + PanelWords.resultStatus(last.result().status()) + "：" + last.result().summary() + " · "
                    + PanelWords.duration(moment.ageMillis(FirstSeenTimes.goalState(last))) + " 前", resultColor(last)));
        }
        if (failed != null) rows.add(failureRow(moment, failed, finished));
        return rows;
    }

    private static Row failureRow(Moment moment, StatusSnapshot.GoalLine failed, List<StatusSnapshot.GoalLine> finished) {
        Problem problem = failed.result().problem();
        StringBuilder text = new StringBuilder("#" + failed.id() + " " + PanelWords.ability(failed.ability()) + "："
                + StatusSentences.why(failed.result()));
        if (problem != null && problem.suggestion() != null && !problem.suggestion().isBlank()) {
            text.append("；").append(problem.suggestion());
        }
        text.append(" · ").append(PanelWords.duration(moment.ageMillis(FirstSeenTimes.goalState(failed)))).append(" 前");
        int repeats = sameFailureInARow(failed, finished);
        if (repeats > 1) text.append(" · 连着 ").append(repeats).append(" 个目标都这样");
        return Row.of("失败", ROW_LINES, text.toString(), PanelColor.PROBLEM);
    }

    // 从这次失败往前数，连着几个目标是同一个能力、同一种问题、同一句话失败的：LLM 在原地重试时一眼看得出。
    private static int sameFailureInARow(StatusSnapshot.GoalLine failed, List<StatusSnapshot.GoalLine> finished) {
        int count = 0;
        for (StatusSnapshot.GoalLine goal : finished.subList(finished.indexOf(failed), finished.size())) {
            Problem a = goal.result().problem();
            Problem b = failed.result().problem();
            boolean same = goal.ability().equals(failed.ability()) && goal.result().status() == TaskResult.Status.FAILED
                    && a != null && b != null && a.kind() == b.kind() && a.message().equals(b.message());
            if (!same) break;
            count++;
        }
        return count;
    }

    static PanelColor resultColor(StatusSnapshot.GoalLine goal) {
        return switch (goal.result().status()) {
            case DONE -> PanelColor.GOOD;
            case PARTIAL -> PanelColor.WAITING;
            case FAILED -> PanelColor.PROBLEM;
            case CANCELLED -> PanelColor.MINOR;
        };
    }
}
