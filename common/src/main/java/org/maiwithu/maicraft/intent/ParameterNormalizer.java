// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按能力契约声明的类型还原宿主模型改写过的 JSON 编码，再交给原有契约检查。
 * 部分宿主会把 -86 写成 "-86"、把 true 写成 "true"、把数组包成 {"item":[...]}；
 * 这些写法语义唯一，只改编码不改含义。未声明的字段、无法唯一还原的写法原样保留，仍由契约如实拒绝。
 */
public final class ParameterNormalizer {

    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d{1,18}");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
    /** 对象字段里只还原方块坐标和版本号这类固定为整数的键，其他字符串（标签、文字）一律不碰。 */
    private static final Set<String> INTEGER_KEYS = Set.of("x", "y", "z", "schema_version");
    /** goal 顶层字段；其余键若是该能力声明的参数，说明被放错了层级。 */
    private static final Set<String> GOAL_FIELDS = Set.of("ability", "outcome", "target", "parameters",
            "preferences", "constraints", "children", "on_failure");

    private ParameterNormalizer() {}

    /**
     * 原地还原一份公开 goal：目标坐标、参数类型、放错层级的参数，以及 sequence 子目标。
     * 每处改写都记进 notes，回执据此如实说明 Mod 收到后做了哪些编码还原。
     */
    public static void normalizeGoal(JsonObject goal, String path, List<String> notes) {
        if (goal == null || !goal.has("ability") || !isString(goal.get("ability"))) return;
        String ability = goal.get("ability").getAsString();
        // 坐标目标只接受整数格；"-86" 这类写法先还原，再由目标校验检查范围与维度。
        if (goal.has("target") && goal.get("target").isJsonObject()) {
            JsonObject target = goal.getAsJsonObject("target");
            if (target.has("position") && target.get("position").isJsonObject())
                normalizeIntegerKeys(target.getAsJsonObject("position"), path + ".target.position", notes);
        }
        Map<String, String> types = SemanticAbilityCatalog.parameterTypes(ability);
        // 参数被写在 goal 顶层（如 goal.count）时，只有该能力确实声明了这个参数、且 parameters 里没有同名值，
        // 才搬进 goal.parameters；有冲突时保留原样，由入口按"未知字段"拒绝并指出位置。
        JsonObject parameters = goal.has("parameters") && goal.get("parameters").isJsonObject()
                ? goal.getAsJsonObject("parameters") : null;
        for (String key : List.copyOf(goal.keySet())) {
            if (GOAL_FIELDS.contains(key) || !types.containsKey(key)) continue;
            if (parameters == null) {
                // parameters 被写成非对象时不替它重建，交给入口按类型错误拒绝。
                if (goal.has("parameters") && !goal.get("parameters").isJsonNull()) continue;
                parameters = new JsonObject();
                goal.add("parameters", parameters);
            }
            if (parameters.has(key)) continue;
            parameters.add(key, goal.remove(key));
            notes.add(path + "." + key + " -> " + path + ".parameters." + key);
        }
        if (parameters != null) normalizeParameters(ability, parameters, path + ".parameters", notes);
        if (goal.has("children") && goal.get("children").isJsonArray()) {
            JsonArray children = goal.getAsJsonArray("children");
            for (int i = 0; i < children.size(); i++)
                if (children.get(i).isJsonObject())
                    normalizeGoal(children.get(i).getAsJsonObject(), path + ".children[" + i + "]", notes);
        }
    }

    /** 原地还原某能力的参数对象；决策应答的 details.parameters 合并前也走这里。 */
    public static void normalizeParameters(String ability, JsonObject parameters, String path, List<String> notes) {
        Map<String, String> types = SemanticAbilityCatalog.parameterTypes(ability);
        for (String key : List.copyOf(parameters.keySet())) {
            String type = types.get(key);
            if (type == null) continue;
            JsonElement before = parameters.get(key);
            JsonElement after = normalizeValue(type, before, path + "." + key, notes);
            if (after != before) parameters.add(key, after);
        }
    }

    private static JsonElement normalizeValue(String type, JsonElement value, String path, List<String> notes) {
        if (value == null || value.isJsonNull()) return value;
        JsonElement current = value;
        // XML 风格的工具调用会把数组项包成 {"item": ...}；除对象字段外，这层包装没有业务含义。
        if (!"object".equals(type) && current.isJsonObject() && current.getAsJsonObject().size() == 1
                && current.getAsJsonObject().has("item")) {
            current = current.getAsJsonObject().get("item");
            notes.add(path + ": unwrapped {\"item\": ...}");
        }
        if ("integer".equals(type)) return converted(current, integer(current), path, "integer", notes);
        if ("number".equals(type)) return converted(current, number(current), path, "number", notes);
        if ("boolean".equals(type)) return converted(current, bool(current), path, "boolean", notes);
        if (type.startsWith("array")) return array(current, path, notes);
        if ("object".equals(type)) return object(current, path, notes);
        return current;
    }

    private static JsonElement converted(JsonElement original, JsonElement converted, String path, String type,
                                         List<String> notes) {
        if (converted == null) return original;
        notes.add(path + ": string -> " + type);
        return converted;
    }

    private static JsonElement array(JsonElement value, String path, List<String> notes) {
        if (value.isJsonArray()) return value;
        // JSON 文本形式的数组按原文解析；单个字符串或对象视为只有一项的数组。
        if (isString(value)) {
            String text = value.getAsString().trim();
            if (text.startsWith("[")) {
                try {
                    JsonElement parsed = JsonParser.parseString(text);
                    if (parsed.isJsonArray()) {
                        notes.add(path + ": JSON text -> array");
                        return parsed;
                    }
                } catch (RuntimeException ignored) {
                    // 不是合法数组文本时按普通字符串处理，交给契约检查如实拒绝或接受。
                }
            }
        }
        if (value.isJsonPrimitive() || value.isJsonObject()) {
            JsonArray wrapped = new JsonArray();
            wrapped.add(value);
            notes.add(path + ": single value -> array");
            return wrapped;
        }
        return value;
    }

    private static JsonElement object(JsonElement value, String path, List<String> notes) {
        JsonElement current = value;
        // 整个对象被写成 JSON 字符串时先解析；解析失败保留原值。
        if (isString(current) && current.getAsString().trim().startsWith("{")) {
            try {
                JsonElement parsed = JsonParser.parseString(current.getAsString());
                if (parsed.isJsonObject()) {
                    notes.add(path + ": JSON text -> object");
                    current = parsed;
                }
            } catch (RuntimeException ignored) {
                // 非法对象文本交给契约检查，不在这里猜测含义。
            }
        }
        if (current.isJsonObject()) normalizeIntegerKeys(current.getAsJsonObject(), path, notes);
        return current;
    }

    private static void normalizeIntegerKeys(JsonObject object, String path, List<String> notes) {
        for (String key : List.copyOf(object.keySet())) {
            if (!INTEGER_KEYS.contains(key)) continue;
            JsonElement integer = integer(object.get(key));
            if (integer != null) {
                object.add(key, integer);
                notes.add(path + "." + key + ": string -> integer");
            }
        }
    }

    private static JsonElement integer(JsonElement value) {
        if (!isString(value)) return null;
        String text = value.getAsString().trim();
        if (!INTEGER.matcher(text).matches()) return null;
        return new JsonPrimitive(Long.parseLong(text.startsWith("+") ? text.substring(1) : text));
    }

    private static JsonElement number(JsonElement value) {
        if (!isString(value)) return null;
        String text = value.getAsString().trim();
        if (!NUMBER.matcher(text).matches()) return null;
        return new JsonPrimitive(new BigDecimal(text));
    }

    private static JsonElement bool(JsonElement value) {
        if (!isString(value)) return null;
        String text = value.getAsString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text)) return new JsonPrimitive(true);
        if ("false".equals(text)) return new JsonPrimitive(false);
        return null;
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }
}
