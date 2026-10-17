// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/**
 * 任务此刻能不能被打断。由任务按角色此刻的处境自己报告，打断规则据此决定生存需求能不能插进来。
 *
 * <p>它代替了在任务上逐个加布尔开关的做法（贴边时别退、紧急救援、连续驾驶、主动让出控制、寻死时不自救……）。
 */
public enum Interruptibility {
    /** 正处在两个动作之间，手上没有进行中的动作：吃一口、去睡觉这类不急的事可以趁这个空当插进来。 */
    BETWEEN_ACTIONS,
    /** 正常干活：只有需要尽快或立刻处理的事能打断。 */
    WORKING,
    /** 现在停下不安全：悬空、贴边、驾驶中、正在等游戏确认交互结果。只有必须立刻处理的事能打断，打断本身可能更危险。 */
    UNSAFE_TO_STOP
}
