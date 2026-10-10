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

    /** 终端界面显示网络连没连上（没电、没频道都算没连上）；不是 ME 终端的界面时给空。 */
    Optional<Boolean> linked(AbstractContainerMenu menu);

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
     * 网络存货的一条：一种东西（物品加组件）在网络里有多少。
     *
     * @param serial    这一条在这份界面里的编号，取货时指明取哪一条用它
     * @param sample    这种东西的样子（一件），组件与网络里的一致；比对"是不是同一种"用它
     * @param stored    网络里现有的件数
     * @param craftable 网络里有样板能做出它
     */
    record StockEntry(long serial, ItemStack sample, long stored, boolean craftable) {
        public StockEntry {
            Objects.requireNonNull(sample, "sample");
            sample = sample.copyWithCount(1);
            if (stored < 0) throw new IllegalArgumentException("网络里的件数不能为负：" + stored);
        }
    }
}
