// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

/** 核对时一格的结论：直接回答"这一格现在怎样了"。 */
public enum CellState {
    /** 已符合要求：点名的属性逐项一致；没点名时同方块即可，立式与墙式的同一种物品互认。 */
    MATCHES,
    /** 方块不对：格里是别的方块。 */
    WRONG_BLOCK,
    /** 方块对、点名的属性不对。 */
    WRONG_STATE,
    /** 该有东西的格还是空的，或只有可替换的草、水。 */
    MISSING,
    /** 那一格没加载，不猜。 */
    UNKNOWN,
    /** 留给机器的格，由机器按最终结构核对。 */
    CHECKED_BY_MACHINE,
    /** 范围内没声明的格里有东西；只作信息，不算失败。 */
    EXTRA;

    /** 这一格还要不要动手：没加载的和留给机器的都不归施工引擎。 */
    public boolean needsWork() {
        return this == WRONG_BLOCK || this == WRONG_STATE || this == MISSING;
    }
}
