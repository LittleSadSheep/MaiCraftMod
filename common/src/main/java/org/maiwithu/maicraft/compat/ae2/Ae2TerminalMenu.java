// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * ME 终端界面的模组读写接缝：点开的界面是不是这台终端的、连没连上网络、网络存货里有什么，
 * 以及向服务端发取货操作。网络里的货不在界面的格子里，是 AE2 自己同步过来的一份存货表；
 * 取货也不是原版的点格子，而是 AE2 自己的操作包。只用 Minecraft 与 Java 的类型描述，测试里用替身。
 */
public interface Ae2TerminalMenu {

    /** 这份界面是不是装在这一格这一面的那台 ME 终端打开的；点开之后先核对，免得在别的界面上点。 */
    boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block, Direction side);

    /**
     * 这份界面是不是身上的无线终端打开的（背后是拿在手里的那件物品，不是装在线缆上的面板）。
     * 拿出无线终端点开之后先核对，免得在别的界面上点。
     */
    default boolean wirelessMenu(AbstractContainerMenu menu) {
        return false;
    }

    /** 终端界面显示网络连没连上（没电、没频道都算没连上）；不是 ME 终端的界面时给空。 */
    Optional<Boolean> linked(AbstractContainerMenu menu);

    /** 没连上时界面上写的原因（超出无线范围、没电、没频道）；读不到为空。 */
    default Optional<String> unlinkedWhy(AbstractContainerMenu menu) {
        return Optional.empty();
    }

    /**
     * 终端界面里已经同步过来的网络存货，只列物品，流体等别的种类不列。
     * 存货表由终端的界面画面建立：界面还没画出来、或不是 ME 终端的界面时给空。
     */
    Optional<List<StockEntry>> stock(AbstractContainerMenu menu);

    /** 向服务端发"Shift 点这一条"：这种东西最多一组直接进背包，进哪一格由游戏挑。 */
    void takeStack(AbstractContainerMenu menu, long serial);

    /** 向服务端发"取一件到光标上"：光标上已有同一种东西时再叠一件，满一组就不再加。 */
    void takeOne(AbstractContainerMenu menu, long serial);

    /**
     * 光标空着时点流体那一条装一桶：网络里有空桶就拿一只装满，直接放进背包（Shift 点流体）。
     * 网络里没有空桶时什么都不发生，调用方按背包前后对照会看到没动静。
     */
    default void fillBucket(AbstractContainerMenu menu, long serial) {
    }

    /** 向服务端发"把光标上的东西放回网络"：等于在终端的网络格子空白处点一下；网络放不下的留在光标上。 */
    void putBack(AbstractContainerMenu menu);

    /** 向服务端发"下单合成这一条"：网络里有样板能做它时，服务端打开填数量的界面。不是 ME 终端的界面时什么都不发。 */
    default void requestCraft(AbstractContainerMenu menu, long serial) {
    }

    /** 这份界面是不是填合成数量的界面。 */
    default boolean craftAmountMenu(AbstractContainerMenu menu) {
        return false;
    }

    /** 这份界面是不是合成确认界面（算料、选合成 CPU 的那一页）。 */
    default boolean craftConfirmMenu(AbstractContainerMenu menu) {
        return false;
    }

    /** 在填数量的界面里定下数量，请网络算料、算完能做就直接开工；缺料时停在确认界面列出缺什么。 */
    default void confirmCraft(AbstractContainerMenu menu, int amount) {
    }

    /**
     * 合成确认界面里算出来缺的料（物品 ID 与件数，一条一句）；还在算、或不缺（网络已经开工就不会停在这页）为空。
     * 没有空闲的合成 CPU 时也写一句。
     */
    default List<String> craftShortages(AbstractContainerMenu menu) {
        return List.of();
    }

    /**
     * 网络存货的一条：一种东西（物品加组件）在网络里有多少。
     *
     * @param serial    这一条在这份界面里的编号，取货时指明取哪一条用它
     * @param sample    这种东西的样子（一件），组件与网络里的一致；比对"是不是同一种"用它
     * @param stored    网络里现有的件数
     * @param craftable 网络里有样板能做出它
     * @param fillsBucket 这一条是流体（水、岩浆这类有桶的）：按一桶一件列，样子是装满的桶，
     *                    取的时候像玩家拿空桶点它，装满一桶放进背包
     */
    record StockEntry(long serial, ItemStack sample, long stored, boolean craftable, boolean fillsBucket) {
        public StockEntry {
            Objects.requireNonNull(sample, "sample");
            sample = sample.copyWithCount(1);
            if (stored < 0) throw new IllegalArgumentException("网络里的件数不能为负：" + stored);
        }

        /** 物品那一条。 */
        public StockEntry(long serial, ItemStack sample, long stored, boolean craftable) {
            this(serial, sample, stored, craftable, false);
        }
    }
}
