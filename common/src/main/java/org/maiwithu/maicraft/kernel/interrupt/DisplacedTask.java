// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

/**
 * 被挪了岗位的任务的特征接口：临时任务收尾时发现自己离原来的地方太远、走不回去了，
 * 就声明"主任务别在新地方悄悄续上"，并说明停在了哪里。
 *
 * <p>控制循环据此把守着原地的主任务停住等 LLM 决定（接着走回去还是改目标），暂停说明里写上为什么停、停在哪；
 * 去某地、拿东西这类在哪做都一样的主任务从新地点接着做。
 */
public interface DisplacedTask {

    /** 收尾时是否回不到原来的岗位（打点、站位）。 */
    boolean cannotResumeInPlace();

    /** 为什么停、离原来的岗位几格、停在了哪里，一句话；交给事件与主任务的暂停说明。 */
    String stoppedWhere();

    /**
     * 不管主任务在哪做都一样，都要停下等 LLM 决定（被陌生玩家打了：还不还手由 LLM 定）。默认不用。
     */
    default boolean waitsForOrders() {
        return false;
    }
}
