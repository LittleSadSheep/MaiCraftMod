// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

/**
 * 记忆来源：这条记忆是怎么得来的，用的时候据此知道把握有多大。
 *
 * <p>远望看到的可能是别的样子（隔着半座山看到的箱子没开过，里面不知道）；
 * 到访过的至少身体到过、当场核实过；亲手用过的更真的打开过、真的工作过一次。
 * 记忆可能过时，三种来源到现场都要核对，只是核对之前越往后越可信一些。
 */
public enum MemoryOrigin {
    /** 亲眼看到（远望）：只看得见、没到跟前——群系样本、结构线索、岩浆湖这类路上望见的。 */
    SEEN,

    /** 到访：身体到过并核实——脚下群系、站上平台、破出露天、走到东西跟前。 */
    VISITED,

    /** 亲手用过：打开过这个容器，或实际用过这个设施。 */
    USED;

    /** 两条记忆合成一条时，来源取更强的那个：到访的事实不因为后来只看了一眼就退回远望，用过的更不退。 */
    public static MemoryOrigin stronger(MemoryOrigin first, MemoryOrigin second) {
        return first == USED || second == USED ? USED
                : first == VISITED || second == VISITED ? VISITED
                : SEEN;
    }
}
