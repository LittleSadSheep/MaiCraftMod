// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import org.maiwithu.maicraft.behavior.inventory.SpotsContainers;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;

/**
 * 存东西能力的协作服务：启动时创建并登记从构造函数递进来。
 *
 * <p>找容器、打开容器、找位置缺一样都存不进去，不许为 null；挖盖子、标签判断、世界记忆是可选接缝，
 * 没接上时按接缝处理。
 *
 * @param spots  找容器（现场扫描与世界记忆合并，归属与盖子按游戏事实读好）
 * @param opens  打开一只容器
 * @param places 目标对象落在哪
 * @param digs   挖开压住盖子的方块
 * @param tags   物品挂着哪些标签（玩家行为层的读法）；没接上时为 null，按什么标签都不挂算
 * @param memory 世界记忆；存完把容器里有什么记下来
 */
record DepositServices(
        SpotsContainers spots,
        DepositSeams.OpensMenus opens,
        DepositSeams.FindsPlaces places,
        DepositSeams.DigsLid digs,
        ReadsItemTags tags,
        WorldMemory memory) {

    DepositServices {
        if (spots == null) throw new IllegalArgumentException("找容器的接缝不能为空");
        if (opens == null) throw new IllegalArgumentException("打开容器的接缝不能为空");
        if (places == null) throw new IllegalArgumentException("找位置的接缝不能为空");
    }
}
