// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析、校验并规范化之后的参数取值。翻译和执行器只通过它读参数，不再自己解析 JSON。
 *
 * <p>取值类型：整数为 long、数字为 double、布尔为 boolean、文字与资源 ID 为 String、列表为 {@code List<String>}。
 * 没有给出且没有默认值的可选参数不在其中，读取前用 {@link #has} 判断。
 */
public final class Params {
    /** 没有任何参数。 */
    public static final Params EMPTY = new Params(Map.of());

    private final Map<String, Object> values;

    Params(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public long integer(String name) {
        return (Long) require(name);
    }

    public double number(String name) {
        return (Double) require(name);
    }

    public boolean bool(String name) {
        return (Boolean) require(name);
    }

    /** 文字、选项或资源 ID。 */
    public String text(String name) {
        return (String) require(name);
    }

    @SuppressWarnings("unchecked")
    public List<String> list(String name) {
        return (List<String>) require(name);
    }

    /** 写回 JSON，用于持久化与回执里展示 Mod 实际采用的参数。 */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        values.forEach((name, value) -> {
            switch (value) {
                case Long number -> json.addProperty(name, number);
                case Double number -> json.addProperty(name, number);
                case Boolean flag -> json.addProperty(name, flag);
                case String text -> json.addProperty(name, text);
                case List<?> list -> {
                    JsonArray array = new JsonArray();
                    list.forEach(item -> array.add(String.valueOf(item)));
                    json.add(name, array);
                }
                default -> throw new IllegalStateException("参数 " + name + " 的取值类型不受支持：" + value.getClass());
            }
        });
        return json;
    }

    private Object require(String name) {
        Object value = values.get(name);
        if (value == null) throw new IllegalStateException("参数 " + name + " 没有取值；可选参数读取前先用 has() 判断");
        return value;
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
