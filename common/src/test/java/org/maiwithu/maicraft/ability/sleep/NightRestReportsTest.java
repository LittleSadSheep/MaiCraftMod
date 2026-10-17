// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 夜休上报的节流：同一夜同一句"没睡成"只报第一次，换原因、换夜再说；睡成的每次都报。 */
class NightRestReportsTest {

    @Test
    void 同一夜同一句失败只报第一次() {
        NightRestReports reports = new NightRestReports(() -> 7);
        String failure = "天黑了想去睡，没睡成：32 格内没有能睡的床（附近扫过了，没有看到床块）";
        assertTrue(reports.shouldAnnounce(failure), "第一次要报");
        assertFalse(reports.shouldAnnounce(failure), "同一夜同一句不再报");
        assertFalse(reports.shouldAnnounce(failure), "再插任务还是不报");
    }

    @Test
    void 换了原因再说一次() {
        NightRestReports reports = new NightRestReports(() -> 7);
        reports.shouldAnnounce("天黑了想去睡，没睡成：扫到 2 张床，都睡不上：2 张床边有怪");
        assertTrue(reports.shouldAnnounce("天黑了想去睡，没睡成：附近扫过了，没有看到床块"), "换原因要报");
    }

    @Test
    void 换了夜再说一次() {
        long[] day = {7};
        NightRestReports reports = new NightRestReports(() -> day[0]);
        String failure = "天黑了想去睡，没睡成：32 格内没有能睡的床";
        assertTrue(reports.shouldAnnounce(failure));
        day[0] = 8;
        assertTrue(reports.shouldAnnounce(failure), "新的一夜从头报");
    }

    @Test
    void 睡成的与交代床的每次都报() {
        NightRestReports reports = new NightRestReports(() -> 7);
        String slept = "天黑了，自己去床上睡了一觉，一觉睡到了天亮；睡了一夜，醒来到回了原来的位置";
        assertTrue(reports.shouldAnnounce(slept));
        assertTrue(reports.shouldAnnounce(slept), "睡成的时间跳了、人挪了，每次都要说");
        assertTrue(reports.shouldAnnounce("天黑了放下过一张床：自己放的床留在 4, 64, 2，重生点也改到了这张床"));
    }
}
