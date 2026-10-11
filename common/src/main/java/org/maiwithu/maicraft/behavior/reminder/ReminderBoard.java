// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.reminder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/**
 * 提醒板：还成立的提醒放这里，observe(self) 每次都完整交出去；事件只负责把 LLM 叫醒。
 *
 * <p>第一次出现立刻发一条事件；文案来回变（切工具、贴着水面进出）只更新板上的内容，稳定两秒、
 * 离上次通知过了三十秒才再发；一直成立的每十分钟再提一次。条件消失立刻从板上撤下，撤下的通知也等稳定再发，
 * 短暂离开又遇险不会先报"撤下"再连发新提醒；撤下后通知记录仍留着，重新出现不能冒充第一次绕过冷却。
 */
public final class ReminderBoard {

    private static final long PERIODIC_INTERVAL = Duration.ofMinutes(10).toNanos();
    private static final long CHANGE_INTERVAL = Duration.ofSeconds(30).toNanos();
    private static final long SETTLE_INTERVAL = Duration.ofSeconds(2).toNanos();

    private final Map<String, Reminder> active = new LinkedHashMap<>();
    private final Map<String, Notice> notices = new LinkedHashMap<>();
    private final TaskEventSink events;
    private final LongSupplier clock;

    public ReminderBoard(TaskEventSink events) {
        this(events, System::nanoTime);
    }

    /** 单调时钟只安排通知；世界时间仍原样写进提醒，校时或回拨不能绕过同一提醒的冷却。 */
    public ReminderBoard(TaskEventSink events, LongSupplier clock) {
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 这条提醒此刻成立：放上板；第一次出现立刻发事件，之后只在合并通知时按冷却发。 */
    public synchronized void update(String id, String message, Map<String, Object> evidence,
                                    List<String> suggestions, long tick) {
        Reminder previous = active.get(id);
        long first = previous == null || tick < previous.observedAtTick() ? tick : previous.firstObservedTick();
        active.put(id, new Reminder(id, message, first, tick, evidence, suggestions));
        long now = clock.getAsLong();
        Notice notice = notices.get(id);
        if (notice == null) {
            notices.put(id, new Notice(message, now));
            events.publish(TaskEvent.Kind.REMINDER, "提醒：" + message);
        } else {
            notice.observe(message, null, now);
        }
    }

    /** 条件消失：立刻撤下；撤下的通知等合并时稳定了再发。 */
    public synchronized void remove(String id, String reason, long tick) {
        active.remove(id);
        Notice notice = notices.get(id);
        if (notice != null) notice.observe(null, reason, clock.getAsLong());
    }

    /** 本刻观察都做完后合并通知：只发仍然成立的最新状态，不重放已经反转的撤下或文案。 */
    public synchronized void flush() {
        long now = clock.getAsLong();
        for (String id : List.copyOf(notices.keySet())) {
            Notice notice = notices.get(id);
            if (notice == null) continue;
            Reminder value = active.get(id);
            boolean periodic = value != null && now - notice.announcedAt >= PERIODIC_INTERVAL;
            boolean changed = !Objects.equals(notice.announcedMessage, notice.message)
                    && now - notice.changedAt >= SETTLE_INTERVAL && now - notice.announcedAt >= CHANGE_INTERVAL;
            if (!periodic && !changed) continue;
            notice.announcedAt = now;
            notice.announcedMessage = notice.message;
            events.publish(TaskEvent.Kind.REMINDER, value != null ? "提醒：" + value.message()
                    : "提醒撤下：" + notice.reason);
        }
    }

    /** 读板上还成立的提醒；不消费、不延长有效期。 */
    public synchronized List<Reminder> snapshot() {
        return List.copyOf(new ArrayList<>(active.values()));
    }

    /** 换世界、死亡时静默清空：上一具身体的建议不交给新身体。 */
    public synchronized void clear() {
        active.clear();
        notices.clear();
    }

    /** 通知状态和此刻的风险分开记：撤下后继续保留冷却。 */
    private static final class Notice {
        private String message;
        private String announcedMessage;
        private String reason;
        private long changedAt;
        private long announcedAt;

        private Notice(String message, long now) {
            this.message = announcedMessage = message;
            changedAt = announcedAt = now;
        }

        private void observe(String message, String reason, long now) {
            if (!Objects.equals(this.message, message)) {
                this.message = message;
                changedAt = now;
            }
            this.reason = reason;
        }
    }
}
