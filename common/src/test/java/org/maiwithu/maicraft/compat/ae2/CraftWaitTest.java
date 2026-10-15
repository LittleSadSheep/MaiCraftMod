// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 下单后在终端前等成品：进来一批重新计时，两分钟没新的、满五分钟、AE2 报这单结束两秒后都不再等。 */
class CraftWaitTest {

    // 连着等 ticks 刻都没有新成品，返回最后一刻的结论。
    private static CraftWait.Verdict idle(CraftWait wait, int ticks, int taken, boolean pending) {
        CraftWait.Verdict last = CraftWait.Verdict.WAIT;
        for (int i = 0; i < ticks; i++) last = wait.tick(taken, pending);
        return last;
    }

    @Test
    void 成品陆续进来就重新计时_两分钟没新的才收场() {
        CraftWait wait = new CraftWait(20, true);
        assertEquals(CraftWait.Verdict.WAIT, idle(wait, CraftWait.IDLE_TICKS - 1, 20, true));
        assertEquals(CraftWait.Verdict.WAIT, wait.tick(50, true), "进来一批，重新计时");
        assertEquals(CraftWait.Verdict.WAIT, idle(wait, CraftWait.IDLE_TICKS - 1, 50, true));
        assertEquals(CraftWait.Verdict.IDLE, wait.tick(50, true));
        assertEquals(30, wait.arrived(), "开始等以后到的才算到了几件，先取走的现货不算");
    }

    @Test
    void 一直有成品进来也最多等五分钟() {
        CraftWait wait = new CraftWait(0, true);
        CraftWait.Verdict last = CraftWait.Verdict.WAIT;
        int taken = 0;
        int ticks = 0;
        while (last == CraftWait.Verdict.WAIT && ticks < CraftWait.TOTAL_TICKS * 2) {
            ticks++;
            if (ticks % 100 == 0) taken++;
            last = wait.tick(taken, true);
        }
        assertEquals(CraftWait.Verdict.TOO_LONG, last);
        assertEquals(CraftWait.TOTAL_TICKS, ticks);
    }

    @Test
    void AE2报这单结束_过两秒还没有更多才收场() {
        CraftWait wait = new CraftWait(0, false);
        assertEquals(CraftWait.Verdict.WAIT, idle(wait, 5, 0, true));
        assertEquals(CraftWait.Verdict.WAIT, idle(wait, CraftWait.ENDED_SETTLE_TICKS, 0, false),
                "刚结束时存货可能还没同步过来，先等两秒");
        assertEquals(CraftWait.Verdict.ENDED, wait.tick(0, false));
    }

    @Test
    void 结束后又取到一批_重新数两秒() {
        CraftWait wait = new CraftWait(0, true);
        idle(wait, CraftWait.ENDED_SETTLE_TICKS - 5, 0, false);
        assertEquals(CraftWait.Verdict.WAIT, wait.tick(16, false), "最后一批进来了，这一刻算两秒里的第一刻");
        assertEquals(CraftWait.Verdict.WAIT, idle(wait, CraftWait.ENDED_SETTLE_TICKS - 1, 16, false));
        assertEquals(CraftWait.Verdict.ENDED, wait.tick(16, false));
        assertEquals(16, wait.arrived());
    }

    @Test
    void 刚下单还没收到开工_不当成结束() {
        CraftWait wait = new CraftWait(0, false);
        assertEquals(CraftWait.Verdict.WAIT, idle(wait, CraftWait.ENDED_SETTLE_TICKS * 10, 0, false),
                "服务端的开工还没到，不能当成这单已经结束");
        assertEquals(CraftWait.Verdict.IDLE, idle(wait, CraftWait.IDLE_TICKS, 0, false), "只按两分钟没新的收场");
    }
}
