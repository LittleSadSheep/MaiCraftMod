// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

/** 这一格由谁来放：施工引擎自己放，还是留给机器用模组自己的安装方式放。 */
public enum PlacedBy {
    /** 施工引擎放：普通方块、清空、倒桶都是它。 */
    BUILDER,
    /** 留给机器：传送带、链条这类只能用模组自己的方式成型的格；引擎不放不清障，仍算进场地范围与保护，核对时由机器按最终结构核对。 */
    MACHINE
}
