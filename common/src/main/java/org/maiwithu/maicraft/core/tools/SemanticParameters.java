// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 语义工具入口解析参数的唯一严格契约：缺席与显式 null 同义，类型错误与越界明确拒绝，绝不悄悄截断或夹取。 */
public final class SemanticParameters {
    private SemanticParameters() {}

    public static List<String> strings(JsonElement value, String label) {
        // 显式 null 与缺席同义：都是"没提供这个清单"，不是格式错误。
        if (value == null || value.isJsonNull()) return List.of();
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException(label + " must be an array of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(label + " must contain only strings");
            }
            String text = element.getAsString();
            if (text.isBlank()) throw new IllegalArgumentException(
                    label + " cannot contain blank values");
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    public static String primitiveString(JsonObject object, String key) {
        if (!object.has(key)) return null;
        JsonElement value = object.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && !value.getAsString().isBlank())
            return value.getAsString();
        throw new IllegalArgumentException(key + " must be a non-blank string");
    }

    /** 可选文本：缺席、显式 null 和空白都表示"没提供"；提供时裁剪成规范形态，供后续按标识符解析。 */
    public static String text(JsonObject object, String key) {
        if (!object.has(key)) return null;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String text = value.getAsString();
        return text == null || text.isBlank() ? null : text.trim();
    }

    public static Integer optionalInteger(JsonObject object, String key, int minimum, int maximum) {
        // 与 integer 的差别只在缺席语义：返回 null 让调用方表达"未提供"，而不是猜一个默认值。
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        JsonElement value = object.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try {
                int number = value.getAsBigDecimal().intValueExact();
                if (number >= minimum && number <= maximum) return number;
            } catch (ArithmeticException | NumberFormatException invalid) { }
        }
        throw new IllegalArgumentException(key + " must be an integer from " + minimum + " to " + maximum);
    }

    public static int integer(
            JsonObject object, String key, int fallback, int minimum, int maximum) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        JsonElement value = object.get(key);
        // 玩家说要几件就按几件检查；小数、字符串和超限数都报错，不能截断或悄悄改成上限。
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try {
                int number = value.getAsBigDecimal().intValueExact();
                if (number >= minimum && number <= maximum) return number;
            } catch (ArithmeticException | NumberFormatException invalid) { }
        }
        throw new IllegalArgumentException(key + " must be an integer from " + minimum + " to " + maximum);
    }

    public static boolean bool(JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        JsonElement value = object.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        throw new IllegalArgumentException(key + " must be a boolean");
    }

    public static void rejectUnknown(JsonObject object, Set<String> allowed, String label) {
        // 明确提供但不认识的字段必须报错，不能把玩家的限制或数量要求悄悄丢掉。
        for (String key : object.keySet()) {
            if (allowed.contains(key)) continue;
            // 拒绝必须带出合法键集合：调用方只能看到这条文本，不说合法集合它只能逐键盲试。
            String accepted = String.join(", ", allowed.stream().sorted().toList());
            throw new IllegalArgumentException(label + " does not accept " + key
                    + "; accepted keys: " + accepted);
        }
    }
}
