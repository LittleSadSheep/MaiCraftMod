// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 给外部 AI 的有界消息流骨架：按发生顺序记录最近二百五十六条消息，读取者携带游标续读，
 * 落后过多即报告 history_lost，换世界时更换流编号作废旧进度；订阅者在新消息到达时收到推送。
 * "有界"是承诺的一部分：容量之外最旧的消息会被淘汰且不可恢复，不要把它当作归档存储。
 *
 * <p>为什么抽基类：两个流对外实现同一套游标协议（分页、stream_reset、history_lost、
 * resync_required），机制若各写一份，修复一处时容易漏掉另一处，使同一协议出现两种行为。
 * 因此机制只有这一份实现；子类只决定三件事——发布时的消息形状（publish 留在子类）、
 * 读取时的过滤条件（readPage 的 filter 参数）、以及回执键名与文案（pageKey/label）。
 *
 * <p>边界：它不是可靠队列——没有确认与重投递，订阅者错过的消息只能靠游标分页拉取补读，
 * 且受容量上限约束；也不跨进程持久化，换世界即作废旧进度。
 *
 * <p>锁契约：发布者必须在"构建条目 → nextCursor → store → readPage 取信号"的同一个
 * synchronized(this) 块内完成发布，条目顺序才与游标严格一致；订阅者通知必须在锁外
 * （notifyListeners），回调里不得再进入本对象的锁。
 *
 * <p>分化出口：仅过滤不同时传不同的 filter，不必覆盖；若某个流未来需要整页不同的
 * 分页行为，可覆盖 readPage；若存储层走向不同（如持久化），应拆掉本基类而不是绕它。
 *
 * <p>之后再出现新的消息流需求（新的游戏内事件流、日志流等）时，继承本类并只实现
 * 发布与 pageKey/label 两个名字；不要复制现有子类另起一份机制。
 */
abstract class BoundedMessageStream {
    private static final int CAPACITY = 256;
    private final List<JsonObject> entries = new ArrayList<>();
    private final CopyOnWriteArrayList<Consumer<JsonElement>> listeners = new CopyOnWriteArrayList<>();
    private String streamId = UUID.randomUUID().toString();
    private long cursor;

    /** 本流的事件在读取回执里的键名，例如 "messages" 或 "events"。 */
    protected abstract String pageKey();

    /** 游标校验失败文案里对流的名字，例如 "chat" 或 "attention"。 */
    protected abstract String label();

    /** 发布者构建条目时取下一个递增游标；必须在调用方自己的同步块内调用，保证条目与游标一致。 */
    protected final synchronized long nextCursor() {
        return ++cursor;
    }

    /** 入列并按容量淘汰最旧条目；与 nextCursor 相同的同步块约束。 */
    protected final synchronized void store(JsonObject entry) {
        entries.add(entry);
        if (entries.size() > CAPACITY) entries.removeFirst();
    }

    protected final synchronized String streamId() {
        return streamId;
    }

    protected final synchronized long currentCursor() {
        return cursor;
    }

    synchronized JsonObject checkpoint() {
        // stream_id 表示这一轮事件流，cursor 表示读到了哪条；换世界后两者要重新同步。
        JsonObject result = new JsonObject();
        result.addProperty("stream_id", streamId);
        result.addProperty("cursor", cursor);
        return result;
    }

    /**
     * 游标协议的唯一实现：校验进度、判定重置（流编号不符或游标超前）与丢失（游标落后于已淘汰的
     * 最旧条目）、按进度分页，并在回执中附带最新/最旧游标与恢复提示。
     * filter 决定哪些条目进入本页：全量流传 {@code event -> true}，任务通知流传任务匹配谓词。
     *
     * <p>游标失效必须显式交付：{@code cursor_valid=false} 配 {@code cursor_status} 说明失效原因
     * 与恢复动作，调用方不需要从空事件页自行猜测；缺口量化为 {@code unread_before_page}（本页
     * 窗口之前未交付的匹配条目）与 {@code evicted_before_page}（容量淘汰、不可恢复的流内条目），
     * 让跳跃可被调用方察觉而不是静默吞掉。
     */
    protected synchronized JsonObject readPage(long after, int limit, String expectedStream, Predicate<JsonObject> filter) {
        // 调用者带来的流编号变了，或游标比当前最新事件还大，说明它拿着另一轮的进度，需要重新对齐。
        if (after < 0 || limit < 1) throw new IllegalArgumentException("Invalid " + label() + " cursor or limit");
        boolean reset = expectedStream != null && !streamId.equals(expectedStream) || after > cursor;
        boolean initial = expectedStream == null && after == 0;
        long oldest = entries.isEmpty() ? cursor + 1 : entries.getFirst().get("cursor").getAsLong();
        long from = reset ? 0 : after;
        boolean lost = !initial && from < oldest - 1;
        List<JsonObject> matching = entries.stream()
                .filter(entry -> entry.get("cursor").getAsLong() > from)
                .filter(filter::test).toList();
        // 第一次未带进度时只看最近几条；已经带进度时从那里往后读，不能跳过中间尚未读完的事件。
        int start = initial ? Math.max(0, matching.size() - limit) : 0;
        JsonArray page = new JsonArray();
        int end = Math.min(matching.size(), start + limit);
        for (int i = start; i < end; i++) page.add(matching.get(i).deepCopy());
        boolean more = end < matching.size();
        long next = more ? matching.get(end - 1).get("cursor").getAsLong() : cursor;
        JsonObject result = checkpoint();
        result.addProperty("cursor", next);
        result.addProperty("latest_cursor", cursor);
        result.addProperty("oldest_cursor", oldest);
        result.addProperty("has_more", more);
        result.addProperty("history_lost", lost);
        result.addProperty("stream_reset", reset);
        result.addProperty("resync_required", reset || lost);
        // 游标是否被原样遵守；失效时给调用方一句可直接执行的恢复动作，替代猜游标。
        result.addProperty("cursor_valid", !(reset || lost));
        if (reset || lost) result.addProperty("cursor_status", invalidationNotice(reset, oldest));
        // 页边界与缺口量化：调用方对比相邻事件的 cursor 即可察觉任何跳跃，不必靠人眼对账。
        if (!page.isEmpty()) {
            result.addProperty("page_first_cursor", page.get(0).getAsJsonObject().get("cursor").getAsLong());
            result.addProperty("page_last_cursor", page.get(page.size() - 1).getAsJsonObject().get("cursor").getAsLong());
        }
        if (start > 0) result.addProperty("unread_before_page", start);
        long evicted = Math.max(0, oldest - 1 - from);
        if (evicted > 0) result.addProperty("evicted_before_page", evicted);
        result.add(pageKey(), page);
        return result;
    }

    private String invalidationNotice(boolean reset, long oldest) {
        String recovery = " continue paging from the cursor in this response";
        if (reset) return "cursor invalidated: stream_id mismatch or cursor ahead of stream;" + recovery;
        return "cursor invalidated: events up to cursor " + (oldest - 1)
                + " were evicted (capacity " + CAPACITY + ") and cannot be recovered; this page starts at the oldest retained event;" + recovery;
    }

    AutoCloseable subscribe(Consumer<JsonElement> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void clear() {
        // 换世界等情况下清空旧事件并换一个 stream_id，让等待者知道旧游标不能继续用了。
        JsonObject signal;
        synchronized (this) {
            entries.clear();
            cursor = 0;
            streamId = UUID.randomUUID().toString();
            signal = checkpoint();
            signal.addProperty("stream_reset", true);
        }
        notifyListeners(signal);
    }

    /** 每个订阅者拿到自己的副本；某个订阅者处理失败，也不影响其他人与事件继续交付。必须在锁外调用。 */
    protected final void notifyListeners(JsonObject signal) {
        for (Consumer<JsonElement> listener : listeners) {
            try { listener.accept(signal.deepCopy()); }
            catch (RuntimeException ignored) { /* 单个订阅者出错不能阻断其他事件的交付。 */ }
        }
    }
}
