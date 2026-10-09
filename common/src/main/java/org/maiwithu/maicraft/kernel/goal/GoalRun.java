// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一次目标推进的记录：编号、目标、处境、进行到第几步、问过的问题、得到过的回答与最终结果。
 * 存盘的是它，重启后从它恢复；LLM 查询目标进展时看到的也是它。
 *
 * <p>处境的走法：RUNNING 与 AWAITING_ANSWER 之间来回（提问、回答）；RUNNING 或 AWAITING_ANSWER
 * 可以被暂停（LLM 要求暂停，或重启后恢复出来），恢复后回到暂停前的处境；任何处境都可以结束，
 * 结束后不可再改。违反走法直接抛异常：那说明调用方的代码写错了，不是游戏里发生了什么。
 *
 * <p>只在客户端主线程读写，本类不加锁。
 */
public final class GoalRun {
    /** LLM 直接下达的目标没有所属的目标运行。 */
    public static final long NO_PARENT = -1;

    private final long id;
    private final Goal goal;
    /** 所属的目标运行编号；LLM 直接下达的目标为 {@link #NO_PARENT}。重启后恢复时靠它分辨哪些是步骤自己的记录。 */
    private final long parentRunId;
    /** 是所属 sequence 的第几步（从 0 开始）；LLM 直接下达的目标为 -1。重启后按它找回同一步的记录。 */
    private final int stepOfParent;
    private GoalRunState state = GoalRunState.RUNNING;
    /** 进行到第几步（从 0 开始）；普通目标固定 0，sequence 是它包含的第几个子目标。 */
    private int stepIndex;
    /** 在等回答的问题；没有问题挂着时为 null。暂停时问题照样挂着。 */
    private Question question;
    /** 这次推进里 LLM 已经给过的回答，按先后顺序。 */
    private final List<String> answers = new ArrayList<>();
    /**
     * sequence 已经结束的各步骤的结论，按步骤先后；普通目标为空。随记录存盘：重启后靠它接上前面几步的成败
     * 与已经发生的变化，整件事收尾时不会把重启前做完的步骤算成没做。
     */
    private final List<TaskResult> stepResults = new ArrayList<>();
    /** 结束后的结果；没结束时为 null。 */
    private TaskResult result;
    private long startedTick = -1;
    private long finishedTick = -1;

    /** 编号由目标运行存储分配，这里不持有全局计数器；LLM 直接下达的目标没有所属运行。 */
    public GoalRun(long id, Goal goal) {
        this(id, goal, NO_PARENT, -1);
    }

    /** sequence 的某一步：记录它所属的目标运行和它是第几步。 */
    public GoalRun(long id, Goal goal, long parentRunId, int stepOfParent) {
        this.id = id;
        this.goal = Objects.requireNonNull(goal, "goal");
        this.parentRunId = parentRunId;
        this.stepOfParent = stepOfParent;
    }

    /**
     * 从存盘读回一条还没结束的记录：处境、步骤、挂着的问题和回答原样接上。读回的记录交给目标推进恢复为暂停，
     * 这里不改处境，也不做走法检查——存盘时它就是这个样子。
     */
    public static GoalRun fromSaved(long id, Goal goal, long parentRunId, int stepOfParent, GoalRunState state,
                                    int stepIndex, Question question, List<String> answers,
                                    List<TaskResult> stepResults, long startedTick) {
        if (state == GoalRunState.FINISHED) {
            throw new IllegalArgumentException("目标运行 " + id + " 已经结束，存盘只读回没结束的记录");
        }
        if (state == GoalRunState.AWAITING_ANSWER && question == null) {
            throw new IllegalArgumentException("目标运行 " + id + " 存成等回答，却没有挂着的问题");
        }
        GoalRun run = new GoalRun(id, goal, parentRunId, stepOfParent);
        run.state = Objects.requireNonNull(state, "state");
        run.stepIndex = stepIndex;
        run.question = question;
        run.answers.addAll(answers);
        run.stepResults.addAll(stepResults);
        run.startedTick = startedTick;
        return run;
    }

    /**
     * 开始被推进时调用：只记第一次的开始时刻。重启后恢复的记录照样会被再 start 一次
     * （可能还挂着问题或处于暂停），这不改变它的处境，也不改写原来的开始时刻。
     */
    public void start(long gameTick) {
        if (state == GoalRunState.FINISHED) {
            throw new IllegalStateException("目标运行 " + id + " 已经结束，不能再开始推进");
        }
        if (startedTick < 0) {
            startedTick = gameTick;
        }
    }

    /** 停下来向 LLM 提问：从 RUNNING 进入等回答。 */
    public void ask(Question asked) {
        requireState(GoalRunState.RUNNING, "提问");
        question = Objects.requireNonNull(asked, "asked");
        state = GoalRunState.AWAITING_ANSWER;
    }

    /**
     * LLM 回答了：记下回答，问题撤下。在等回答时回到推进；暂停中的目标也能先收下回答，
     * 处境保持暂停，恢复后直接接着推进，不再等这个回答。
     */
    public void answer(String text) {
        if (question == null || (state != GoalRunState.AWAITING_ANSWER && state != GoalRunState.PAUSED)) {
            throw new IllegalStateException("目标运行 " + id + " 没有在等回答，当前是 " + state);
        }
        if (text == null || text.isBlank()) throw new IllegalArgumentException("回答不能为空");
        answers.add(text);
        question = null;
        if (state == GoalRunState.AWAITING_ANSWER) {
            state = GoalRunState.RUNNING;
        }
    }

    /** 暂停推进：LLM 要求暂停，或重启后恢复出来。等回答的目标也能暂停，问题照样挂着；已结束的不能改。 */
    public void pause() {
        if (state != GoalRunState.RUNNING && state != GoalRunState.AWAITING_ANSWER) {
            throw new IllegalStateException("目标运行 " + id + " 只能在推进中或等回答时暂停，当前是 " + state);
        }
        state = GoalRunState.PAUSED;
    }

    /** 恢复推进：还挂着问题的回到等回答，其余回到推进。 */
    public void resume() {
        requireState(GoalRunState.PAUSED, "恢复");
        state = question != null ? GoalRunState.AWAITING_ANSWER : GoalRunState.RUNNING;
    }

    /** 结束并定下结果；结束只发生一次，之后本记录封存。 */
    public void finish(TaskResult value, long gameTick) {
        if (state == GoalRunState.FINISHED) {
            throw new IllegalStateException("目标运行 " + id + " 已经结束，不能再改结果");
        }
        result = Objects.requireNonNull(value, "value");
        question = null;
        state = GoalRunState.FINISHED;
        finishedTick = gameTick;
    }

    /** sequence 的一步结束了：记下它的结论，随记录存盘。 */
    public void recordStepResult(TaskResult stepResult) {
        if (state == GoalRunState.FINISHED) {
            throw new IllegalStateException("目标运行 " + id + " 已经结束，不能再记步骤结论");
        }
        stepResults.add(Objects.requireNonNull(stepResult, "stepResult"));
    }

    /** 推进到另一个步骤（sequence 用）。 */
    public void advanceToStep(int index) {
        if (state != GoalRunState.RUNNING) {
            throw new IllegalStateException("目标运行 " + id + " 只有在推进中才能换步骤，当前是 " + state);
        }
        stepIndex = index;
    }

    public long id() {
        return id;
    }

    public Goal goal() {
        return goal;
    }

    /** 所属的目标运行编号；LLM 直接下达的目标为 {@link #NO_PARENT}。 */
    public long parentRunId() {
        return parentRunId;
    }

    /** 是所属 sequence 的第几步；LLM 直接下达的目标为 -1。 */
    public int stepOfParent() {
        return stepOfParent;
    }

    public GoalRunState state() {
        return state;
    }

    public int stepIndex() {
        return stepIndex;
    }

    /** 挂着的问题；没有问题挂着时为 null。 */
    public Question question() {
        return question;
    }

    /** LLM 已经给过的回答，按先后顺序。 */
    public List<String> answers() {
        return Collections.unmodifiableList(answers);
    }

    /** sequence 已经结束的各步骤的结论，按步骤先后；普通目标为空。 */
    public List<TaskResult> stepResults() {
        return Collections.unmodifiableList(stepResults);
    }

    /** 结束后的结果；还没结束时为 null。 */
    public TaskResult result() {
        return result;
    }

    /** 第一次开始时的游戏刻；还没开始时为 -1。 */
    public long startedTick() {
        return startedTick;
    }

    /** 结束时的游戏刻；还没结束时为 -1。 */
    public long finishedTick() {
        return finishedTick;
    }

    /** 还没结束时为 true；重启后要恢复的就是这些。 */
    public boolean unfinished() {
        return state != GoalRunState.FINISHED;
    }

    private void requireState(GoalRunState expected, String doing) {
        if (state != expected) {
            throw new IllegalStateException("目标运行 " + id + " 只能在 " + expected + " 时" + doing + "，当前是 " + state);
        }
    }
}
