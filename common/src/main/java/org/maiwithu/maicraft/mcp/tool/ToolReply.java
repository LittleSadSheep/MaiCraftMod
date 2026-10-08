// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

/**
 * 所有 MCP 工具共用的返回格式。
 *
 * <p>成功：{@code {"ok": true, "data": …, "notes": […], "next": {"tool": …, "arguments": …}}}，
 * notes 说明对请求做过哪些规范化（例如把 "6" 当成 6），next 告诉调用方接着该调哪个工具。
 *
 * <p>失败：{@code {"ok": false, "error": {"code": …, "message": …, "fields": […], "example": …}}}，
 * 校验错误一次报全，fields 逐条列出，不让调用方改一个错报下一个。
 */
public final class ToolReply {
    private ToolReply() {}

    public static JsonObject ok(JsonElement data) {
        return ok(data, List.of(), null);
    }

    /**
     * @param notes 对请求做过的规范化；没有时给空列表
     * @param next  下一步建议的调用；没有时为 null
     */
    public static JsonObject ok(JsonElement data, List<String> notes, JsonObject next) {
        JsonObject reply = new JsonObject();
        reply.addProperty("ok", true);
        reply.add("data", Objects.requireNonNull(data, "data"));
        if (!notes.isEmpty()) {
            JsonArray list = new JsonArray();
            notes.forEach(list::add);
            reply.add("notes", list);
        }
        if (next != null) {
            reply.add("next", next);
        }
        return reply;
    }

    public static JsonObject error(ErrorCode code, String message) {
        return error(code, message, List.of(), null);
    }

    /**
     * @param fields  逐条写错的地方；不是参数问题时给空列表
     * @param example 一个写对了的请求样子；没有时为 null
     */
    public static JsonObject error(ErrorCode code, String message, List<FieldError> fields, JsonObject example) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code.wireName());
        error.addProperty("message", message);
        if (!fields.isEmpty()) {
            JsonArray list = new JsonArray();
            for (FieldError field : fields) {
                JsonObject item = new JsonObject();
                item.addProperty("field", field.field());
                item.addProperty("message", field.message());
                if (field.expected() != null) item.addProperty("expected", field.expected());
                list.add(item);
            }
            error.add("fields", list);
        }
        if (example != null) {
            error.add("example", example);
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("ok", false);
        reply.add("error", error);
        return reply;
    }

    /** 下一步建议的调用：哪个工具、带什么参数，调用方照抄即可。 */
    public static JsonObject next(String tool, JsonObject arguments) {
        JsonObject next = new JsonObject();
        next.addProperty("tool", tool);
        next.add("arguments", arguments);
        return next;
    }
}
