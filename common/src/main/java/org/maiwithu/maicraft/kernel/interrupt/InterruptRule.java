// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Urgency;

import java.util.Objects;

/**
 * 打断规则，全仓只有这一条：
 * 必须立刻处理的打断一切；需要尽快处理的，在停下不安全时先等手上这一下做完；找空当处理的，只在两个动作之间插进来。
 *
 * <p>例：正在挖矿时饿了（找空当）→ 挖完这一下、到两个动作之间再吃；
 * 正在悬空搭桥时被僵尸打（尽快）→ 先把这一格放稳；正在往虚空掉（立刻）→ 马上自救。
 */
public final class InterruptRule {
    private InterruptRule() {}

    /** 这么急的生存需求，此刻能不能打断处在这种状态的任务。 */
    public static boolean canInterrupt(Urgency need, Interruptibility task) {
        Objects.requireNonNull(need, "need");
        Objects.requireNonNull(task, "task");
        return switch (need) {
            case NOW -> true;
            case SOON -> task != Interruptibility.UNSAFE_TO_STOP;
            case LATER -> task == Interruptibility.BETWEEN_ACTIONS;
        };
    }
}
