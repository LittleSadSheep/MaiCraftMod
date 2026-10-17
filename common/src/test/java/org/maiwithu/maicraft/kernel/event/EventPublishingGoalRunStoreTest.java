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
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        assertEquals("第 1 步（maicraft:test）：没拆", events.get(2).message());
    }

    @Test
    void stepsWithTheSameConclusionStillTellWhichStepFinished() throws InterruptedException {
        // 一格一格施工时每一步的结论都是同一句：事件里带上第几步与目的，读的人分得清做完了哪一步。
        TaskEventLog log = new TaskEventLog();
        EventPublishingGoalRunStore store = new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), log);
        GoalRun sequence = new GoalRun(store.nextId(), GOAL);
        store.save(sequence);
        Goal wall = new Goal("maicraft:build", "砌东墙", null, ParamValues.EMPTY, null, null, null);
        for (int index = 0; index < 2; index++) {
            GoalRun step = new GoalRun(store.nextId(), wall, sequence.id(), index);
            store.save(step);
            step.finish(TaskResult.done("按图建好了"), 500 + index);
            store.save(step);
        }

        List<String> finished = events(log).stream()
                .filter(event -> event.kind() == TaskEvent.Kind.STEP_FINISHED).map(TaskEvent::message).toList();
        assertEquals(List.of("第 1 步（maicraft:build，砌东墙）：按图建好了", "第 2 步（maicraft:build，砌东墙）：按图建好了"),
                finished);
    }

    @Test
    void finishingAttachesOneLineAboutTheCharacterAndNothingElseDoes() throws InterruptedException {
        // 目标与 sequence 的一步结束时附上结束那一刻的一行自身摘要；开始、提问这些不附，读不到时照样发事件。
        TaskEventLog log = new TaskEventLog();
        String[] brief = {"在 minecraft:overworld 12, 64, -8；血 18，饱食 15；两手空着"};
        EventPublishingGoalRunStore store = new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), log,
                () -> Optional.ofNullable(brief[0]));
        GoalRun sequence = new GoalRun(store.nextId(), GOAL);
        store.save(sequence);
        GoalRun step = new GoalRun(store.nextId(), GOAL, sequence.id(), 0);
        store.save(step);
        step.finish(TaskResult.done("拆完了"), 300);
        store.save(step);
        brief[0] = null;
        sequence.finish(TaskResult.done("都做完了"), 301);
        store.save(sequence);

        List<TaskEvent> events = events(log);
        assertEquals(List.of(TaskEvent.Kind.STARTED, TaskEvent.Kind.STEP_FINISHED, TaskEvent.Kind.FINISHED),
                events.stream().map(TaskEvent::kind).toList());
        assertNull(events.get(0).selfBrief());
        assertEquals("在 minecraft:overworld 12, 64, -8；血 18，饱食 15；两手空着", events.get(1).selfBrief());
        assertNull(events.get(2).selfBrief(), "读不到角色时不附，事件照发");
        assertEquals("都做完了", events.get(2).message(), "结论句不变，摘要另放一处");
    }

    @Test
    void aGoalWithoutAnyEventForTenMinutesGetsOneStallNotice() throws InterruptedException {
        // 实机 10-16：目标挂在进行中直到进程关闭，对面的任务台账每半小时报一次"无进展"共 25 条，
        // 都是它自己计时的。现在目标十分钟没有任何事件就发一条停滞提醒；同一个停滞不重复，来了新事件重新计时。
        long[] now = {0};
        TaskEventLog log = new TaskEventLog();
        EventPublishingGoalRunStore store = new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), log,
                ReadsSelfBrief.NONE, () -> now[0]);
        GoalRun run = new GoalRun(store.nextId(), GOAL);
        run.start(0);
        store.save(run);
        now[0] = 10L * 60 * 1_000_000_000L + 1;
        store.tick();
        store.tick();
        store.tick();

        List<TaskEvent> events = events(log);
        assertEquals(2, events.size(), "同一个停滞只提醒一次");
        assertEquals(TaskEvent.Kind.STALLED, events.get(1).kind());
        assertEquals(run.id(), events.get(1).goalRunId());
        assertTrue(events.get(1).message().contains("10 分钟"), events.get(1).message());

        // 目标动了（又有事件）→ 重新计时，此后十分钟内不再提醒。
        now[0] += 60_000_000_000L;
        store.save(run);
        now[0] += 60_000_000_000L;
        store.tick();
        assertEquals(2, events(log).size());
        now[0] += 10L * 60 * 1_000_000_000L;
        store.tick();
        assertEquals(3, events(log).size());

        // 结束了：不再有停滞提醒。
        run.finish(TaskResult.done("做完了"), 100);
        store.save(run);
        now[0] += 20L * 60 * 1_000_000_000L;
        store.tick();
        List<TaskEvent> after = events(log);
        assertEquals(4, after.size());
        assertEquals(TaskEvent.Kind.FINISHED, after.get(3).kind());
    }

    @Test
    void aPausedGoalIsNotStalled() throws InterruptedException {
        // 暂停有自己的事件（谁停的、停在哪）；恢复是 LLM 的决定，不按停滞反复催。
        long[] now = {0};
        TaskEventLog log = new TaskEventLog();
        EventPublishingGoalRunStore store = new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), log,
                ReadsSelfBrief.NONE, () -> now[0]);
        GoalRun run = new GoalRun(store.nextId(), GOAL);
        run.start(0);
        store.save(run);
        run.pause();
        store.save(run);
        now[0] += 30L * 60 * 1_000_000_000L;
        store.tick();
        assertEquals(List.of(TaskEvent.Kind.STARTED, TaskEvent.Kind.PAUSED),
                events(log).stream().map(TaskEvent::kind).toList());
    }
}
