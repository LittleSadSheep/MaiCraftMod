// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 语义任务读取数量、许可和来源清单时共用的严格检查；不把格式错误猜成另一种游戏行动。 */
public final class SemanticParameters {
    private SemanticParameters() {}

    public static List<String> strings(JsonElement value, String label) {
        if (value == null) return List.of();
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

    public static int integer(
            JsonObject object, String key, int fallback, int minimum, int maximum) {
        if (!object.has(key)) return fallback;
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
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        throw new IllegalArgumentException(key + " must be a boolean");
    }

    public static void rejectUnknown(JsonObject object, Set<String> allowed, String label) {
        // 明确提供但不认识的字段必须报错，不能把玩家的限制或数量要求悄悄丢掉。
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException(label + " does not accept " + key);
        }
    }
}
