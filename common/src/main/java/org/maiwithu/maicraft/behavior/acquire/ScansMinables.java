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

    /** 最近一次找可挖方块有没有把附近扫完：方块索引分刻建，没扫完时空结果不等于没有。 */
    boolean scanComplete();

    /** 想要的东西是不是埋在脚下的石头掉的（圆石、石质工具的材料这类）：谁都知道往下挖几格就有，看不见也能去挖。 */
    boolean buriedUnderfoot(WantedItem wanted);

    /** 挖掉这种方块会不会掉想要的东西：往下挖楼梯时数挖到了几格用。 */
    boolean dropsWanted(WantedItem wanted, String blockTypeId);
}
