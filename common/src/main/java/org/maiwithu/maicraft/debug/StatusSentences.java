// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.maiwithu.maicraft.game.serverlink.ServerCapabilityState;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskProgress;

/**
 * 现状的说法：同一件事在简要档和详细档里说成同一句话，标签只说"这一行说的是谁"，处境写成整句放在正文里。
 */
final class StatusSentences {
    /** 刚失败、处理不了的生存需求在简要档里提醒多久：一分钟够主播看到，又不会一直挂着旧事。 */
    static final long RECENT_MILLIS = 60_000;
    /** 宿主多久没有任何调用、也没有挂着等事件，就算"没在听"：宿主等事件一次最多挂几十秒就会再来。 */
    static final long HOST_SILENT_MILLIS = 60_000;

    private StatusSentences() {}

    /** 目标第一行：只放不会变长的短字段；详细档再带编号与处境。 */
    static List<PanelLine.Piece> goalHead(Moment moment, StatusSnapshot.GoalLine goal, boolean full) {
        StringBuilder text = new StringBuilder();
        if (full) text.append('#').append(goal.id()).append(' ');
        text.append(PanelWords.ability(goal.ability()));
        if (full) text.append(" · ").append(PanelWords.goalState(goal.state(), goal.result()));
        if (goal.steps() > 0) text.append(" · 第 ").append(goal.step() + 1).append('/').append(goal.steps()).append(" 步");
        if (goal.startedTick() >= 0) {
            long until = goal.finishedTick() >= 0 ? goal.finishedTick() : moment.status().gameTick();
            text.append(" · ").append(PanelWords.ticks(until - goal.startedTick()));
        }
        boolean waiting = goal.state() == GoalRunState.AWAITING_ANSWER || goal.state() == GoalRunState.PAUSED;
        return List.of(new PanelLine.Piece(text.toString(), waiting ? PanelColor.WAITING : PanelColor.DOING));
    }

    /** 这个目标为什么做：LLM 写的 purpose；没写就用目标对象的说法；都没有时为空串。 */
    static String purpose(StatusSnapshot.GoalLine goal) {
        return goal.purpose() != null && !goal.purpose().isBlank() ? goal.purpose() : PanelWords.target(goal.target());
    }

    /** 在等回答：问题一行、选项一行，都完整显示；宿主已经不来读事件时变红并说清楚。 */
    static List<Row> question(Moment moment, String label, Question question, String sinceKey) {
        long silent = hostSilentMillis(moment);
        boolean nobodyListening = silent > HOST_SILENT_MILLIS;
        PanelColor color = nobodyListening ? PanelColor.PROBLEM : PanelColor.WAITING;
        String head = "在等回答：" + question.text() + " · " + PanelWords.duration(moment.ageMillis(sinceKey));
        if (nobodyListening) {
            head += silent == Long.MAX_VALUE ? " · 宿主一直没来读事件" : " · 宿主 " + PanelWords.duration(silent) + " 没来读事件";
        }
        List<String> options = new ArrayList<>();
        for (Question.Option option : question.options()) options.add(option.id() + " " + option.meaning());
        return List.of(Row.of(label, Row.UNLIMITED, head, color),
                Row.of("", Row.UNLIMITED, "选项：" + String.join(" / ", options), color));
    }

    /** 宿主多久没动静：有调用正在处理（例如挂着等事件）就是 0；一次调用都没见过时是 {@link Long#MAX_VALUE}。 */
    static long hostSilentMillis(Moment moment) {
        long latest = Long.MIN_VALUE;
        for (StatusSnapshot.ToolCall call : moment.status().calls()) {
            if (call.running()) return 0;
            latest = Math.max(latest, call.startedAtMillis());
        }
        return latest == Long.MIN_VALUE ? Long.MAX_VALUE : Math.max(0, moment.nowMillis() - latest);
    }

    /** 和服务端 MaiCraft 没握手好时的一句话；握手好了为空，不占地方。 */
    static Optional<PanelLine.Piece> serverLink(StatusSnapshot.Connection connection) {
        StatusSnapshot.ServerLink link = connection.serverLink();
        if (!connection.control().inWorld()) return Optional.empty();
        if (link.state() == ServerCapabilityState.State.READY && link.confirmed()) return Optional.empty();
        String why = link.reason().isBlank() ? "" : "（" + link.reason() + "）";
        if (link.expired()) {
            return Optional.of(new PanelLine.Piece("服务端迟迟没握手，服务器多半没装 MaiCraft" + why, PanelColor.PROBLEM));
        }
        return Optional.of(switch (link.state()) {
            case NEGOTIATING -> new PanelLine.Piece("正在和服务端 MaiCraft 握手" + why, PanelColor.WAITING);
            case READY -> new PanelLine.Piece("服务端 MaiCraft 的握手还没确认" + why, PanelColor.WAITING);
            case DENIED -> new PanelLine.Piece("服务端 MaiCraft 拒绝了连接" + why, PanelColor.PROBLEM);
            case LOST -> new PanelLine.Piece("和服务端 MaiCraft 断开了" + why, PanelColor.PROBLEM);
            case UNCONFIRMED -> new PanelLine.Piece("服务端 MaiCraft 没确认这条连接" + why, PanelColor.PROBLEM);
            case DISCONNECTED -> new PanelLine.Piece("没连上服务端 MaiCraft" + why, PanelColor.PROBLEM);
        });
    }

    /**
     * 运行栈一层说成一句话。最上面那层是角色此刻在听的：主任务直接说在做什么，临时任务以需求名开头说是谁插进来的；
     * 被压着的写成"等着"；主任务停在半路时只说停在哪、等谁。
     */
    static String layer(StatusSnapshot.Layer layer, String doing, String parkedWhere, boolean top) {
        if (layer.needName() == null) {
            if (layer.parked()) return "主任务停在半路（" + (parkedWhere == null ? "位置不明" : parkedWhere) + "），等 LLM 换活";
            return top ? doing : "主任务等着：" + doing;
        }
        // 临时任务自己说的话常以需求名开头（"自卫：……"），前面已经写了是谁插进来的，去掉重复的那一截。
        String own = doing.startsWith(layer.needName() + "：") ? doing.substring(layer.needName().length() + 1) : doing;
        return top ? layer.needName() + "插进来（" + PanelWords.urgency(layer.urgency()) + "）：" + own
                : layer.needName() + "的临时任务等着：" + own;
    }

    /** 想插进来却插不进的生存需求：说清是谁、多急、为什么现在插不进来。 */
    static String heldBack(StatusSnapshot.HeldBack held) {
        String why = switch (held.current()) {
            case UNSAFE_TO_STOP -> "可手上这一下现在停下不安全，等做完再说";
            case WORKING -> "它只在两个动作之间插进来，手上正干着活";
            case BETWEEN_ACTIONS -> "这一刻没轮上，下一刻再看";
            case null -> "这一刻没轮上，下一刻再看";
        };
        return held.needName() + "想插进来（" + PanelWords.urgency(held.urgency()) + "），" + why;
    }

    /** 上次失败、正等着再试的生存需求：为什么失败、还要等多久、连续失败几次了。 */
    static String retryWait(StatusSnapshot.RetryWait wait) {
        return wait.needName() + "上次失败：" + wait.why() + "。" + wait.secondsLeft() + "s 后再试"
                + (wait.failures() > 1 ? "（已连续失败 " + wait.failures() + " 次）" : "");
    }

    /** 被按住的需求如果其实是在等着再试，就只按"等着再试"说一次，不说两遍。 */
    static boolean alsoWaitingToRetry(Moment moment, StatusSnapshot.HeldBack held) {
        return moment.loop().retryWaits().stream().anyMatch(wait -> wait.needName().equals(held.needName()));
    }

    /** 快卡住了：无进展的时长过了无进展时限的一半。不设判卡住时限的任务（等待、跟随）不算。 */
    static boolean nearlyStuck(TaskProgress progress) {
        return progress != null && progress.stuckAfterTicks() < Long.MAX_VALUE
                && progress.ticksSinceProgress() * 2 >= progress.stuckAfterTicks();
    }

    /** 快卡住的提醒：自哪次进展之后多久没动，无进展时限是多少（到了就判卡住）。 */
    static String stalled(TaskProgress progress) {
        return "自「" + progress.lastProgress() + "」之后 " + PanelWords.ticks(progress.ticksSinceProgress())
                + " 无进展（" + PanelWords.ticks(progress.stuckAfterTicks()) + " 无进展时限）";
    }

    /** 一个失败的目标：能力、问题种类、游戏里的说法。 */
    static String failure(StatusSnapshot.GoalLine goal) {
        return PanelWords.ability(goal.ability()) + " 失败：" + why(goal.result());
    }

    /** 为什么失败：问题种类加上用游戏里的话说的原因；没写问题时用结果的一句话结论。 */
    static String why(TaskResult result) {
        Problem problem = result.problem();
        return problem == null ? result.summary() : PanelWords.problemKind(problem.kind()) + "，" + problem.message();
    }

    /** 同一个工具从最近一次往回数连着报错了几次；最近一次成功了就是 0。还没结束的调用不算。 */
    static int errorsInARow(List<StatusSnapshot.ToolCall> calls, String tool) {
        int count = 0;
        for (int i = calls.size() - 1; i >= 0; i--) {
            StatusSnapshot.ToolCall call = calls.get(i);
            if (call.running() || !call.tool().equals(tool)) continue;
            if (call.succeeded()) break;
            count++;
        }
        return count;
    }

    /** 一次报错写成"字段：说明"；不是参数问题时只有说明。 */
    static String error(StatusSnapshot.ToolCall call) {
        String message = call.errorMessage() == null ? call.errorCode() : call.errorMessage();
        return call.errorField() == null ? message : call.errorField() + "：" + message;
    }

    /** 性能：TPS 按预算折算（没超预算就是满速），MSPT 是最近一百刻平均每刻耗时。 */
    static PanelLine.Piece performance(StatusSnapshot.Performance performance) {
        double mspt = performance.msPerTick();
        double target = performance.targetMsPerTick();
        double tps = mspt <= target ? 1000.0 / target : 1000.0 / mspt;
        // 超预算一点（原版延迟图的同一阈值，1.25 倍以内）标黄，明显掉速标红。
        PanelColor color = mspt <= target ? PanelColor.GOOD : mspt <= target * 1.25 ? PanelColor.WAITING : PanelColor.PROBLEM;
        return new PanelLine.Piece(String.format(Locale.ROOT, "TPS %.1f · MSPT %.1fms", tps, mspt), color);
    }

    /** 最近的一条"处理不了的生存需求"事件；一分钟前的就不算了。 */
    static Optional<TaskEvent> recentUnhandledNeed(Moment moment) {
        List<TaskEvent> events = moment.status().events();
        for (int i = events.size() - 1; i >= 0; i--) {
            TaskEvent event = events.get(i);
            if (event.kind() == TaskEvent.Kind.NEED_UNHANDLED) {
                return moment.ageMillis(FirstSeenTimes.event(event)) <= RECENT_MILLIS ? Optional.of(event) : Optional.empty();
            }
        }
        return Optional.empty();
    }
}
