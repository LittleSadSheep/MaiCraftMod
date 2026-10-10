// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.game.player.DeathFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 死亡恢复决策：角色死后给 LLM 的一个选择——回到世界里（普通世界是重生，极限模式是旁观世界），
 * 或取消当前目标把死亡屏幕留给人。两种"回去"在原版是死亡界面上同一个按钮发的同一个请求，
 * 服务器按世界规则结算：普通世界复活，极限模式切成旁观；所以只按世界规则给其中一种，不让 LLM 选了旁观却被复活。
 *
 * <p>死亡停摆时挂一次问题（经内核的问题通道，MCP 的 answer 沿目标运行表的回答管道回来）。
 * 同一个死亡过程只挂一次；挂着的决策记在一条内存里的目标运行上，让 LLM 能按编号查到它、回答它。
 * 答复后问题随之消费：执行失败（原生请求没发出去）时把同一个问题重新挂上，不吞答复；
 * 执行成功或选了取消就了结本轮，迟到的重复答复不会再落在消费掉的问题上。
 * 回到活体（重生或人以别的方式救回来）或离开世界时，整份决策丢掉，下次死亡从头再来。
 *
 * <p>决策编号是负数（-2 起往回数），和下达的目标运行编号不在一个数域里，一眼分得清；
 * -1 留给事件流里"与目标无关"的事件，不和它撞，按编号读事件时只读得到这条决策的。
 * 只在客户端线程使用。
 */
public final class DeathRecovery {
    /** 决策在 MCP 里显示的名字：不是能力 ID，内核不认识任何能力，这里只是决策种类在任务列表里的叫法。 */
    public static final String DECISION_NAME = "death_recovery";

    private final TaskEventLog events;
    private GoalRun decision;
    /** 挂着的问题原文：答复会把问题从记录上消费掉，重新挂时要按原文挂回去。 */
    private Question lastQuestion;
    /** 下一条决策的编号：从"与目标无关"的编号再往回一格起，负数往回数，与目标运行的正数编号永不相遇。 */
    private long nextDecisionId = TaskEventLog.NO_GOAL - 1;

    public DeathRecovery(TaskEventLog events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * 角色死了：挂一次死亡恢复决策。已经挂着或本轮已经了结（等 LLM 换人来重生）时不再挂。
     *
     * @param facts           死亡现场的可见事实；这一刻拿不到时为 null，问题文本里就少写一条，按普通世界给选项
     * @param connectionAlive 到服务器的连接还在不在；不在时回不去，只能取消目标
     */
    public void onDeath(DeathFacts facts, boolean connectionAlive) {
        if (decision != null) {
            return;
        }
        Question question = new Question(Question.Reason.CHOOSE_ONE, questionText(facts),
                options(facts != null && facts.hardcore(), connectionAlive));
        decision = new GoalRun(nextDecisionId--, goal(), GoalRun.NO_PARENT, -1);
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

    // 这个编号是不是以前某轮死亡挂过的决策：编号从"与目标无关"的下一格起按顺序往回数，
    // 挂过的正好是这一段连续的负数，用不着另记一份名单。
    private boolean issuedEarlier(long runId) {
        return runId < TaskEventLog.NO_GOAL && runId > nextDecisionId;
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
        /** 取消当前目标，死亡屏幕留给人。 */
        CANCEL_GOAL
    }

    /**
     * LLM 回答了死亡恢复问题：校验编号与选项，消费问题，返回所选做法给目标运行表去执行。
     * 编号不是本轮决策的、或问题已经消费掉（迟到的重复答复），一律如实抛错，不悄悄当没听见。
     */
    public Choice answer(long runId, String optionId) {
        if (!owns(runId)) {
            if (issuedEarlier(runId)) {
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
            case "cancel_goal" -> Choice.CANCEL_GOAL;
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
            case CANCEL_GOAL -> "已取消当前目标，死亡屏幕留给人处理";
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

    // 选项：连接还在才回得去——普通世界给重生，极限模式给旁观世界（死亡界面上就是这样）；取消目标永远提供。
    private static List<Question.Option> options(boolean hardcore, boolean connectionAlive) {
        List<Question.Option> options = new ArrayList<>();
        if (connectionAlive && !hardcore) {
            options.add(new Question.Option("respawn", "发原版重生请求，回出生点或床，目标原地接着做"));
        }
        if (connectionAlive && hardcore) {
            options.add(new Question.Option("spectate", "极限模式不能重生：请求旁观这个世界，成不成由服务器决定"));
        }
        options.add(new Question.Option("cancel_goal", "取消当前目标，死亡屏幕留给人处理"));
        return List.copyOf(options);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    // 决策记录要挂一个目标：这里只借它的名字与说明给查询端看，不会有人推进它。
    private static Goal goal() {
        return new Goal(DECISION_NAME, "死亡恢复：重生、观战或取消目标", null,
                ParamValues.EMPTY, null, List.of(), null);
    }
}
