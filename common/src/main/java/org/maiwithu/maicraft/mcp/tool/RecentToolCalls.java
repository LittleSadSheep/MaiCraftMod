// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * MCP 入口最近收到的工具调用：给调试面板看 LLM 有没有真的在发调用、发了什么、成没成。
 *
 * <p>请求线程写、客户端线程读，方法都加锁，读出来的是不可变的抄录。只留最近几十次调用，不当历史存；
 * 参数原样压缩成一行，太长的截断并以"…"结尾——这里只供人在面板上看，不进任何给 LLM 的回话。
 */
public final class RecentToolCalls {

    /** 留最近多少次调用：面板底部的时间线最多十几行，同一工具连着调用还会合成一行，留这些够看。 */
    static final int KEPT_CALLS = 32;

    /** 参数压缩成一行后最多留多少字：面板上一条最多折三行，再多也显示不出来。 */
    static final int ARGUMENTS_LIMIT = 400;

    private final LongSupplier clockMillis;
    private final Deque<Call> calls = new ArrayDeque<>();
    private long nextId = 1;

    /** 用现实时钟记时刻。 */
    public RecentToolCalls() {
        this(System::currentTimeMillis);
    }

    /** @param clockMillis 现实时钟（毫秒）；离线测试换成可控的钟 */
    public RecentToolCalls(LongSupplier clockMillis) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** 一次调用进来了，返回它的编号；结束时用编号交回结果。 */
    public synchronized long started(String tool, JsonObject arguments) {
        long id = nextId++;
        calls.addLast(new Call(id, tool, compact(arguments), clockMillis.getAsLong(), Call.RUNNING, null, null, null));
        while (calls.size() > KEPT_CALLS) calls.removeFirst();
        return id;
    }

    /** 一次调用结束了：按回话记下成功，或错误码、写错的字段与说明。 */
    public synchronized void ended(long id, JsonObject reply) {
        long now = clockMillis.getAsLong();
        List<Call> updated = new ArrayList<>(calls.size());
        for (Call call : calls) {
            updated.add(call.id() == id ? finished(call, reply, now) : call);
        }
        calls.clear();
        calls.addAll(updated);
    }

    /** 最近的调用，先进来的在前；还没结束的也在里面。 */
    public synchronized List<Call> recent() {
        return List.copyOf(calls);
    }

    // 回话里 ok=false 时取错误码、第一个写错的字段与说明；ok=true 时只记成功。
    private static Call finished(Call call, JsonObject reply, long endedAt) {
        if (reply != null && reply.has("ok") && !reply.get("ok").getAsBoolean() && reply.has("error")) {
            JsonObject error = reply.getAsJsonObject("error");
            String field = null;
            String message = text(error, "message");
            if (error.has("fields") && !error.getAsJsonArray("fields").isEmpty()) {
                JsonObject first = error.getAsJsonArray("fields").get(0).getAsJsonObject();
                field = text(first, "field");
                message = text(first, "message");
            }
            return new Call(call.id(), call.tool(), call.arguments(), call.startedAtMillis(), endedAt,
                    text(error, "code"), field, message);
        }
        return new Call(call.id(), call.tool(), call.arguments(), call.startedAtMillis(), endedAt, null, null, null);
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    // 参数原样压成一行；太长的留前面一段并以"…"结尾，让人看得出后面还有。
    private static String compact(JsonObject arguments) {
        String text = arguments == null ? "{}" : arguments.toString();
        return text.length() <= ARGUMENTS_LIMIT ? text : text.substring(0, ARGUMENTS_LIMIT - 1) + "…";
    }

    /**
     * 一次工具调用。
     *
     * @param id              进来的先后编号
     * @param tool            工具名
     * @param arguments       原样压缩成一行的参数
     * @param startedAtMillis 进来的现实时刻
     * @param endedAtMillis   结束的现实时刻；还没结束时是 {@link #RUNNING}
     * @param errorCode       出错时的错误码；成功或还没结束时为 null
     * @param errorField      出错时第一个写错的字段；不是参数问题时为 null
     * @param errorMessage    出错时的说明
     */
    public record Call(long id, String tool, String arguments, long startedAtMillis, long endedAtMillis,
                       String errorCode, String errorField, String errorMessage) {
        /** 还没结束的调用的结束时刻。 */
        public static final long RUNNING = -1;

        /** 还在处理中（例如 events 正挂着等新事件）。 */
        public boolean running() {
            return endedAtMillis == RUNNING;
        }

        /** 已经结束且成功。 */
        public boolean succeeded() {
            return !running() && errorCode == null;
        }
    }
}
