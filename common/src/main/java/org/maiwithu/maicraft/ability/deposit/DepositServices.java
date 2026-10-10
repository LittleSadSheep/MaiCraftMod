// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import org.maiwithu.maicraft.behavior.inventory.SpotsContainers;
import org.maiwithu.maicraft.behavior.inventory.StoresInContainer;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;

/**
 * 存东西能力的协作服务：启动时创建并登记从构造函数递进来。
 *
 * <p>找容器、存放、找位置缺一样都存不进去，不许为 null；挖盖子、标签判断是可选接缝，
 * 没接上时按接缝处理。
 *
 * @param spots   找容器（现场扫描与世界记忆合并，归属与盖子按游戏事实读好）
 * @param storing 存放要用的现场部件（打开容器、世界记忆），与腾地方共用一份
 * @param places  目标对象落在哪
 * @param digs    挖开压住盖子的方块
 * @param tags    物品挂着哪些标签（玩家行为层的读法）；没接上时为 null，按什么标签都不挂算
 */
record DepositServices(
        SpotsContainers spots,
        StoresInContainer.Parts storing,
        DepositSeams.FindsPlaces places,
        DepositSeams.DigsLid digs,
        ReadsItemTags tags) {

    DepositServices {
        if (spots == null) throw new IllegalArgumentException("找容器的接缝不能为空");
        if (storing == null) throw new IllegalArgumentException("存放的现场部件不能为空");
        if (places == null) throw new IllegalArgumentException("找位置的接缝不能为空");
    }
}
