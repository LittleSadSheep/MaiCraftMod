// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;

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
 * @param network 网络存储（身上的无线终端）；没有联动时给空
 * @param offhand 副手：整理背包时把副手的东西放回背包一起存；没接上为 null，副手不管
 */
record DepositServices(
        SpotsContainers spots,
        StoresInContainer.Parts storing,
        DepositSeams.FindsPlaces places,
        DepositSeams.DigsLid digs,
        ReadsItemTags tags,
        Supplier<Optional<NetworkStorage>> network,
        DepositSeams.ClearsOffhand offhand) {

    /** 没有网络存储、不管副手。 */
    DepositServices(SpotsContainers spots, StoresInContainer.Parts storing, DepositSeams.FindsPlaces places,
            DepositSeams.DigsLid digs, ReadsItemTags tags) {
        this(spots, storing, places, digs, tags, Optional::empty, null);
    }

    /** 不管副手。 */
    DepositServices(SpotsContainers spots, StoresInContainer.Parts storing, DepositSeams.FindsPlaces places,
            DepositSeams.DigsLid digs, ReadsItemTags tags, Supplier<Optional<NetworkStorage>> network) {
        this(spots, storing, places, digs, tags, network, null);
    }

    DepositServices {
        if (spots == null) throw new IllegalArgumentException("找容器的接缝不能为空");
        if (storing == null) throw new IllegalArgumentException("存放的现场部件不能为空");
        if (places == null) throw new IllegalArgumentException("找位置的接缝不能为空");
        if (network == null) network = Optional::empty;
    }
}
