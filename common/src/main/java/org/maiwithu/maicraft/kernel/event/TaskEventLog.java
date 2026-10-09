// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 任务事件流：目标处境的变化与生存需求的动静，按游标往后读，没有新事件时可以等一会儿。
 *
 * <p>换世界或重启时换一条新流；只保留最近 {@value #CAPACITY} 条，读的一方落后太多时会被告知。
 * 游标、等待与重新同步的规则都在 {@link CursorLog}。目标的完整情况始终可以按编号查目标运行，不靠事件。
 *
 * <p>它同时是任务事件的出口：生存需求与临时任务不认识目标编号，从这里发的事件
 * 都记在"与目标无关"（编号 -1）上，宿主在 events 里照样看得到。
 */
public final class TaskEventLog implements TaskEventSink {
    static final int CAPACITY = 256;
    /** 与目标无关的事件（生存需求、临时任务）在记录上写的编号。 */
    private static final long NO_GOAL = -1;

    private final CursorLog<TaskEvent> log = new CursorLog<>(CAPACITY, TaskEvent::cursor);

    /** 生存需求与临时任务发的任务事件：不挂在任何目标上，只把发生了什么告诉宿主。 */
    @Override public void publish(TaskEvent.Kind kind, String message) {
        append(kind, NO_GOAL, message, null);
    }

    /** 记一条事件并叫醒正在等的读者；返回带上序号的事件。 */
    public TaskEvent append(TaskEvent.Kind kind, long goalRunId, String message, TaskResult.Status status) {
        return log.append(cursor -> new TaskEvent(cursor, kind, goalRunId, message, status));
    }

    /** 换世界或重启：清空事件、换一个新的流编号，叫醒正在等的读者让它们重新对齐。 */
    public void restart() {
        log.restart();
    }

    /** 当前的流编号。 */
    public String streamId() {
        return log.streamId();
    }

    /**
     * 读事件；没有可读的事件时最多等 {@code waitMillis} 毫秒，期间来了事件或流换了就立即返回。
     *
     * @param expectedStream 读的一方上次拿到的流编号；第一次读时为 null
     * @param afterCursor    上次读到的游标；第一次读时为 0
     * @param goalRunId      只看这个目标的事件；-1 表示全部
     * @param limit          这次最多返回几条，至少 1
     * @param waitMillis     没有新事件时最多等多久；0 表示不等
     */
    public CursorLog.Page<TaskEvent> read(String expectedStream, long afterCursor, long goalRunId, int limit,
                                          long waitMillis) throws InterruptedException {
        return log.read(expectedStream, afterCursor,
                event -> goalRunId < 0 || event.goalRunId() == goalRunId, limit, waitMillis);
    }
}
