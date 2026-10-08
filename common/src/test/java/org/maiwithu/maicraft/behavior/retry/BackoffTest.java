// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 退避规则：等待刻数按失败次数逐次加倍，封顶为构造时给的上限；不真实等待。 */
class BackoffTest {

    @Test
    void 第一次重试等基数() {
        assertEquals(20L, new Backoff(20, 200).waitTicks(1));
    }

    @Test
    void 等待逐次加倍() {
        Backoff backoff = new Backoff(20, 1000);
        assertEquals(40L, backoff.waitTicks(2));
        assertEquals(80L, backoff.waitTicks(3));
        assertEquals(160L, backoff.waitTicks(4));
    }

    @Test
    void 等待封顶不超过上限() {
        Backoff backoff = new Backoff(20, 100);
        assertEquals(100L, backoff.waitTicks(4));
        assertEquals(100L, backoff.waitTicks(20));
    }

    @Test
    void 基数必须为正且上限不小于基数() {
        assertThrows(IllegalArgumentException.class, () -> new Backoff(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new Backoff(200, 100));
    }
}
