// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;

/** 测试替身：背包就是一张可以改的格子表，来源的执行直接往里放，好核对引擎重新清点看到的现场。 */
final class FakeBackpack implements BackpackView {
    final List<BackpackStack> stacks = new ArrayList<>();
    private final int capacity;

    FakeBackpack(int capacity, BackpackStack... initial) {
        this.capacity = capacity;
        stacks.addAll(List.of(initial));
    }

    @Override public List<BackpackStack> stacks() {
        return List.copyOf(stacks);
    }

    @Override public int usedSlots() {
        return stacks.size();
    }

    @Override public int totalSlots() {
        return capacity;
    }

    /** 往背包里放进几件东西，占一格（数量上限按最大堆叠截断）。 */
    void add(String itemId, int count) {
        stacks.add(new BackpackStack(itemId, count, 64, false, false, false, false));
    }

    /** 一件装备（工具）放进背包。 */
    void addGear(String itemId) {
        stacks.add(new BackpackStack(itemId, 1, 1, true, false, false, false));
    }
}
