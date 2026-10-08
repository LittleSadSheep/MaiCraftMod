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
 * <p>状态只能前进：RUNNING → AWAITING_ANSWER → RUNNING 来回，PAUSED → RUNNING 解除，
 * 任何状态 → FINISHED 结束；结束后不可再改。违反顺序直接抛异常：那说明调用方的代码写错了，
 * 不是游戏里发生了什么。
 *
 * <p>只在客户端主线程读写，本类不加锁。
 */
public final class GoalRun {
    private final long id;
    private final Goal goal;
    private GoalRunState state = GoalRunState.RUNNING;
    /** 进行到第几步（从 0 开始）；普通目标固定 0，sequence 是它包含的第几个子目标。 */
    private int stepIndex;
    /** 等回答时的问题；不在等回答时为 null。 */
    private Question question;
    /** 这次推进里 LLM 已经给过的回答，按先后顺序。 */
    private final List<String> answers = new ArrayList<>();
    /** 结束后的结果；没结束时为 null。 */
    private TaskResult result;
    private long startedTick = -1;
    private long finishedTick = -1;

    /** 编号由目标运行存储分配，这里不持有全局计数器。 */
    public GoalRun(long id, Goal goal) {
        this.id = id;
        this.goal = Objects.requireNonNull(goal, "goal");
    }

    /** 第一次被推进时调用：记下开始时刻。 */
    public void start(long gameTick) {
        if (state != GoalRunState.RUNNING) {
            throw new IllegalStateException("目标运行 " + id + " 只能从 RUNNING 开始推进，当前是 " + state);
        }
        startedTick = gameTick;
    }

    /** 停下来向 LLM 提问：从 RUNNING 进入等回答。 */
    public void ask(Question asked) {
        requireState(GoalRunState.RUNNING, "提问");
        question = Objects.requireNonNull(asked, "asked");
        state = GoalRunState.AWAITING_ANSWER;
    }

    /** LLM 回答了：记下回答，回到推进。 */
    public void answer(String text) {
        requireState(GoalRunState.AWAITING_ANSWER, "回答");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("回答不能为空");
        answers.add(text);
        question = null;
        state = GoalRunState.RUNNING;
    }

    /** 暂停推进：重启恢复后的目标运行处于这个状态；已在等回答或已结束时不能改。 */
    public void pause() {
        requireState(GoalRunState.RUNNING, "暂停");
        state = GoalRunState.PAUSED;
    }

    /** 解除暂停，继续推进；只有暂停中的目标运行能解除。 */
    public void unpause() {
        requireState(GoalRunState.PAUSED, "解除暂停");
        state = GoalRunState.RUNNING;
    }

    /** 结束并定下结果；结束只发生一次，之后本记录封存。 */
    public void finish(TaskResult value, long gameTick) {
        if (state == GoalRunState.FINISHED) {
            throw new IllegalStateException("目标运行 " + id + " 已经结束，不能再改结果");
        }
        result = Objects.requireNonNull(value, "value");
        state = GoalRunState.FINISHED;
        finishedTick = gameTick;
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

    public GoalRunState state() {
        return state;
    }

    public int stepIndex() {
        return stepIndex;
    }

    /** 等回答时的问题；不在等回答时为 null。 */
    public Question question() {
        return question;
    }

    /** LLM 已经给过的回答，按先后顺序。 */
    public List<String> answers() {
        return Collections.unmodifiableList(answers);
    }

    /** 结束后的结果；还没结束时为 null。 */
    public TaskResult result() {
        return result;
    }

    /** 开始时的游戏刻；还没开始时为 -1。 */
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
