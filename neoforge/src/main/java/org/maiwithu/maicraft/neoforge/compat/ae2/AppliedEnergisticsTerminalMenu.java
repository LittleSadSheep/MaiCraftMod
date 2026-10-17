// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.crafting.UnsuitableCpus;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.common.PendingCraftingJobs;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

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

    // 存货表由终端的界面画面建立并接收服务端的增量同步；物品照列，有桶的流体（水、岩浆）按一桶一件列成装满的桶，
    // 其余流体、化学品这类不列。
    @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) {
        if (!(menu instanceof MEStorageMenu storage)) return Optional.empty();
        IClientRepo repo = storage.getClientRepo();
        if (repo == null) return Optional.empty();
        List<StockEntry> entries = new ArrayList<>();
        for (GridInventoryEntry entry : repo.getAllEntries()) {
            if (entry.getWhat() instanceof AEItemKey item) {
                entries.add(new StockEntry(entry.getSerial(), item.toStack(1),
                        Math.max(0, entry.getStoredAmount()), entry.isCraftable()));
            } else if (entry.getWhat() instanceof AEFluidKey fluid && fluid.getFluid().getBucket() != Items.AIR) {
                entries.add(new StockEntry(entry.getSerial(), new ItemStack(fluid.getFluid().getBucket()),
                        Math.max(0, entry.getStoredAmount()) / AEFluidKey.AMOUNT_BUCKET, false, true));
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

    // 光标空着 Shift 点流体：网络里有空桶就拿一只装满放进背包（终端里给的"装满放进背包"那一下）。
    @Override public void fillBucket(AbstractContainerMenu menu, long serial) {
        if (menu instanceof MEStorageMenu storage) storage.handleInteraction(serial, InventoryAction.FILL_ITEM_MOVE_TO_PLAYER);
    }

    // 下单合成：和在终端里中键点那一条一样，服务端打开填数量的界面。
    @Override public void requestCraft(AbstractContainerMenu menu, long serial) {
        if (menu instanceof MEStorageMenu storage) storage.handleInteraction(serial, InventoryAction.AUTO_CRAFT);
    }

    @Override public boolean craftAmountMenu(AbstractContainerMenu menu) {
        return menu instanceof CraftAmountMenu;
    }

    // 定数量、不只补差额、算完就开工：料齐时服务端直接开工并回到终端，缺料时停在确认页把缺的列出来。
    @Override public void confirmCraft(AbstractContainerMenu menu, int amount) {
        if (menu instanceof CraftAmountMenu craftAmount) craftAmount.confirm(amount, false, true);
    }

    // 确认页：计划同步过来才算算完；计划是模拟的就是缺料，列出每样缺多少；开工被拒时服务端把错误码与详情
    // 同步到 submitError（几台在忙、太小、断电，或开工时缺的那一样），照原样交出去，说法在联动入口那边写。
    @Override public Optional<CraftPlanPage> craftPlanPage(AbstractContainerMenu menu) {
        if (!(menu instanceof CraftConfirmMenu confirm)) return Optional.empty();
        var plan = confirm.getPlan();
        List<String> missing = new ArrayList<>();
        if (plan != null && plan.isSimulation()) {
            for (var entry : plan.getEntries()) {
                if (entry.getMissingAmount() > 0) missing.add(entry.getWhat().getId() + " ×" + entry.getMissingAmount());
            }
        }
        Optional<CraftRefusal> refusal = Optional.empty();
        ICraftingSubmitResult submitted = confirm.submitError == null ? null : confirm.submitError.result();
        if (submitted != null && submitted.errorCode() != null) {
            int busy = 0;
            int tooSmall = 0;
            int offline = 0;
            String missingOne = "";
            if (submitted.errorDetail() instanceof UnsuitableCpus cpus) {
                busy = cpus.busy();
                tooSmall = cpus.tooSmall();
                offline = cpus.offline();
            } else if (submitted.errorDetail() instanceof GenericStack stack) {
                missingOne = stack.what().getId() + " ×" + stack.amount();
            }
            refusal = Optional.of(new CraftRefusal(submitted.errorCode().name(), busy, tooSmall, offline, missingOne));
        }
        return Optional.of(new CraftPlanPage(plan != null, missing, refusal));
    }

    // 角色自己下的作业：AE2 客户端按服务端发来的开工、结束记着，按物品键问。
    @Override public boolean craftPending(ItemStack sample) {
        AEItemKey key = AEItemKey.of(sample);
        return key != null && PendingCraftingJobs.hasPendingJob(key);
    }

    // 编号 -1 指网络格子的空白处：光标上拿着东西时，左键这一下把它放回网络。
    @Override public void putBack(AbstractContainerMenu menu) {
        if (menu instanceof MEStorageMenu storage) storage.handleInteraction(-1, InventoryAction.PICKUP_OR_SET_DOWN);
    }
}
