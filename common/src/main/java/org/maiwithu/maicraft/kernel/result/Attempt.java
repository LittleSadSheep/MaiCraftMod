// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.result;

import java.util.Objects;

/**
 * 试过的一个办法及其结果，例如"从门口那一侧靠近床：被墙挡住，看不见床头"。
 * 结果里只放这样的摘要，不放逐刻日志。
 *
 * @param tried  试了什么办法
 * @param result 结果如何
 */
public record Attempt(String tried, String result) {
    public Attempt {
        Objects.requireNonNull(tried, "tried");
        Objects.requireNonNull(result, "result");
    }
}
