// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import java.util.Objects;

/**
 * 请求里一处写错的地方。
 *
 * @param field    出错的字段路径，例如 {@code goal.parameters.count}
 * @param message  错在哪，用一句话说清楚
 * @param expected 应该怎么写；说不出具体样子时为 null
 */
public record FieldError(String field, String message, String expected) {
    public FieldError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }
}
