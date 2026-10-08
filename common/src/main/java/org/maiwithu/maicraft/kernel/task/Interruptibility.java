// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

/**
 * 任务此刻能不能被打断（docs/design/03 的 M6）。由任务自己按身体处境汇报，仲裁据此决定需求能否插进来。
 *
 * <p>它取代了 v1 在 Task 上逐个补的五个布尔开关：贴边退避、紧急救援、连续驾驶、让位、寻死时压制反射。
 */
public enum Interruptibility {
    /** 步骤之间、手上没有进行中的动作：吃一口、去睡觉这类舒适需求可以插进来。 */
    FREE,
    /** 正常干活：只有有害或致命的需求能打断。 */
    BUSY,
    /** 连续驾驶、等待原生确认、悬空或贴边：只有致命需求能打断，打断本身可能更危险。 */
    DELICATE
}
