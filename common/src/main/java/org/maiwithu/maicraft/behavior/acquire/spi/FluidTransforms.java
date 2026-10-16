// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.List;

import net.minecraft.world.level.Level;

/**
 * 一个模组的"扔进流体里会变"的配方表：AE2 的转化配方由它的联动交来。拿东西有一条途径专门走它：
 * 备料、站到水边、把料扔进同一格水、退开、等它变、捡回来，全在玩家行为层，这里只回答有哪些配方。
 */
public interface FluidTransforms {

    /** 这些配方走哪条拿东西的途径（AE2 是 ae2_transform），写进能力说明的 via 可选值。 */
    AcquireVia via();

    /** 此刻这个世界里的配方（客户端同步过来的那一份）；读不到给空表。 */
    List<FluidTransform> recipes(Level level);
}
