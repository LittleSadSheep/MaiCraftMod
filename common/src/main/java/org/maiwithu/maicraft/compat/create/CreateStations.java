// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * 读机械动力"看得见的东西"的模组读写接缝：置物台台面上放着什么、工作盆里装着什么、传送带上在送什么、
 * 库存请求器上次读到的现货。这些都是 Create 画在世界里、或同步给客户端显示的，玩家看得见，不算透视。
 * 只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里，测试里用替身。
 */
public interface CreateStations {

    /** 置物台（含弹射置物台）上的东西：台面那一叠，加上加工多出来的产物；不是置物台、没加载给空。 */
    Optional<List<ItemStack>> depotItems(ClientLevel level, BlockPos at);

    /** 工作盆里的物品：待加工的与做好的都算；不是工作盆、没加载给空。 */
    Optional<List<ItemStack>> basinItems(ClientLevel level, BlockPos at);

    /** 这一格所在的整条传送带上正在送的东西；不是传送带、没加载给空。 */
    Optional<List<ItemStack>> beltItems(ClientLevel level, BlockPos at);

    /** 库存请求器上次读到的网络现货；不是库存请求器、没加载给空；还没读过时是空表加上"多少刻前"。 */
    Optional<StockSnapshot> stock(ClientLevel level, BlockPos at);

    /**
     * 库存请求器的一份现货摘要。只有现货：Create 在界面里给出的"能合成多少"的预览不算。
     *
     * @param entries         每种东西有几件
     * @param ticksSinceUpdate 这份摘要是多少刻前收到的
     */
    record StockSnapshot(List<StockEntry> entries, int ticksSinceUpdate) {
        public StockSnapshot {
            entries = List.copyOf(entries);
        }
    }

    /** 现货里的一种东西：物品 ID 与件数（件数可以超过一组）。 */
    record StockEntry(String itemId, long count) {
        public StockEntry {
            Objects.requireNonNull(itemId, "itemId");
        }
    }
}
