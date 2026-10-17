// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Locale;

/** 施工结束时一格的结局：对的、这次放的、清的、倒的，或没做成的各种原因。 */
public enum CellEnding {
    /** 开始时就对了，没动。 */
    MATCHES,
    /** 这次放好的。 */
    PLACED,
    /** 这次清空的（要求清空的格）。 */
    CLEARED,
    /** 这次倒好的。 */
    POURED,
    /** 结束时仍是别的方块。 */
    WRONG_BLOCK,
    /** 结束时方块对了但点名的属性不对。 */
    WRONG_STATE,
    /** 结束时仍是空的。 */
    MISSING,
    /** 没加载，下不了结论。 */
    UNKNOWN,
    /** 基岩、出界：怎么都做不了。 */
    IMPOSSIBLE,
    /** 受保护的格：没动，等同意。 */
    PROTECTED,
    /** 留给机器放的格。 */
    CHECKED_BY_MACHINE;

    /** 结果里用的小写名字。 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
