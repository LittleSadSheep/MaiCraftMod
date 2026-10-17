// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * 机械动力物流网络（6.0）的模组读写接缝：附近的库存请求器（旁边坐没坐着库存管理员、上次用过的地址、
 * 同步过来的现货）、收包裹的端口（青蛙港、邮筒：地址、收不收、里面的包裹）、一件包裹里装着什么，
 * 以及请求界面开着时照"发送"那样下单。只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里。
 */
public interface CreateLogistics {

    /** center 附近 radius 格内已加载的库存请求器。 */
    List<Ticker> tickers(ClientLevel level, BlockPos center, int radius);

    /** center 附近 radius 格内已加载的包裹端口。 */
    List<Pickup> pickups(ClientLevel level, BlockPos center, int radius);

    /** 这件是不是包裹：寄往哪、里面装着什么（物品 ID → 件数）；不是为空。 */
    Optional<Parcel> parcel(ItemStack stack);

    /** 现在开着的是不是库存请求器的请求界面。 */
    boolean requestMenuOpen(LocalPlayer player);

    /** 请求界面开着时照"发送"那样下单：清单（物品 ID → 件数）寄往 address。 */
    void order(BlockPos ticker, Map<String, Integer> items, String address);

    /**
     * 一台库存请求器。
     *
     * @param pos              在哪
     * @param keeper           旁边坐着的库存管理员（实体编号）；没有为空
     * @param lastAddress      上次在这里下单用的地址；没有为空串
     * @param stock            同步过来的现货（物品 ID → 件数），合成预览不算
     * @param ticksSinceUpdate 现货摘要多少刻前同步的
     */
    record Ticker(BlockPos pos, OptionalInt keeper, String lastAddress, Map<String, Integer> stock, int ticksSinceUpdate) {
        public Ticker {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            stock = Map.copyOf(stock);
        }
    }

    /**
     * 一个取货口：包裹端口（青蛙港、邮筒）。
     *
     * @param pos      在哪
     * @param address  写着的地址；没写为空串
     * @param accepts  开着"收包裹"
     * @param packages 十八格里的东西（空格是空叠）
     */
    record Pickup(BlockPos pos, String address, boolean accepts, List<ItemStack> packages) {
        public Pickup {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            packages = List.copyOf(packages);
        }
    }

    /** 一件包裹：寄往哪、装着什么（物品 ID → 件数）。 */
    record Parcel(String address, Map<String, Integer> contents) {
        public Parcel {
            contents = Map.copyOf(contents);
        }
    }
}
