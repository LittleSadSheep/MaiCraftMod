// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 检查一次请求时记下的错误与规范化说明，以及按宽松写法读字段的办法。
 *
 * <p>宽松写法（"6" 当作 6、"true" 当作 true、单个字符串当作一项的列表）只在 MCP 入口这里统一处理，
 * 每处理一处就在 notes 里说明一次；能力和内核只拿到规范的取值。错误不在第一处就停，一次请求的问题全部记下。
 */
final class RequestCheck {
    private final List<FieldError> errors = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private ErrorCode code = ErrorCode.INVALID_PARAMETER;

    void error(String field, String message, String expected) {
        errors.add(new FieldError(field, message, expected));
    }

    void note(String text) {
        notes.add(text);
    }

    /** 错误里有一条是"没有这个能力"时，整次请求的错误种类按它报，调用方先改能力名。 */
    void markUnknownAbility() {
        code = ErrorCode.UNKNOWN_ABILITY;
    }

    boolean ok() {
        return errors.isEmpty();
    }

    List<FieldError> errors() {
        return List.copyOf(errors);
    }

    List<String> notes() {
        return List.copyOf(notes);
    }

    ErrorCode code() {
        return code;
    }

    /** 读一个文字字段；没给或给了 null 时为 null（必填时记一条错误）。 */
    String text(JsonObject object, String key, String path, boolean required) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            if (required) error(path, "缺少 " + key, "文字");
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            error(path, key + " 应该是文字", "文字");
            return null;
        }
        String text = element.getAsString().trim();
        if (text.isEmpty()) {
            error(path, key + " 不能为空", "非空文字");
            return null;
        }
        return text;
    }

    /** 读一个整数字段；整数写成字符串时按整数处理并说明。 */
    Integer integer(JsonObject object, String key, String path, boolean required) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            if (required) error(path, "缺少 " + key, "整数");
            return null;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            String raw = primitive.getAsString().trim();
            if (raw.matches("[+-]?\\d{1,9}")) {
                int value = Integer.parseInt(raw);
                if (primitive.isString()) note(path + "：\"" + raw + "\" 按整数 " + value + " 处理");
                return value;
            }
        }
        error(path, key + " 应该是整数", "整数");
        return null;
    }

    /** 读一个布尔字段；"true"、"false" 字符串按布尔处理并说明。 */
    Boolean bool(JsonObject object, String key, String path) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            String raw = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
            if (raw.equals("true") || raw.equals("false")) {
                note(path + "：\"" + primitive.getAsString() + "\" 按布尔值 " + raw + " 处理");
                return Boolean.parseBoolean(raw);
            }
        }
        error(path, key + " 应该是 true 或 false", "true / false");
        return null;
    }

    /** 读一个从固定选项里选的字段；大小写与首尾空白整理后比较。 */
    String choice(JsonObject object, String key, String path, List<String> choices) {
        String raw = text(object, key, path, false);
        if (raw == null) return null;
        String value = raw.toLowerCase(Locale.ROOT);
        if (!choices.contains(value)) {
            error(path, key + " 没有 \"" + raw + "\" 这个取值", String.join(" / ", choices));
            return null;
        }
        if (!value.equals(raw)) note(path + "：\"" + raw + "\" 按 \"" + value + "\" 处理");
        return value;
    }

    /** 读一个文字列表；只给一个字符串时按一项的列表处理并说明。 */
    List<String> texts(JsonObject object, String key, String path) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            note(path + "：单个字符串按一项的列表处理");
            return List.of(element.getAsString().trim());
        }
        if (!element.isJsonArray()) {
            error(path, key + " 应该是文字列表", "[\"…\", \"…\"]");
            return null;
        }
        List<String> values = new ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().isBlank()) {
                error(path + "[" + i + "]", "列表里每一项都应该是非空文字", "文字");
                continue;
            }
            values.add(item.getAsString().trim());
        }
        return values;
    }

    /** 对象里有没有不认识的字段：有就逐个记错误，并列出认识的字段。 */
    void rejectUnknownFields(JsonObject object, String path, List<String> known) {
        for (String key : object.keySet()) {
            if (!known.contains(key)) {
                error(path + "." + key, (path.isEmpty() ? "" : path + " ") + "没有字段 " + key,
                        "可用的字段：" + String.join("、", known));
            }
        }
    }
}
