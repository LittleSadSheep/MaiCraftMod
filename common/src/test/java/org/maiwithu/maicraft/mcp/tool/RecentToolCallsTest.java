// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 最近的工具调用：调试面板靠它看 LLM 发了什么、成没成。 */
class RecentToolCallsTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private final RecentToolCalls calls = new RecentToolCalls(clock::get);

    @Test
    void callThatIsStillWaitingShowsAsRunningUntilItEnds() {
        // events 挂着等新事件时，面板要看得出宿主正在等，而不是什么都没发生。
        long id = calls.started("events", object("{\"wait_ms\":30000}"));

        assertTrue(calls.recent().getFirst().running());

        clock.set(31_000);
        calls.ended(id, ToolReply.ok(new JsonObject()));
        RecentToolCalls.Call call = calls.recent().getFirst();
        assertTrue(call.succeeded());
        assertEquals(31_000, call.endedAtMillis());
        assertEquals("{\"wait_ms\":30000}", call.arguments(), "参数原样压缩成一行");
    }

    @Test
    void parameterErrorKeepsCodeAndTheFirstWrongField() {
        // LLM 的 execute 被参数校验挡回：面板上要写出哪个字段错了、为什么，不只是"失败"。
        long id = calls.started("execute", object("{\"goal\":{\"ability\":\"maicraft:obtain\"}}"));
        calls.ended(id, ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数不对",
                List.of(new FieldError("goal.parameters.count", "count 必须是 1 到 256 的整数", null)), null));

        RecentToolCalls.Call call = calls.recent().getFirst();

        assertEquals("invalid_parameter", call.errorCode());
        assertEquals("goal.parameters.count", call.errorField());
        assertEquals("count 必须是 1 到 256 的整数", call.errorMessage());
    }

    @Test
    void callThatNeverRepliedIsNotLeftRunning() {
        // 调用中途出了没接住的错、没有回话：照样结账，不能一直挂成"处理中"让面板以为宿主还在等。
        calls.ended(calls.started("observe", new JsonObject()), null);

        RecentToolCalls.Call call = calls.recent().getFirst();

        assertEquals("internal_error", call.errorCode());
        assertEquals("调用没有返回", call.errorMessage());
    }

    @Test
    void keepsOnlyTheMostRecentCallsAndShortensLongArguments() {
        for (int i = 0; i < RecentToolCalls.KEPT_CALLS + 5; i++) {
            calls.ended(calls.started("observe", new JsonObject()), ToolReply.ok(new JsonObject()));
        }
        JsonObject big = new JsonObject();
        big.addProperty("text", "长".repeat(RecentToolCalls.ARGUMENTS_LIMIT));
        calls.started("execute", big);

        List<RecentToolCalls.Call> recent = calls.recent();

        assertEquals(RecentToolCalls.KEPT_CALLS, recent.size());
        String arguments = recent.getLast().arguments();
        assertEquals(RecentToolCalls.ARGUMENTS_LIMIT, arguments.length());
        assertTrue(arguments.endsWith("…"), "截断的参数要看得出后面还有");
        assertNull(recent.getLast().errorCode());
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
