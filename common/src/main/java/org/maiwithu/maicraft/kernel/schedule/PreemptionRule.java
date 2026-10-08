// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.schedule;

import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Urgency;

import java.util.Objects;

/**
 * 身体仲裁的唯一规则（docs/design/03 的 M6）：
 * 致命需求打断一切；有害需求打断空闲与忙碌；舒适需求只在空闲时插进来。
 *
 * <p>例：正在挖矿（忙碌）时饿了（舒适）→ 等挖完这一下、到阶段之间（空闲）再吃；
 * 正在悬空搭桥（精细）时被僵尸打（有害）→ 先把这一格放稳；正在往虚空掉（致命）→ 立刻自救。
 */
public final class PreemptionRule {
    private PreemptionRule() {}

    /** 这个需求此刻能不能打断处在这种可打断程度的任务。 */
    public static boolean preempts(Urgency need, Interruptibility task) {
        Objects.requireNonNull(need, "need");
        Objects.requireNonNull(task, "task");
        return switch (need) {
            case LETHAL -> true;
            case HARMFUL -> task != Interruptibility.DELICATE;
            case COMFORT -> task == Interruptibility.FREE;
        };
    }
}
