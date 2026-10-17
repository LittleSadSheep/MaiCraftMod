// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 被埋判断：卡在实心方块里正在窒息，一律立刻处理。 */
class BuriedDangerTest {

    @Test
    void stuckInSolidBlockIsImmediate() {
        // 挖矿挖穿头顶、沙子塌下来把人埋了：马上刨出来。
        assertEquals(Urgency.NOW, BuriedDanger.assess(() -> true));
    }

    @Test
    void notBuriedIsNotUrgent() {
        assertNull(BuriedDanger.assess(() -> false));
    }
}
