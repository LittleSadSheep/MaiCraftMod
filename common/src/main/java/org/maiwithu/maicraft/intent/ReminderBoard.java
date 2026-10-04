// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 保存仍适用的游戏提醒；事件只负责唤醒，状态事实由快照在每次工具调用时完整交付。 */
public final class ReminderBoard {
    private static final long PERIODIC_INTERVAL = Duration.ofMinutes(10).toNanos();
    private static final long CHANGE_INTERVAL = Duration.ofSeconds(30).toNanos();
    private static final long SETTLE_INTERVAL = Duration.ofSeconds(2).toNanos();
    private final Map<String, JsonObject> active = new LinkedHashMap<>();
    private final Map<String, Notice> notices = new LinkedHashMap<>();
    private final Consumer<JsonObject> announce;
    private final LongSupplier clock;

    public ReminderBoard(Consumer<JsonObject> announce) { this(announce, System::nanoTime); }

    /** 单调时钟只安排通知；世界时间仍原样写入事实，校时或回拨不能绕过同一提醒的冷却。 */
    public ReminderBoard(Consumer<JsonObject> announce, LongSupplier clock) {
        this.announce = announce; this.clock = clock;
    }

    /**
     * 首次危险立即唤醒；切换工具、贴水面进出或文案来回变化时，只更新当前快照和待通知状态。
     * 已解除的提醒仍保留通知记录，重新出现不能伪装成第一次危险绕过冷却。
     */
    public synchronized void update(String id, String message, JsonObject evidence,
                                    JsonArray suggestions, long tick) {
        JsonObject previous = active.get(id);
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("status", "active");
        value.addProperty("severity", "advisory");
        value.addProperty("message", message);
        value.addProperty("first_observed_tick", previous == null || tick < previous.get("observed_at_tick").getAsLong()
                ? tick : previous.get("first_observed_tick").getAsLong());
        value.addProperty("observed_at_tick", tick);
        value.add("evidence", evidence.deepCopy());
        value.add("suggested_actions", suggestions.deepCopy());
        active.put(id, value);
        long now = clock.getAsLong();
        Notice notice = notices.get(id);
        if (notice == null) {
            notices.put(id, new Notice(message, now));
            announce.accept(value.deepCopy());
        } else notice.observe(message, null, tick, now);
    }

    /** 条件消失立即撤下快照；解除通知等待稳定，短暂离场后又遇险不会先报解除再连发新警报。 */
    public synchronized void remove(String id, String reason, long tick) {
        active.remove(id);
        Notice notice = notices.get(id);
        if (notice != null) notice.observe(null, reason, tick, clock.getAsLong());
    }

    /** 本刻观察全部完成后合并通知；只发仍然成立的最新状态，绝不排队重放已经反转的解除或文案。 */
    public synchronized void flush() {
        long now = clock.getAsLong();
        // 回调可以读取或重置提醒板，先冻结 ID 集合，避免一次世界重置留下旧通知或破坏遍历。
        for (String id : List.copyOf(notices.keySet())) {
            Notice notice = notices.get(id);
            if (notice == null) continue;
            JsonObject value = active.get(id);
            boolean periodic = value != null && now - notice.announcedAt >= PERIODIC_INTERVAL;
            boolean changed = !Objects.equals(notice.announcedMessage, notice.message)
                    && now - notice.changedAt >= SETTLE_INTERVAL && now - notice.announcedAt >= CHANGE_INTERVAL;
            if (!periodic && !changed) continue;
            JsonObject event = value == null ? new JsonObject() : value.deepCopy();
            if (value == null) {
                event.addProperty("id", id); event.addProperty("status", "cleared");
                event.addProperty("reason", notice.reason);
                event.addProperty("observed_at_tick", notice.observedAtTick);
                event.addProperty("message", "游戏提醒已撤下：" + notice.reason);
            }
            notice.announcedAt = now; notice.announcedMessage = notice.message;
            announce.accept(event);
        }
    }

    /** 读取不消费提醒，也不延长有效期；不同宿主和连续工具调用都能独立看到完整证据。 */
    public synchronized JsonArray snapshot() {
        JsonArray result = new JsonArray();
        active.values().forEach(value -> result.add(value.deepCopy()));
        return result;
    }

    /** 换世界或死亡时静默清空，不能把上一具身体的建议交给新身体。 */
    public synchronized void clear() { active.clear(); notices.clear(); }

    /** 通知状态独立于当前风险：解除后继续保留冷却，事实数值变化不延后已有文案或解除的稳定时间。 */
    private static final class Notice {
        private String message, announcedMessage, reason;
        private long changedAt, announcedAt, observedAtTick;
        private Notice(String message, long now) {
            this.message = announcedMessage = message;
            changedAt = announcedAt = now;
        }
        private void observe(String message, String reason, long tick, long now) {
            if (!Objects.equals(this.message, message)) { this.message = message; changedAt = now; }
            this.reason = reason; observedAtTick = tick;
        }
    }
}
