// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.outcome;

import java.util.Objects;

/**
 * 尝试过的一种办法及其结果，例如"从门口那一侧靠近床：被墙挡住看不见床头"。
 * 回执里只放摘要，不放逐刻日志。
 *
 * @param strategy 用了什么办法
 * @param result   结果如何
 */
public record Attempt(String strategy, String result) {
    public Attempt {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(result, "result");
    }
}
