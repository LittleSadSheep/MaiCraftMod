// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一个能力的参数规格：参数只在这里定义一次。
 *
 * <p>能力说明里的参数表（{@link #describe}）、MCP 入口的校验与规范化（{@link #parse}）、
 * 能力拿到的带类型取值（{@link Params}），都从这一份定义生成。能力说明的正文不再用散文重复校验规则，
 * 也就不会出现"说明里写一套、校验一套、实际取值又一套"的情况。
 *
 * <p>统一规则：
 * <ul>
 *   <li>显式的 null 与省略同义；</li>
 *   <li>未声明的参数直接报错，并列出可用的参数名；</li>
 *   <li>少数宽松写法（整数字符串、布尔字符串、选项大小写、单个字符串当作一项列表）统一接受，
 *       并在 notes 里说明做过什么；其余一律严格校验；</li>
 *   <li>同一请求的所有错误一次报全。</li>
 * </ul>
 */
public final class ParamSpec {
    /** 不接受任何参数的能力。 */
    public static final ParamSpec EMPTY = new ParamSpec(List.of());

    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern INTEGER_TEXT = Pattern.compile("[+-]?\\d{1,18}");
    private static final Pattern NUMBER_TEXT = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    private final Map<String, Param> params;
    private final List<String> order;

    private ParamSpec(List<Param> list) {
        Map<String, Param> byName = new LinkedHashMap<>();
        for (Param param : list) {
            if (byName.putIfAbsent(param.name(), param) != null) {
                throw new IllegalArgumentException("参数 " + param.name() + " 重复声明");
            }
        }
        this.params = Map.copyOf(byName);
        this.order = List.copyOf(byName.keySet());
    }

    public static ParamSpec of(Param... list) {
        return new ParamSpec(List.of(list));
    }

    /** 按声明顺序的全部参数。 */
    public List<Param> params() {
        return order.stream().map(params::get).toList();
    }

    /** 解析一份原始参数；{@code raw} 为 null 时按空对象处理。 */
    public ParseResult parse(JsonObject raw) {
        JsonObject input = raw == null ? new JsonObject() : raw;
        List<ParamError> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();

        for (String key : input.keySet()) {
            if (!params.containsKey(key)) {
                errors.add(new ParamError(key, "这个能力没有参数 " + key,
                        order.isEmpty() ? "不接受任何参数" : "可用的参数：" + String.join("、", order)));
            }
        }
        for (String name : order) {
            Param param = params.get(name);
            JsonElement element = input.get(name);
            if (element == null || element.isJsonNull()) {
                if (param.required()) {
                    errors.add(new ParamError(name, "缺少必填参数 " + name, expected(param)));
                } else if (param.defaultValue() != null) {
                    values.put(name, param.defaultValue());
                }
                continue;
            }
            Object value = convert(param, element, errors, notes);
            if (value != null) values.put(name, value);
        }
        return new ParseResult(errors.isEmpty() ? new Params(values) : null, errors, notes);
    }

    /** 能力说明里的参数表，由本规格生成。 */
    public JsonArray describe() {
        JsonArray fields = new JsonArray();
        for (Param param : params()) {
            JsonObject field = new JsonObject();
            field.addProperty("name", param.name());
            field.addProperty("type", param.type().schemaType());
            field.addProperty("required", param.required());
            field.addProperty("doc", param.doc());
            if (param.defaultValue() != null) field.add("default", toJson(param.defaultValue()));
            if (param.min() != null) field.addProperty("min", param.min());
            if (param.max() != null) field.addProperty("max", param.max());
            if (!param.choices().isEmpty()) {
                JsonArray choices = new JsonArray();
                param.choices().forEach(choices::add);
                field.add("choices", choices);
            }
            fields.add(field);
        }
        return fields;
    }

    private static Object convert(Param param, JsonElement element, List<ParamError> errors, List<String> notes) {
        String name = param.name();
        return switch (param.type()) {
            case INTEGER -> integer(param, element, errors, notes);
            case NUMBER -> number(param, element, errors, notes);
            case BOOLEAN -> bool(name, element, errors, notes);
            case TEXT -> text(name, element, errors);
            case CHOICE -> choice(param, element, errors, notes);
            case ITEM_OR_TAG, BLOCK_OR_TAG -> resource(name, element, true, errors, notes);
            case ENTITY_TYPE -> resource(name, element, false, errors, notes);
            case ITEM_LIST, BLOCK_LIST, TEXT_LIST -> list(param, element, errors, notes);
        };
    }

    private static Long integer(Param param, JsonElement element, List<ParamError> errors, List<String> notes) {
        Long value = null;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            double raw = element.getAsDouble();
            if (raw == Math.rint(raw) && !Double.isInfinite(raw)) value = (long) raw;
        } else if (isString(element) && INTEGER_TEXT.matcher(element.getAsString().trim()).matches()) {
            value = Long.parseLong(element.getAsString().trim());
            notes.add(param.name() + "：\"" + element.getAsString() + "\" 按整数 " + value + " 处理");
        }
        if (value == null) {
            errors.add(new ParamError(param.name(), param.name() + " 必须是整数", expected(param)));
            return null;
        }
        return inRange(param, value, errors) ? value : null;
    }

    private static Double number(Param param, JsonElement element, List<ParamError> errors, List<String> notes) {
        Double value = null;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            value = element.getAsDouble();
        } else if (isString(element) && NUMBER_TEXT.matcher(element.getAsString().trim()).matches()) {
            value = Double.parseDouble(element.getAsString().trim());
            notes.add(param.name() + "：\"" + element.getAsString() + "\" 按数字 " + value + " 处理");
        }
        if (value == null || value.isNaN() || value.isInfinite()) {
            errors.add(new ParamError(param.name(), param.name() + " 必须是数字", expected(param)));
            return null;
        }
        boolean belowMin = param.min() != null && value < param.min();
        boolean aboveMax = param.max() != null && value > param.max();
        if (belowMin || aboveMax) {
            errors.add(new ParamError(param.name(), param.name() + " 超出范围：" + value, expected(param)));
            return null;
        }
        return value;
    }

    private static Boolean bool(String name, JsonElement element, List<ParamError> errors, List<String> notes) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) return element.getAsBoolean();
        if (isString(element)) {
            String text = element.getAsString().trim().toLowerCase(Locale.ROOT);
            if (text.equals("true") || text.equals("false")) {
                notes.add(name + "：\"" + element.getAsString() + "\" 按布尔值 " + text + " 处理");
                return Boolean.parseBoolean(text);
            }
        }
        errors.add(new ParamError(name, name + " 必须是 true 或 false", "布尔值"));
        return null;
    }

    private static String text(String name, JsonElement element, List<ParamError> errors) {
        if (isString(element) && !element.getAsString().isBlank()) return element.getAsString();
        errors.add(new ParamError(name, name + " 必须是非空文字", "文字"));
        return null;
    }

    private static String choice(Param param, JsonElement element, List<ParamError> errors, List<String> notes) {
        if (isString(element)) {
            String raw = element.getAsString();
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (param.choices().contains(value)) {
                if (!value.equals(raw)) notes.add(param.name() + "：\"" + raw + "\" 按 \"" + value + "\" 处理");
                return value;
            }
        }
        errors.add(new ParamError(param.name(), param.name() + " 只能取这些值之一", expected(param)));
        return null;
    }

    private static String resource(String name, JsonElement element, boolean allowTag,
                                   List<ParamError> errors, List<String> notes) {
        if (isString(element)) {
            String raw = element.getAsString();
            String value = raw.trim().toLowerCase(Locale.ROOT);
            String id = value.startsWith("#") ? value.substring(1) : value;
            if ((allowTag || !value.startsWith("#")) && RESOURCE_ID.matcher(id).matches()) {
                if (!value.equals(raw)) notes.add(name + "：\"" + raw + "\" 按 \"" + value + "\" 处理");
                return value;
            }
        }
        errors.add(new ParamError(name, name + " 写法不对", allowTag
                ? "命名空间:路径，例如 minecraft:torch；标签以 # 开头，例如 #minecraft:logs"
                : "命名空间:路径，例如 minecraft:sheep"));
        return null;
    }

    private static List<String> list(Param param, JsonElement element, List<ParamError> errors, List<String> notes) {
        List<JsonElement> items = new ArrayList<>();
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(items::add);
        } else {
            items.add(element);
            notes.add(param.name() + "：单个值按只有一项的列表处理");
        }
        Set<String> values = new LinkedHashSet<>();
        int before = errors.size();
        for (JsonElement item : items) {
            Object value = param.type() == ParamType.TEXT_LIST
                    ? text(param.name(), item, errors)
                    : resource(param.name(), item, true, errors, notes);
            if (value != null) values.add((String) value);
        }
        if (errors.size() > before) return null;
        if (values.isEmpty()) {
            errors.add(new ParamError(param.name(), param.name() + " 至少要有一项", expected(param)));
            return null;
        }
        return List.copyOf(values);
    }

    private static boolean inRange(Param param, long value, List<ParamError> errors) {
        boolean belowMin = param.min() != null && value < param.min();
        boolean aboveMax = param.max() != null && value > param.max();
        if (belowMin || aboveMax) {
            errors.add(new ParamError(param.name(), param.name() + " 超出范围：" + value, expected(param)));
            return false;
        }
        return true;
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static String expected(Param param) {
        String range = param.min() == null && param.max() == null ? ""
                : " " + (param.min() == null ? "" : param.min()) + ".." + (param.max() == null ? "" : param.max());
        return switch (param.type()) {
            case INTEGER -> "整数" + range;
            case NUMBER -> "数字" + range;
            case BOOLEAN -> "布尔值";
            case TEXT -> "文字";
            case CHOICE -> String.join(" / ", param.choices());
            case ITEM_OR_TAG -> "物品 ID 或 # 开头的物品标签";
            case BLOCK_OR_TAG -> "方块 ID 或 # 开头的方块标签";
            case ENTITY_TYPE -> "实体类型 ID";
            case ITEM_LIST -> "物品 ID 或标签的数组";
            case BLOCK_LIST -> "方块 ID 或标签的数组";
            case TEXT_LIST -> "文字数组";
        };
    }

    private static JsonElement toJson(Object value) {
        return switch (value) {
            case Long number -> new JsonPrimitive(number);
            case Double number -> new JsonPrimitive(number);
            case Boolean flag -> new JsonPrimitive(flag);
            case List<?> list -> {
                JsonArray array = new JsonArray();
                list.forEach(item -> array.add(String.valueOf(item)));
                yield array;
            }
            default -> new JsonPrimitive(String.valueOf(value));
        };
    }
}
