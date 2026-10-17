// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import java.util.Objects;

/**
 * 请求里一处写错的地方。
 *
 * @param field    出错的字段路径，例如 {@code goal.parameters.count}
 * @param message  错在哪，用一句话说清楚
 * @param expected 应该怎么写；说不出具体样子时为 null
 * @param received 收到的是什么样（类型加一小段原文）；缺字段这类没收到东西的为 null
 */
public record FieldError(String field, String message, String expected, String received) {
    public FieldError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }

    public FieldError(String field, String message, String expected) {
        this(field, message, expected, null);
    }
}
