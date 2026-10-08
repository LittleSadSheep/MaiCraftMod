// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 扫可挖方块的只读接缝：以一处为圆心，找挖掉会掉出想要的东西的方块（矿石、地表的石头这类）。
 * 只报真的会掉的：铁矿石掉的是粗铁，问铁锭时它不算。
 */
public interface ScansMinables {

    /**
     * 半径内挖掉会掉出想要的东西的方块，由近及远。
     *
     * @param wanted       想要的东西，例如 minecraft:coal
     * @param center       从哪里找起
     * @param radiusBlocks 半径，单位格
     */
    List<MinableSpot> minable(WantedItem wanted, WorldPosition center, int radiusBlocks);

    /** 世界上有没有任何方块直接掉出想要的东西；铁锭这类做出来的物品没有，只能合成或烧炼。 */
    boolean anyBlockDrops(WantedItem wanted);
}
