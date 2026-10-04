// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.suicide;

import com.google.gson.JsonObject;
import java.util.Set;

/** 只表达本次主动寻死的方式与预算；饥饿或路远是否值得寻死由调用者决定，不由身体维护自行触发。 */
public record SuicideRequest(String method, int radius, int timeoutSeconds, boolean keepInventoryConfirmed) {
    private static final Set<String> METHODS = Set.of("auto", "lava", "hostile", "fall");

    public static SuicideRequest parse(JsonObject parameters) {
        // 先拒绝拼错或类型不符的危险动作参数，不能把不认识的方式悄悄当成自动寻死。
        // 四个工序参数只有省略时才取默认值；显式 null 不算省略，重生开关另由死亡监视器读取。
        String method = "auto";
        if (parameters.has("method")) {
            var value = parameters.get("method");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("method must be auto, lava, hostile or fall");
            method = value.getAsString();
        }
        if (!METHODS.contains(method)) throw new IllegalArgumentException("unsupported suicide method: " + method);
        boolean confirmed = false;
        // 这只是调用方对多人服规则的确认，不负责改游戏规则；单人世界执行时仍以服务端读到的值为准。
        if (parameters.has("keep_inventory_confirmed")) {
            var value = parameters.get("keep_inventory_confirmed");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("keep_inventory_confirmed must be a boolean");
            confirmed = value.getAsBoolean();
        }
        return new SuicideRequest(method, integer(parameters, "search_radius", 24, 4, 64),
                integer(parameters, "timeout_seconds", 120, 10, 600), confirmed);
    }

    private static int integer(JsonObject parameters, String key, int fallback, int min, int max) {
        // 搜索范围和执行时间都需要正的有界整数值；零不表示无限，数值字符串也不冒充有效预算。
        if (!parameters.has(key)) return fallback;
        var value = parameters.get(key);
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            int number = value.getAsBigDecimal().intValueExact();
            if (number >= min && number <= max) return number;
        } catch (ArithmeticException | NumberFormatException invalid) {
            // 小数、溢出和非数字都按无效预算处理，不能截断成另一份原生操作授权。
        }
        throw new IllegalArgumentException(key + " must be an integer in " + min + ".." + max);
    }

    // auto 允许从三类已观察危险中选取；指定方式时，没找到也不偷偷切到另一种寻死方式。
    public boolean permits(String candidate) { return method.equals("auto") || method.equals(candidate); }
}
