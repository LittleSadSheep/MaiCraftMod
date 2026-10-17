// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * ME 终端界面的替身，演下单合成那几页：终端里点"合成"换成填数量页，填完数量换成确认页（或料齐当场开工退回终端），
 * 确认页按 {@link #plan} 显示。此刻是哪一页看 {@link #current}；AE2 记不记着角色自己下的这一单看 {@link #pending}。
 */
final class FakeCraftPages implements Ae2TerminalMenu {

    final AbstractContainerMenu terminal = page("终端");
    final AbstractContainerMenu amountPage = page("填数量页");
    final AbstractContainerMenu confirmPage = page("确认页");
    final AbstractContainerMenu elsewhere = page("别的界面");

    /** 此刻开着的界面。 */
    AbstractContainerMenu current = terminal;
    /** 终端里的网络存货。 */
    final List<StockEntry> stock = new ArrayList<>();
    /** AE2 记着角色自己下的这一单还没结束。 */
    boolean pending;
    /** 定下数量后料齐当场开工、退回终端；为假时停在确认页，按 plan 显示。 */
    boolean startsAtOnce = true;
    /** 点了"合成"服务端不理（不换页）。 */
    boolean ignoresCraftRequest;
    /** 确认页的样子。 */
    CraftPlanPage plan = new CraftPlanPage(false, List.of(), Optional.empty());

    /** 发过的下单：点"合成"的那一条的编号，填数量页定下的件数。 */
    final List<Long> craftRequests = new ArrayList<>();
    final List<Integer> confirmedAmounts = new ArrayList<>();

    private static AbstractContainerMenu page(String name) {
        return new AbstractContainerMenu(null, 0) {
            @Override public ItemStack quickMoveStack(Player player, int index) {
                return ItemStack.EMPTY;
            }

            @Override public boolean stillValid(Player player) {
                return true;
            }

            @Override public String toString() {
                return name;
            }
        };
    }

    @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block, Direction side) {
        return menu == terminal;
    }

    @Override public Optional<Boolean> linked(AbstractContainerMenu menu) {
        return menu == terminal ? Optional.of(true) : Optional.empty();
    }

    @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) {
        return menu == terminal ? Optional.of(List.copyOf(stock)) : Optional.empty();
    }

    @Override public void takeStack(AbstractContainerMenu menu, long serial) {}

    @Override public void takeOne(AbstractContainerMenu menu, long serial) {}

    @Override public void putBack(AbstractContainerMenu menu) {}

    // 终端里点"合成"：服务端打开填数量页。
    @Override public void requestCraft(AbstractContainerMenu menu, long serial) {
        if (menu != terminal) return;
        craftRequests.add(serial);
        if (!ignoresCraftRequest) current = amountPage;
    }

    @Override public boolean craftAmountMenu(AbstractContainerMenu menu) {
        return menu == amountPage;
    }

    // 填数量页定下：料齐当场开工、退回终端并记着这一单；不然换成确认页。
    @Override public void confirmCraft(AbstractContainerMenu menu, int amount) {
        if (menu != amountPage) return;
        confirmedAmounts.add(amount);
        if (startsAtOnce) {
            current = terminal;
            pending = true;
        } else {
            current = confirmPage;
        }
    }

    @Override public Optional<CraftPlanPage> craftPlanPage(AbstractContainerMenu menu) {
        return menu == confirmPage ? Optional.of(plan) : Optional.empty();
    }

    @Override public boolean craftPending(ItemStack sample) {
        return pending;
    }
}
