// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import appeng.api.parts.IPartHost;
import appeng.api.stacks.AEItemKey;
import appeng.helpers.InventoryAction;
import appeng.helpers.WirelessTerminalMenuHost;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.IClientRepo;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.compat.ae2.Ae2TerminalMenu;

/**
 * ME 终端界面的读写端：读 AE2 终端界面上的网络连接状态与客户端存货表，发 AE2 自己的取货操作。
 * 只翻译，不判断；直接引用 AE2 的类，所以只在联动清单确认装了、版本在范围内之后才会被加载。
 */
public final class AppliedEnergisticsTerminalMenu implements Ae2TerminalMenu {

    // 界面背后的终端就是这一格这一面的那个部件对象，才算这台终端打开的。
    @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block, Direction side) {
        return menu instanceof MEStorageMenu storage
                && level.getBlockEntity(block) instanceof IPartHost host
                && host.getPart(side) != null
                && storage.getHost() == host.getPart(side);
    }

    // 背后是拿在手里的无线终端（含无线合成终端）才算：主机就是那件物品的界面主机。
    @Override public boolean wirelessMenu(AbstractContainerMenu menu) {
        return menu instanceof MEStorageMenu storage && storage.getHost() instanceof WirelessTerminalMenuHost<?>;
    }

    // 没连上的原因是服务端同步过来的那句（超出范围、没电、没频道），按客户端语言读出来。
    @Override public Optional<String> unlinkedWhy(AbstractContainerMenu menu) {
        if (!(menu instanceof MEStorageMenu storage) || storage.getLinkStatus().connected()) return Optional.empty();
        var why = storage.getLinkStatus().statusDescription();
        return why == null ? Optional.empty() : Optional.of(why.getString());
    }

    @Override public Optional<Boolean> linked(AbstractContainerMenu menu) {
        if (!(menu instanceof MEStorageMenu storage)) return Optional.empty();
        return Optional.of(storage.getLinkStatus().connected());
    }

    // 存货表由终端的界面画面建立并接收服务端的增量同步；只取物品条目，流体等不列。
    @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) {
        if (!(menu instanceof MEStorageMenu storage)) return Optional.empty();
        IClientRepo repo = storage.getClientRepo();
        if (repo == null) return Optional.empty();
        List<StockEntry> entries = new ArrayList<>();
        for (GridInventoryEntry entry : repo.getAllEntries()) {
            if (entry.getWhat() instanceof AEItemKey item) {
                entries.add(new StockEntry(entry.getSerial(), item.toStack(1),
                        Math.max(0, entry.getStoredAmount()), entry.isCraftable()));
            }
        }
        return Optional.of(List.copyOf(entries));
    }

    // 不是 ME 终端的界面就什么都不发：调用方按背包前后对照会看到没动静。
    @Override public void takeStack(AbstractContainerMenu menu, long serial) {
        if (menu instanceof MEStorageMenu storage) storage.handleInteraction(serial, InventoryAction.SHIFT_CLICK);
    }

    @Override public void takeOne(AbstractContainerMenu menu, long serial) {
        if (menu instanceof MEStorageMenu storage) storage.handleInteraction(serial, InventoryAction.PICKUP_SINGLE);
    }

    // 下单合成：和在终端里中键点那一条一样，服务端打开填数量的界面。
    @Override public void requestCraft(AbstractContainerMenu menu, long serial) {
        if (menu instanceof MEStorageMenu storage) storage.handleInteraction(serial, InventoryAction.AUTO_CRAFT);
    }

    @Override public boolean craftAmountMenu(AbstractContainerMenu menu) {
        return menu instanceof CraftAmountMenu;
    }

    @Override public boolean craftConfirmMenu(AbstractContainerMenu menu) {
        return menu instanceof CraftConfirmMenu;
    }

    // 定数量、不只补差额、算完就开工：料齐时服务端直接开工并回到终端，缺料时停在确认页把缺的列出来。
    @Override public void confirmCraft(AbstractContainerMenu menu, int amount) {
        if (menu instanceof CraftAmountMenu craftAmount) craftAmount.confirm(amount, false, true);
    }

    // 确认页算出来的缺料（模拟结果里缺的每一样），没有空闲合成 CPU 也算一条。
    @Override public List<String> craftShortages(AbstractContainerMenu menu) {
        if (!(menu instanceof CraftConfirmMenu confirm)) return List.of();
        List<String> shortages = new ArrayList<>();
        var plan = confirm.getPlan();
        if (plan != null && plan.isSimulation()) {
            for (var entry : plan.getEntries()) {
                if (entry.getMissingAmount() > 0) shortages.add(entry.getWhat().getId() + " ×" + entry.getMissingAmount());
            }
        }
        if (plan != null && !plan.isSimulation() && confirm.hasNoCPU()) shortages.add("没有空闲的合成 CPU");
        return List.copyOf(shortages);
    }

    // 编号 -1 指网络格子的空白处：光标上拿着东西时，左键这一下把它放回网络。
    @Override public void putBack(AbstractContainerMenu menu) {
        if (menu instanceof MEStorageMenu storage) storage.handleInteraction(-1, InventoryAction.PICKUP_OR_SET_DOWN);
    }
}
