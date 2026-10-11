// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

import com.google.gson.JsonElement;

/**
 * 收到的东西写成了什么样：类型加一小段原文，报写法错误时一并交回去，对方一眼看清自己写成了什么，
 * 不用对着"应该是列表"猜自己哪里不像列表（把 steps 写成 {"item": [...]} 连错五次就是这么来的）。
 */
public final class ReceivedShape {

    /** 原文最多留这么多个字符，再长截断。 */
    private static final int MAX_CHARS = 80;

    private ReceivedShape() {}

    /** 例如：对象 {"item":["minecraft:torch"]}、文字 "here"、数字 6。 */
    public static String of(JsonElement element) {
        if (element == null || element.isJsonNull()) return "null";
        String kind = element.isJsonObject() ? "对象" : element.isJsonArray() ? "列表"
                : element.getAsJsonPrimitive().isString() ? "文字"
                : element.getAsJsonPrimitive().isNumber() ? "数字" : "布尔值";
        String raw = element.toString();
        if (raw.length() > MAX_CHARS) raw = raw.substring(0, MAX_CHARS - 1) + "…";
        return kind + " " + raw;
    }
}
