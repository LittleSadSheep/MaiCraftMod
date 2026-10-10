// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

/** 一格机器方块在机器里干什么；看机器时按它分组，接网络时按它找两端。 */
public enum MachineRole {
    /** 动力或能量的来源：水车、风车、蒸汽机、发电机。 */
    POWER_SOURCE,
    /** 把动力传过去：轴、齿轮、齿轮箱、链式传动箱。 */
    TRANSMISSION,
    /** 加工东西：压机、搅拌器、锯、冶金灌注机。 */
    PROCESSING,
    /** 搬东西：传送带、漏斗、导管、机械手。 */
    LOGISTICS,
    /** 存东西：置物台、盆、储罐、ME 驱动器。 */
    STORAGE,
    /** 开关与控制：拉杆接的离合器、红石控制的部件。 */
    CONTROL,
    /** 把网络连起来的线：ME 线缆、能量导管、管道。 */
    CABLE,
    /** 网络的核心：ME 控制器、能量立方这类整张网靠它的方块。 */
    NETWORK_CORE
}
