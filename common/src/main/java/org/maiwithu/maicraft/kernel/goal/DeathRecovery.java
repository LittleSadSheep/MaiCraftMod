// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.game.player.DeathFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.param.Params;
import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 死亡恢复决策：角色死后给 LLM 的一个选择——请求原版重生、请求切观战，或取消任务把死亡屏幕留给人。
 *
 * <p>死亡停摆时挂一次问题（经内核的问题通道，MCP 的 answer 沿目标运行表的回答管道回来）。
 * 同一个死亡过程只挂一次；挂着的决策记在一条内存里的目标运行上，让 LLM 能按编号查到它、回答它。
 * 答复后问题随之消费：执行失败（原生请求没发出去）时把同一个问题重新挂上，不吞答复；
 * 执行成功或选了取消就了结本轮，迟到的重复答复不会再落在消费掉的问题上。
 * 回到活体（重生或人以别的方式救回来）或离开世界时，整份决策丢掉，下次死亡从头再来。
 *
 * <p>决策编号是负数（-1 起往回数），和下达的目标运行编号不在一个数域里，一眼分得清。
 * 只在客户端线程使用。
 */
public final class DeathRecovery {
    /** 决策在 MCP 里显示的名字：不是能力 ID，内核不认识任何能力，这里只是决策种类在任务列表里的叫法。 */
    public static final String DECISION_NAME = "death_recovery";

    /** 留着最近多少轮的决策编号供迟到答复辨认；死亡不频繁，十六轮足够。 */
    private static final int KEPT_DECISION_IDS = 16;

    private final TaskEventLog events;
    private GoalRun decision;
    /** 挂着的问题原文：答复会把问题从记录上消费掉，重新挂时要按原文挂回去。 */
    private Question lastQuestion;
    /** 下一条决策的编号：负数往回数，与目标运行的正数编号永不相遇。 */
    private long nextDecisionId = -1;
    /** 最近挂过的决策编号：决策了结后迟到的答复据此得到"本轮已了结"的回话，而不是被当成编号不存在。 */
    private final Set<Long> issuedDecisionIds = new LinkedHashSet<>();

    public DeathRecovery(TaskEventLog events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * 角色死了：挂一次死亡恢复决策。已经挂着或本轮已经了结（等 LLM 换人来重生）时不再挂。
     *
     * @param facts           死亡现场的可见事实；这一刻拿不到时为 null，问题文本里就少写一条
     * @param connectionAlive 到服务器的连接还在不在；不在时不提供切观战
     */
    public void onDeath(DeathFacts facts, boolean connectionAlive) {
        if (decision != null) {
            return;
        }
        Question question = new Question(Question.Reason.CHOOSE_ONE, questionText(facts), options(connectionAlive));
        decision = new GoalRun(nextDecisionId--, goal(), GoalRun.NO_PARENT, -1);
        issuedDecisionIds.add(decision.id());
        while (issuedDecisionIds.size() > KEPT_DECISION_IDS) {
            // 只留最近几轮的编号：死亡不频繁，更早的迟到答复当作编号不存在也说得通。
            issuedDecisionIds.remove(issuedDecisionIds.iterator().next());
        }
        decision.ask(question);
        lastQuestion = question;
        events.append(TaskEvent.Kind.ASKED, decision.id(), question.text(), null);
    }

    /** 死亡过程结束（重生或以别的方式回到活体）：本轮决策丢掉，下次死亡再挂新的。 */
    public void backAlive() {
        decision = null;
    }

    /** 离开世界：整份现场丢弃，决策不跨世界残留。 */
    public void forget() {
        decision = null;
    }

    /** 这个编号是不是本轮死亡决策的记录。 */
    public boolean owns(long runId) {
        return decision != null && decision.id() == runId;
    }

    /** 本轮死亡决策的记录；死亡过程之外为空。 */
    public Optional<GoalRun> decisionRun() {
        return Optional.ofNullable(decision);
    }

    /** 挂着、等 LLM 回答的问题；没有时为 null。 */
    public Question pendingQuestion() {
        return decision == null ? null : decision.question();
    }

    /** 决策此刻在做什么的一句话；给查询端呈现用。 */
    public String describe() {
        if (decision == null) {
            return "没有挂着的死亡恢复决策";
        }
        return decision.question() != null
                ? "死亡恢复在等回答：" + decision.question().text()
                : "死亡恢复已答复：" + decision.result().summary();
    }

    /** 可以选的做法。 */
    public enum Choice {
        /** 请求原版重生。 */
        RESPAWN,
        /** 请求切观战。 */
        SPECTATE,
        /** 取消当前任务，死亡屏幕留给人。 */
        CANCEL_TASK
    }

    /**
     * LLM 回答了死亡恢复问题：校验编号与选项，消费问题，返回所选做法给目标运行表去执行。
     * 编号不是本轮决策的、或问题已经消费掉（迟到的重复答复），一律如实抛错，不悄悄当没听见。
     */
    public Choice answer(long runId, String optionId) {
        if (!owns(runId)) {
            if (issuedDecisionIds.contains(runId)) {
                // 编号确实是某轮死亡决策的，但那轮已经了结：迟到的答复如实说不再收，不冒充编号不存在。
                throw new GoalRunTable.WrongGoalRunState("本轮死亡恢复已了结，不再收回答");
            }
            throw new GoalRunTable.UnknownGoalRun(runId);
        }
        Question question = decision.question();
        if (question == null) {
            throw new GoalRunTable.WrongGoalRunState("死亡恢复决策已经答复过了，本轮不会再收回答");
        }
        if (question.options().stream().noneMatch(option -> option.id().equals(optionId))) {
            throw new GoalRunTable.WrongGoalRunState("问题没有编号为 " + optionId + " 的回答，可选："
                    + String.join("、", question.options().stream().map(Question.Option::id).toList()));
        }
        decision.answer(optionId);
        return switch (optionId) {
            case "respawn" -> Choice.RESPAWN;
            case "spectate" -> Choice.SPECTATE;
            case "cancel_task" -> Choice.CANCEL_TASK;
            default -> throw new IllegalStateException("选项已经核对过，不该走到别的故事里");
        };
    }

    /**
     * 回答的执行结果：原生请求发出去了（或选了取消、取消不需要发包）就了结本轮，
     * 在决策记录上定下结果并发事件；原生请求没发出去就把同一个问题重新挂上，答复不吞。
     */
    public void applied(Choice choice, boolean sent) {
        if (decision == null || decision.question() != null) {
            throw new GoalRunTable.WrongGoalRunState("死亡恢复决策不在等执行结果");
        }
        if (!sent) {
            // 发不出去（例如连接刚好断了）：同一个问题按原文重新挂上，LLM 换个选法或再试一次。
            decision.ask(lastQuestion);
            events.append(TaskEvent.Kind.ASKED, decision.id(), lastQuestion.text(), null);
            return;
        }
        String summary = switch (choice) {
            case RESPAWN -> "已发出原版重生请求，等服务器结算，复活后循环接着做";
            case SPECTATE -> "已发出切观战的请求，成不成由服务器决定";
            case CANCEL_TASK -> "已取消当前任务，死亡屏幕留给人处理";
        };
        decision.finish(TaskResult.done(summary), -1);
        events.append(TaskEvent.Kind.DEATH_RECOVERY_APPLIED, decision.id(), summary, TaskResult.Status.DONE);
    }

    // 问题文本：带死亡现场的事实，拿得到什么带什么；死因客户端拿不到，不编。
    private static String questionText(DeathFacts facts) {
        StringBuilder text = new StringBuilder("角色死了。");
        if (facts != null) {
            text.append("分数 ").append(facts.score())
                    .append("，死在 ").append(facts.dimension())
                    .append("（").append(format(facts.x())).append(", ").append(format(facts.y()))
                    .append(", ").append(format(facts.z())).append("）。");
        }
        return text.append("接下来怎么办？").toString();
    }

    // 选项：重生永远提供；切观战只在连接还在时提供；取消任务永远提供。
    private static List<Question.Option> options(boolean connectionAlive) {
        List<Question.Option> options = new ArrayList<>();
        options.add(new Question.Option("respawn", "发原版重生请求，回出生点或床，任务原地接着做"));
        if (connectionAlive) {
            options.add(new Question.Option("spectate", "请求切到旁观模式，成不成由服务器决定"));
        }
        options.add(new Question.Option("cancel_task", "取消当前任务，死亡屏幕留给人处理"));
        return List.copyOf(options);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    // 决策记录要挂一个目标：这里只借它的名字与说明给查询端看，不会有人推进它。
    private static Goal goal() {
        return new Goal(DECISION_NAME, "死亡恢复：重生、观战或取消任务", null,
                Params.EMPTY, null, List.of(), null);
    }
}
