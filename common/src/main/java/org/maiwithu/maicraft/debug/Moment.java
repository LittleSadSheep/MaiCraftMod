// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.maiwithu.maicraft.kernel.task.TaskProgress;

/**
 * 排版这一刻手上的东西：现状快照、各状态第一次被看到的时刻、此刻的现实时间与时区。
 * 各段从这里取值、算"已等多久"，自己不碰时钟，离线测试给什么就是什么。
 */
record Moment(StatusSnapshot status, FirstSeenTimes times, long nowMillis, ZoneId zone) {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 从第一次看到它起过了多久（毫秒）。 */
    long ageMillis(String key) {
        return Math.max(0, nowMillis - times.seenAt(key, nowMillis));
    }

    /** 现实时刻写成时:分:秒，方便和日志对照。 */
    String clock(long millis) {
        return CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /** 手上的目标；没有时为 null。 */
    StatusSnapshot.GoalLine mainGoal() {
        return status.goals().main();
    }

    /** 控制循环。 */
    StatusSnapshot.Loop loop() {
        return status.loop();
    }

    /** 此刻在推进的那个任务的进展；没有时为 null。 */
    TaskProgress progress() {
        return status.loop().progress();
    }

    /** 运行栈最上面那一层，也就是角色此刻在听的任务；栈空时为 null。 */
    StatusSnapshot.Layer topLayer() {
        List<StatusSnapshot.Layer> layers = status.loop().layers();
        return layers.isEmpty() ? null : layers.getLast();
    }
}
