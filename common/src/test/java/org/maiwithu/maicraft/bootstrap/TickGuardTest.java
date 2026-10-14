// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 每刻子系统的兜底：一个出错只跳过它这一刻，别的照常推进，下一刻它还会再试。 */
class TickGuardTest {

    @Test
    void 一个子系统出错不拦后面的_下一刻照样再试() {
        TickGuard guard = new TickGuard();
        int[] ran = new int[2];
        boolean[] broken = {true};
        for (int tick = 0; tick < 3; tick++) {
            // 感知读世界记忆抛异常的那几刻：只跳过感知，后面的提醒照常推进。
            boolean observed = guard.run("世界扫描", () -> {
                if (broken[0]) throw new IllegalStateException("world_memory_read_failed");
                ran[0]++;
            });
            assertFalse(observed);
            assertTrue(guard.run("提醒", () -> ran[1]++));
        }
        assertEquals(0, ran[0]);
        assertEquals(3, ran[1]);
        // 毛病好了：下一刻感知接着跑。
        broken[0] = false;
        assertTrue(guard.run("世界扫描", () -> ran[0]++));
        assertEquals(1, ran[0]);
    }
}
