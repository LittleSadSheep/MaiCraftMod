// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** 换向升级：五段四败触发，先换出发点再轮换扇形，都试过才受阻；全向没有可轮换的方位。 */
class LegEscalationTest {

    private static List<Boolean> outcomes(boolean... succeeded) {
        List<Boolean> list = new ArrayList<>();
        for (boolean value : succeeded) {
            list.add(value);
        }
        return list;
    }

    @Test
    void 最近五段失败满四段才升级() {
        assertEquals(LegEscalation.Level.CARRY_ON,
                LegEscalation.decide(outcomes(true, false, false, false, true), true, 0, 0),
                "窗内只有三败还继续");
        assertEquals(LegEscalation.Level.SHIFT_ORIGIN,
                LegEscalation.decide(outcomes(false, false, false, false, false), true, 0, 0));
    }

    @Test
    void 偶发到达不清零升级的势头() {
        // 触发看观察窗占比，不看严格连续：偶发一次到达不掩盖出发点地形本身不可行。
        assertEquals(LegEscalation.Level.SHIFT_ORIGIN,
                LegEscalation.decide(outcomes(false, false, true, false, false, false, false),
                        true, 0, 0));
    }

    @Test
    void 先换出发点_份额用完再轮换() {
        assertEquals(LegEscalation.Level.SHIFT_ORIGIN,
                LegEscalation.decide(outcomes(false, false, false, false, false), true, 0, 0));
        assertEquals(LegEscalation.Level.SHIFT_ORIGIN,
                LegEscalation.decide(outcomes(false, false, false, false, false), true, 1, 0));
        assertEquals(LegEscalation.Level.ROTATE_SECTOR,
                LegEscalation.decide(outcomes(false, false, false, false, false), true, 2, 0));
    }

    @Test
    void 轮换至多七次_八个方位试遍才受阻() {
        assertEquals(LegEscalation.Level.ROTATE_SECTOR,
                LegEscalation.decide(outcomes(false, false, false, false, false), true, 2, 6));
        assertEquals(LegEscalation.Level.BLOCKED,
                LegEscalation.decide(outcomes(false, false, false, false, false), true, 2, 7));
    }

    @Test
    void 全向跑图没有可轮换的方位_只剩换出发点() {
        assertEquals(LegEscalation.Level.SHIFT_ORIGIN,
                LegEscalation.decide(outcomes(false, false, false, false, false), false, 0, 0));
        assertEquals(LegEscalation.Level.BLOCKED,
                LegEscalation.decide(outcomes(false, false, false, false, false), false, 2, 0));
    }

    @Test
    void 换出发点这段自身失败直接走下一级() {
        assertEquals(LegEscalation.Level.SHIFT_ORIGIN,
                LegEscalation.nextAfterFailedShift(true, 0, 0));
        assertEquals(LegEscalation.Level.ROTATE_SECTOR,
                LegEscalation.nextAfterFailedShift(true, 2, 0));
        assertEquals(LegEscalation.Level.BLOCKED,
                LegEscalation.nextAfterFailedShift(false, 2, 0));
    }
}
