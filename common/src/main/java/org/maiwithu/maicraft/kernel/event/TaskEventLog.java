// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 任务事件流：按发生顺序保留最近的事件，读的一方带着游标往后读，没有新事件时可以等一会儿。
 *
 * <p>流有编号。换世界或重启时清空并换一个新编号，拿着旧编号来读的一方会被告知"流换了"，
 * 不会把上一个世界的事件当成现在的。只保留最近 {@value #CAPACITY} 条：读的一方落后太多、
 * 中间的事件已经被挤掉时，同样会被告知，而不是悄悄跳过。目标的完整情况始终可以按编号查目标运行，不靠事件。
 *
 * <p>写在客户端线程，读在 MCP 的请求线程：所有方法都在本对象的锁上进行，等新事件时在锁上等。
 *
 * <p>它同时是任务事件的出口：生存需求与临时任务不认识目标编号，从这里发的事件
 * 都记在"与目标无关"（编号 -1）上，宿主在 events 里照样看得到。
 */
public final class TaskEventLog implements TaskEventSink {
    static final int CAPACITY = 256;
    /** 与目标无关的事件（生存需求、临时任务）在记录上写的编号。 */
    private static final long NO_GOAL = -1;

    private final Deque<TaskEvent> events = new ArrayDeque<>();
    private String streamId = newStreamId();
    private long latest;

    /** 生存需求与临时任务发的任务事件：不挂在任何目标上，只把发生了什么告诉宿主。 */
    @Override public void publish(TaskEvent.Kind kind, String message) {
        append(kind, NO_GOAL, message, null);
    }

    /** 记一条事件并叫醒正在等的读者；返回带上序号的事件。 */
    public synchronized TaskEvent append(TaskEvent.Kind kind, long goalRunId, String message, TaskResult.Status status) {
        TaskEvent event = new TaskEvent(latest + 1, kind, goalRunId, message, status);
        latest = event.cursor();
        events.addLast(event);
        if (events.size() > CAPACITY) {
            events.removeFirst();
        }
        notifyAll();
        return event;
    }

    /** 换世界或重启：清空事件、换一个新的流编号，叫醒正在等的读者让它们重新对齐。 */
    public synchronized void restart() {
        events.clear();
        latest = 0;
        streamId = newStreamId();
        notifyAll();
    }

    /** 当前的流编号。 */
    public synchronized String streamId() {
        return streamId;
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
    public synchronized Page read(String expectedStream, long afterCursor, long goalRunId, int limit, long waitMillis)
            throws InterruptedException {
        if (afterCursor < 0 || limit < 1 || waitMillis < 0) {
            throw new IllegalArgumentException("游标、条数或等待时间不合法：" + afterCursor + "、" + limit + "、" + waitMillis);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        Page page = page(expectedStream, afterCursor, goalRunId, limit);
        // 只有"游标有效、确实没有新事件"才值得等；流换了或历史丢了要马上告诉读的一方。
        while (page.events().isEmpty() && page.cursorStatus() == CursorStatus.VALID) {
            long left = deadline - System.nanoTime();
            if (left <= 0) break;
            TimeUnit.NANOSECONDS.timedWait(this, left);
            page = page(expectedStream, afterCursor, goalRunId, limit);
        }
        return page;
    }

    private Page page(String expectedStream, long afterCursor, long goalRunId, int limit) {
        // 流编号对不上，或游标比最新的还大：读的一方拿着另一轮的进度，从头读这一轮。
        boolean changed = expectedStream != null && !streamId.equals(expectedStream) || afterCursor > latest;
        boolean first = expectedStream == null && afterCursor == 0;
        long from = changed ? 0 : afterCursor;
        long oldest = events.isEmpty() ? latest + 1 : events.peekFirst().cursor();
        boolean lost = !first && !changed && from < oldest - 1;
        List<TaskEvent> matching = events.stream()
                .filter(event -> event.cursor() > from)
                .filter(event -> goalRunId < 0 || event.goalRunId() == goalRunId)
                .toList();
        // 第一次读、手上没有进度时，只给最近的几条；带着进度读时从进度往后，不跳过中间没读的。
        int start = first ? Math.max(0, matching.size() - limit) : 0;
        int end = Math.min(matching.size(), start + limit);
        List<TaskEvent> page = matching.subList(start, end);
        boolean hasMore = end < matching.size();
        long next = hasMore ? page.get(page.size() - 1).cursor() : latest;
        CursorStatus status = changed ? CursorStatus.STREAM_CHANGED
                : lost ? CursorStatus.HISTORY_LOST
                : CursorStatus.VALID;
        return new Page(streamId, next, List.copyOf(page), hasMore, status);
    }

    private static String newStreamId() {
        return UUID.randomUUID().toString();
    }

    /** 读的一方带来的游标还能不能接着用。 */
    public enum CursorStatus {
        /** 能用，事件从游标之后连续给出。 */
        VALID,
        /** 流换了（换了世界或重启了）：这一页从新流的开头给出，之前的进度作废。 */
        STREAM_CHANGED,
        /** 落后太多，游标之后有事件已经被挤掉、读不回来了：这一页从还留着的最早一条给出。 */
        HISTORY_LOST
    }

    /**
     * 一页事件。
     *
     * @param streamId     当前的流编号，下次读时带上
     * @param cursor       下次读时带上的游标
     * @param events       这一页的事件，按发生顺序
     * @param hasMore      游标之后还有没给完的事件：马上再读一页，不要等
     * @param cursorStatus 读的一方带来的游标还能不能用
     */
    public record Page(String streamId, long cursor, List<TaskEvent> events, boolean hasMore, CursorStatus cursorStatus) {
        public Page {
            Objects.requireNonNull(streamId, "streamId");
            Objects.requireNonNull(cursorStatus, "cursorStatus");
            events = List.copyOf(events);
        }
    }
}
