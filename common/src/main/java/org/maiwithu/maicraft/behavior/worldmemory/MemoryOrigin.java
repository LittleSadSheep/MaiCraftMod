// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

/**
 * 记忆来源：这条记忆是怎么得来的，用的时候据此知道把握有多大。
 *
 * <p>亲眼看到的可能是别的样子（隔着半格看到的箱子没开过，里面不知道）；
 * 亲手用过的至少真的打开过、真的工作过一次。记忆可能过时，两种来源到现场都要核对，
 * 只是核对之前"亲手用过"更可信一些。
 */
public enum MemoryOrigin {
    /** 亲眼看到：看见过这个方块或这个地方，但没有实际操作过。 */
    SEEN,

    /** 亲手用过：打开过这个容器，或实际用过这个设施。 */
    USED;

    /** 两条记忆合成一条时，来源取更强的那个：亲手用过的事实不因为后来只看了一眼就退回去。 */
    public static MemoryOrigin stronger(MemoryOrigin first, MemoryOrigin second) {
        return first == USED || second == USED ? USED : SEEN;
    }
}
