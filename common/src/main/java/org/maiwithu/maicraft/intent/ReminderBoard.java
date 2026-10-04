// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 保存仍适用的游戏提醒；事件只负责唤醒，状态事实由快照在每次工具调用时完整交付。 */
public final class ReminderBoard {
    private static final long PERIODIC_INTERVAL_TICKS = 10 * 60 * 20;
    private record Entry(JsonObject value, long announcedAt) {}
    private final Map<String, Entry> active = new LinkedHashMap<>();
    private final Consumer<JsonObject> announce;

    public ReminderBoard(Consumer<JsonObject> announce) { this.announce = announce; }

    /**
     * 规则每次重新核实现场后更新证据；同一状态的复核不进事件流，否则持续状态会按观察频率刷屏，
     * 淹没受损、决策等关键事件。只有首次出现、文案变化（规则用文案表达状态实质变化，如入夜、
     * 腐肉耗尽）、游戏时间回退或满十分钟定期窗口才再次唤醒；工具读取始终拿到最新事实。
     */
    public synchronized void update(String id, String message, JsonObject evidence,
                                    JsonArray suggestions, long tick) {
        Entry previous = active.get(id);
        boolean publish = previous == null || tick < previous.announcedAt()
                || !message.equals(previous.value().get("message").getAsString())
                || tick - previous.announcedAt() >= PERIODIC_INTERVAL_TICKS;
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("status", "active");
        value.addProperty("severity", "advisory");
        value.addProperty("message", message);
        value.addProperty("first_observed_tick", previous == null ? tick
                : previous.value().get("first_observed_tick").getAsLong());
        value.addProperty("observed_at_tick", tick);
        value.add("evidence", evidence.deepCopy());
        value.add("suggested_actions", suggestions.deepCopy());
        active.put(id, new Entry(value, publish ? tick : previous.announcedAt()));
        if (publish) announce.accept(value.deepCopy());
    }

    /** 条件消失先撤下常驻提醒，再报告原因；安静或离开现场都不等于已经消灭怪物或完成补光。 */
    public synchronized void remove(String id, String reason, long tick) {
        Entry previous = active.remove(id);
        if (previous == null) return;
        JsonObject event = new JsonObject();
        event.addProperty("id", id);
        event.addProperty("status", "cleared");
        event.addProperty("reason", reason);
        event.addProperty("observed_at_tick", tick);
        event.addProperty("message", "游戏提醒已撤下：" + reason);
        announce.accept(event);
    }

    /** 读取不消费提醒，也不延长有效期；不同宿主和连续工具调用都能独立看到完整证据。 */
    public synchronized JsonArray snapshot() {
        JsonArray result = new JsonArray();
        active.values().forEach(entry -> result.add(entry.value().deepCopy()));
        return result;
    }

    /** 换世界或死亡时静默清空，不能把上一具身体的建议交给新身体。 */
    public synchronized void clear() { active.clear(); }
}
