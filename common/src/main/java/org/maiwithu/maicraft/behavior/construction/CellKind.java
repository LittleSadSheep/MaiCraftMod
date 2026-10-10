// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

/** 一格该怎么做：放一个方块、把这一格清空，还是倒一桶水或岩浆。 */
public enum CellKind {
    /** 放一个方块；格里是别的东西就先拆再放。 */
    BLOCK,
    /** 清空这一格；蓝图没写的格子不是这种，不会被清。 */
    AIR,
    /** 倒一桶：要求这一格最后是水源或岩浆源，实心格都放完了再倒。 */
    FLUID_SOURCE
}
