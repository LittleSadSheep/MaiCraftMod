// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.goal.GoalRunStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会发任务事件的目标运行存储：目标推进每次处境变化都会存盘，这里先交给真正的存储，
 * 再和这条记录上次的处境比较，把变化发成任务事件。目标推进器因此不用在每个分支里记得发事件。
 *
 * <p>sequence 的每一步都有自己的目标运行记录，但 LLM 只认识自己下达的那个编号：
 * 步骤的提问、暂停、结束都记在整个 sequence 的编号上，步骤开始不单独发事件；步骤结束的消息带上第几步与能力。
 *
 * <p>只在客户端线程使用。
 */
public final class EventPublishingGoalRunStore implements GoalRunStore {
    private final GoalRunStore delegate;
    private final TaskEventLog log;
    /** 各条还没结束的记录上次存盘时的处境；结束后移除，不会越积越多。 */
    private final Map<Long, GoalRunState> lastStates = new HashMap<>();

    public EventPublishingGoalRunStore(GoalRunStore delegate, TaskEventLog log) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override public long nextId() {
        return delegate.nextId();
    }

    @Override public void save(GoalRun run) {
        delegate.save(run);
        GoalRunState before = run.state() == GoalRunState.FINISHED
                ? lastStates.remove(run.id())
                : lastStates.put(run.id(), run.state());
        if (before != run.state()) {
            publish(run, before);
        }
    }

    @Override public List<GoalRun> unfinished() {
        return delegate.unfinished();
    }

    /** 处境变了：按"从哪来、到哪去"发一条事件；只是回答了问题、回到推进，不另发事件。 */
    private void publish(GoalRun run, GoalRunState before) {
        boolean step = run.parentRunId() != GoalRun.NO_PARENT;
        long id = step ? run.parentRunId() : run.id();
        switch (run.state()) {
            case RUNNING -> {
                if (before == null && !step) {
                    log.append(TaskEvent.Kind.STARTED, id, "开始：" + describe(run), null);
                } else if (before == GoalRunState.PAUSED) {
                    log.append(TaskEvent.Kind.RESUMED, id, "接着推进：" + describe(run), null);
                }
            }
            case AWAITING_ANSWER -> {
                String question = run.question().text();
                if (before == GoalRunState.PAUSED) {
                    log.append(TaskEvent.Kind.RESUMED, id, "解除暂停，仍在等回答：" + question, null);
                } else {
                    log.append(TaskEvent.Kind.ASKED, id, question, null);
                }
            }
            // 被临时任务带离原地停下的，带上为什么停、停在哪：LLM 不必猜是不是游戏重连了。
            case PAUSED -> log.append(TaskEvent.Kind.PAUSED, id, "暂停：" + describe(run) + run.pauseNote("；"), null);
            // sequence 的一步做完记在整件 sequence 的编号上，结论句前带上第几步、哪个能力：连着几步结论一样
            // （一格一格施工每步都是"按图建好了"）时，读事件的人也分得清做完的是哪一步。
            case FINISHED -> log.append(step ? TaskEvent.Kind.STEP_FINISHED : TaskEvent.Kind.FINISHED, id,
                    step ? run.goal().stepLabel(run.stepOfParent()) + "：" + run.result().summary() : run.result().summary(),
                    run.result().status());
        }
    }

    private static String describe(GoalRun run) {
        String purpose = run.goal().purpose();
        return purpose == null || purpose.isBlank()
                ? run.goal().ability()
                : run.goal().ability() + "（" + purpose + "）";
    }
}
