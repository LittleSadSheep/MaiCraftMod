// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

/**
 * 记忆种类：一条世界记忆记的是哪类东西。
 *
 * <p>种类决定查询怎么用它：找箱子取东西查容器，找合成台查工作站，找矿、找木头查产地线索。
 */
public enum MemoryKind {
    /** 容器：箱子、木桶这类能装东西的方块；记位置、方块类型，开过的话还记里面有什么。 */
    CONTAINER,

    /** 工作站：工作台、熔炉这类用一次就知道能用的设施；记位置与方块类型。 */
    WORKSTATION,

    /** 产地：在哪里大概能找到什么的线索，例如"这一片见过煤矿"；只有大概位置，到现场要自己再找。 */
    SITE,
}
