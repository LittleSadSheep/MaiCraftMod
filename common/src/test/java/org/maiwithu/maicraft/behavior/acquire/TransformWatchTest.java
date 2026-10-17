// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 料扔进水里以后等多久：够数就收、冒出来后两秒不再多就收、十秒一件没冒就不等。 */
class TransformWatchTest {

    @Test
    void 冒出来的够数了就不等() {
        TransformWatch watch = new TransformWatch(8);
        for (int tick = 0; tick < 70; tick++) assertEquals(TransformWatch.Verdict.WAITING, watch.observe(0));
        assertEquals(TransformWatch.Verdict.WAITING, watch.observe(2));
        assertEquals(TransformWatch.Verdict.ENOUGH, watch.observe(8));
        assertEquals(8, watch.seen());
    }

    @Test
    void 冒出来一些以后两秒不再多就收_每多一件重新计时() {
        TransformWatch watch = new TransformWatch(8);
        watch.observe(2);
        for (int tick = 1; tick < TransformWatch.QUIET_TICKS; tick++) {
            assertEquals(TransformWatch.Verdict.WAITING, watch.observe(2));
        }
        assertEquals(TransformWatch.Verdict.WAITING, watch.observe(4), "又多了两件，重新计时");
        TransformWatch.Verdict last = TransformWatch.Verdict.WAITING;
        for (int tick = 0; tick < TransformWatch.QUIET_TICKS; tick++) last = watch.observe(4);
        assertEquals(TransformWatch.Verdict.SETTLED, last);
        assertEquals(4, watch.seen());
    }

    @Test
    void 十秒一件都没冒出来就不等了() {
        TransformWatch watch = new TransformWatch(2);
        TransformWatch.Verdict last = TransformWatch.Verdict.WAITING;
        for (int tick = 0; tick < TransformWatch.GIVE_UP_TICKS; tick++) last = watch.observe(0);
        assertEquals(TransformWatch.Verdict.NOTHING, last);
        assertEquals(TransformWatch.GIVE_UP_TICKS, watch.waited());
    }
}
