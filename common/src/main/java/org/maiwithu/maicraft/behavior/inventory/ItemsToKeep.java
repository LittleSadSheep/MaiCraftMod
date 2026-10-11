// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

/**
 * 正要用的东西每种留几件：上一个目标刚拿到的、手上这件事正在拿的。路上垫方块、饿了挑吃的都先绕开它们——
 * 挖了三块圆石要做石镐，爬上来时又垫下去；拿了熟海带要交任务，饿了又吃掉，这一趟就白跑了。
 */
@FunctionalInterface
public interface ItemsToKeep {

    /** 什么都不留：没有接上"正要用的东西"时的默认。 */
    ItemsToKeep NOTHING = itemId -> 0;

    /** 这种物品要留几件；不在用的给 0。 */
    int keep(String itemId);
}
