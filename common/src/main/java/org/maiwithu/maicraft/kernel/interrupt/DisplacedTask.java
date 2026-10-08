// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

/**
 * 被挪了岗位的任务的特征接口：临时任务收尾时发现自己离原来的地方太远、走不回去了，
 * 就声明"主任务别在新地方悄悄续上"，并说明停在了哪里。
 *
 * <p>控制循环据此把主任务停住等 LLM 决定（接着走回去还是改目标），事件里带上位置。
 */
public interface DisplacedTask {

    /** 收尾时是否回不到原来的岗位（打点、站位）。 */
    boolean cannotResumeInPlace();

    /** 停在了哪里，一句话；交给事件与主任务的暂停说明。 */
    String stoppedWhere();
}
