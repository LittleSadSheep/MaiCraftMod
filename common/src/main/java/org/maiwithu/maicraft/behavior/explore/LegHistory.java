// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 走行段的账：观察窗（最近几段的成败）、总数与失败数、换出发点与轮换已用的次数、
 * 生效过的扇形方位历史，以及有没有到过航点、核实到的位置与收尾原因。
 * 探索任务读写它，受阻的说明与结果细节都从这里出。
 */
final class LegHistory {

    final Deque<Boolean> window = new ArrayDeque<>();
    final List<String> sectorNotes = new ArrayList<>();
    int total;
    int failures;
    int originShifts;
    int rotations;
    boolean reachedAnyWaypoint;
    WorldPosition reached;
    ExploreDetails.StopReason stopReason;

    /** 记一段的成败；窗口只留最近几段，成败都算——偶发到达不清零连续失败的势头。 */
    void record(boolean succeeded) {
        total++;
        if (succeeded) {
            reachedAnyWaypoint = true;
        } else {
            failures++;
        }
        window.add(succeeded);
        if (window.size() > LegEscalation.WINDOW) {
            window.removeFirst();
        }
    }

    List<Boolean> windowSnapshot() {
        return List.copyOf(window);
    }
}
