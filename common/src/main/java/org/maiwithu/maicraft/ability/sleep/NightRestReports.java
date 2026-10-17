// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 夜休给宿主的上报要不要发：睡成、床留在哪，每次都说；同一夜同一句"没睡成"只报第一次，
 * 换了原因（句子变了）或换了夜再报。夜休任务来来去去，节流的记忆跟这个对象走、跨任务活着。
 *
 * <p>实机 10-16 一晚把同一句"天黑了想去睡，没睡成"灌了 53 条，70 秒里 7 条，
 * 挤占主播的叙事窗口，也说不出任何新信息。
 */
public final class NightRestReports {

    /** 夜休失败的收场句都以这句开头；只有这一类要节流，睡成的与交代床的每次都报。 */
    private static final String FAILURE_PREFIX = "天黑了想去睡，没睡成";

    private final LongSupplier dayIndex;
    private String lastReported;
    private long lastReportedDay = -1;

    public NightRestReports(LongSupplier dayIndex) {
        this.dayIndex = Objects.requireNonNull(dayIndex, "dayIndex");
    }

    /** 这条收尾说明发不发：失败句同一夜同一句只发第一次，其余照发。 */
    public boolean shouldAnnounce(String note) {
        if (note == null || !note.startsWith(FAILURE_PREFIX)) {
            return note != null;
        }
        long day = dayIndex.getAsLong();
        if (note.equals(lastReported) && day == lastReportedDay) {
            return false;
        }
        lastReported = note;
        lastReportedDay = day;
        return true;
    }
}
