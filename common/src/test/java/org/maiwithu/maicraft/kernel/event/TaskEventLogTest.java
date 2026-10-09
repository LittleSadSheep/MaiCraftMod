// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.event.CursorLog.CursorStatus;
import org.maiwithu.maicraft.kernel.event.CursorLog.Page;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务事件流：游标续读、按目标过滤、分页、换流、落后太多、等新事件。 */
class TaskEventLogTest {

    private static void append(TaskEventLog log, long goalRunId, int times) {
        for (int i = 0; i < times; i++) {
            log.append(TaskEvent.Kind.STARTED, goalRunId, "事件", null);
        }
    }

    @Test
    void publishedSurvivalEventsCarryNoGoalId() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();

        log.publish(TaskEvent.Kind.TEMPORARY_TASK_STARTED, "被威胁，插入自卫");

        Page<TaskEvent> page = log.read(null, 0, -1, 10, 0);
        assertEquals(1, page.events().size());
        TaskEvent event = page.events().get(0);
        assertEquals(TaskEvent.Kind.TEMPORARY_TASK_STARTED, event.kind());
        assertEquals(-1, event.goalRunId());
        assertEquals("被威胁，插入自卫", event.message());
    }

    @Test
    void firstReadWithoutProgressGivesOnlyTheLatestEvents() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        append(log, 1, 5);

        Page<TaskEvent> page = log.read(null, 0, -1, 3, 0);

        assertEquals(3, page.events().size());
        assertEquals(3, page.events().get(0).cursor());
        assertEquals(5, page.cursor());
        assertFalse(page.hasMore());
        assertEquals(CursorStatus.VALID, page.cursorStatus());
    }

    @Test
    void readingWithProgressPagesThroughEverythingAfterTheCursor() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        append(log, 1, 5);

        Page<TaskEvent> first = log.read(log.streamId(), 1, -1, 2, 0);
        Page<TaskEvent> second = log.read(first.streamId(), first.cursor(), -1, 2, 0);

        assertEquals(2, first.events().get(0).cursor());
        assertTrue(first.hasMore());
        assertEquals(3, first.cursor());
        assertEquals(4, second.events().get(0).cursor());
        assertFalse(second.hasMore());
        assertEquals(5, second.cursor());
    }

    @Test
    void filteringByGoalRunSkipsOtherGoalsButStillMovesTheCursor() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        append(log, 1, 2);
        append(log, 2, 1);
        append(log, 1, 2);

        Page<TaskEvent> page = log.read(log.streamId(), 0, 2, 10, 0);

        assertEquals(1, page.events().size());
        assertEquals(2, page.events().get(0).goalRunId());
        assertEquals(5, page.cursor());
    }

    @Test
    void restartTellsReadersTheStreamChanged() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        append(log, 1, 3);
        String oldStream = log.streamId();
        log.restart();
        append(log, 7, 1);

        Page<TaskEvent> page = log.read(oldStream, 3, -1, 10, 0);

        assertEquals(CursorStatus.STREAM_CHANGED, page.cursorStatus());
        assertNotEquals(oldStream, page.streamId());
        assertEquals(7, page.events().get(0).goalRunId());
    }

    @Test
    void cursorAheadOfTheStreamCountsAsStreamChanged() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        append(log, 1, 2);

        assertEquals(CursorStatus.STREAM_CHANGED, log.read(log.streamId(), 99, -1, 10, 0).cursorStatus());
    }

    @Test
    void fallingTooFarBehindIsReportedInsteadOfSkippedSilently() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        append(log, 1, TaskEventLog.CAPACITY + 40);

        Page<TaskEvent> page = log.read(log.streamId(), 10, -1, 5, 0);

        assertEquals(CursorStatus.HISTORY_LOST, page.cursorStatus());
        assertEquals(41, page.events().get(0).cursor());
    }

    @Test
    void waitingReturnsAsSoonAsAnEventArrives() throws Exception {
        TaskEventLog log = new TaskEventLog();
        String stream = log.streamId();
        CompletableFuture<Page<TaskEvent>> reading = CompletableFuture.supplyAsync(() -> {
            try {
                return log.read(stream, 0, -1, 10, 10_000);
            } catch (InterruptedException exception) {
                throw new IllegalStateException(exception);
            }
        });
        Thread.sleep(50);
        log.append(TaskEvent.Kind.FINISHED, 3, "做完了", null);

        Page<TaskEvent> page = reading.get(5, TimeUnit.SECONDS);

        assertEquals(1, page.events().size());
        assertEquals(TaskEvent.Kind.FINISHED, page.events().get(0).kind());
    }

    @Test
    void waitingWithNothingNewReturnsAnEmptyPageAfterTheTimeout() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        append(log, 1, 2);

        Page<TaskEvent> page = log.read(log.streamId(), 2, -1, 10, 30);

        assertTrue(page.events().isEmpty());
        assertEquals(CursorStatus.VALID, page.cursorStatus());
        assertEquals(2, page.cursor());
    }
}
