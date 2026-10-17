// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 工作槽搬运计划：从背包侧的一格往工作槽搬恰好几件的点击序列（纯计算）。
 *
 * <p>整堆都搬用两次左键；只搬一部分时左键拿起、右键一件一件放进工作槽、左键把余量放回——
 * 绝不整堆快移超额。收尾的取回走整堆快速移动，不在这份计划里。
 */
final class WorkslotMoves {

    /** 一次点击：普通格的左键（button 0）拿起或放下、右键（button 1）放一件。 */
    record Click(int slotId, int button) {}

    private WorkslotMoves() {}

    /**
     * 从 fromSlot 往 toSlot 搬 count 件的点击序列；fromCount 是那格现在的件数。
     * count 与整堆相等时直接整堆放过去，少一件都不行就逐件放。
     */
    static Deque<Click> load(int fromSlot, int toSlot, int count, int fromCount) {
        Deque<Click> clicks = new ArrayDeque<>();
        clicks.add(new Click(fromSlot, 0));
        if (count >= fromCount) {
            clicks.add(new Click(toSlot, 0));
            return clicks;
        }
        for (int i = 0; i < count; i++) {
            clicks.add(new Click(toSlot, 1));
        }
        clicks.add(new Click(fromSlot, 0));
        return clicks;
    }
}
