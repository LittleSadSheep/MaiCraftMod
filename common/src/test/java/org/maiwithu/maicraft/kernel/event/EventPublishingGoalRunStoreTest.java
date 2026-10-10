// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 目标运行的处境变化变成任务事件；sequence 的步骤记在整个 sequence 的编号上。 */
class EventPublishingGoalRunStoreTest {

    private static final Goal GOAL = Goal.of("maicraft:test", null, ParamValues.EMPTY);
    private static final Question QUESTION = new Question(Question.Reason.NEED_APPROVAL, "要拆这面墙吗？",
            List.of(new Question.Option("yes", "拆"), new Question.Option("no", "不拆")));

    private static List<TaskEvent> events(TaskEventLog log) throws InterruptedException {
        return log.read(log.streamId(), 0, OptionalLong.empty(), 100, 0).events();
    }

    @Test
    void stateChangesOfATopLevelGoalBecomeEvents() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        EventPublishingGoalRunStore store = new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), log);
        GoalRun run = new GoalRun(store.nextId(), GOAL);

        store.save(run);
        run.ask(QUESTION);
        store.save(run);
        run.answer("yes");
        store.save(run);
        run.pause();
        store.save(run);
        run.resume();
        store.save(run);
        run.finish(TaskResult.done("拆完了"), 300);
        store.save(run);

        List<TaskEvent> events = events(log);
        assertEquals(List.of(TaskEvent.Kind.STARTED, TaskEvent.Kind.ASKED, TaskEvent.Kind.PAUSED,
                TaskEvent.Kind.RESUMED, TaskEvent.Kind.FINISHED), events.stream().map(TaskEvent::kind).toList());
        assertEquals("要拆这面墙吗？", events.get(1).message());
        assertEquals(TaskResult.Status.DONE, events.get(4).status());
        assertNull(events.get(0).status());
    }

    @Test
    void savingWithoutAChangeDoesNotRepeatTheEvent() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        EventPublishingGoalRunStore store = new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), log);
        GoalRun run = new GoalRun(store.nextId(), GOAL);

        store.save(run);
        store.save(run);

        assertEquals(1, events(log).size());
    }

    @Test
    void stepsOfASequenceReportUnderTheSequenceItself() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        EventPublishingGoalRunStore store = new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), log);
        GoalRun sequence = new GoalRun(store.nextId(), GOAL);
        store.save(sequence);
        GoalRun step = new GoalRun(store.nextId(), GOAL, sequence.id(), 0);

        store.save(step);
        step.ask(QUESTION);
        store.save(step);
        step.answer("no");
        step.finish(TaskResult.failed("没拆", Problem.of(Problem.Kind.NEED_APPROVAL, "不许拆")), 400);
        store.save(step);

        List<TaskEvent> events = events(log);
        assertEquals(List.of(TaskEvent.Kind.STARTED, TaskEvent.Kind.ASKED, TaskEvent.Kind.STEP_FINISHED),
                events.stream().map(TaskEvent::kind).toList());
        assertEquals(sequence.id(), events.get(1).goalRunId());
        assertEquals(sequence.id(), events.get(2).goalRunId());
        assertEquals(TaskResult.Status.FAILED, events.get(2).status());
    }
}
