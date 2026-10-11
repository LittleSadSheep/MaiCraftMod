// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 从一堆里精确丢出几件，一次丢完，不一件一件按 Q：打开背包，有空格就用左右键在空格里分出这几件，
 * 对那一格整份丢（Ctrl+Q）；背包满了没空格，就把这几件拿在鼠标上、多拿的一件件退回原格，
 * 再在界面外点一下整份丢掉。每一下都等游戏确认那几格变成预期的样子才点下一下；丢完关上背包。
 */
final class MenuDropBatch implements Action {

    /** 每一下点击等确认的期限。 */
    private static final int CLICK_TIMEOUT_TICKS = 20;
    /** 界面外的那一格：拿着东西点它就是丢出去。 */
    private static final int OUTSIDE = -999;

    /** 一下点击：点哪一格、哪个键、哪种点法，点完后原格、空格、鼠标上各是几件。 */
    private record Click(int slot, int button, ClickType type, int sourceAfter, int destinationAfter, int cursorAfter) {}

    private final int inventorySlot;
    private final int amount;
    private List<Click> clicks;
    private int index;
    private int sourceSlot;
    private int destinationSlot = -1;
    private ItemStack kind;
    private PendingMenuAction pending;
    private boolean closing;
    private boolean thrown;
    /** 丢的那一下（最后一下）提交了却没得到确认：丢没丢出去说不清。 */
    private boolean throwUncertain;
    private Problem failure;

    /**
     * @param inventorySlot 要丢的那一堆在背包里的格号（快捷栏 0~8、主背包 9~35）
     * @param amount        丢几件，少于这一堆的件数
     */
    MenuDropBatch(int inventorySlot, int amount) {
        this.inventorySlot = inventorySlot;
        this.amount = amount;
    }

    /** 那几件丢出去了（最后那一下得到了确认）。 */
    boolean thrown() {
        return thrown;
    }

    /** 丢那一下提交了但没等到确认：说不清丢没丢出去。 */
    boolean throwUncertain() {
        return throwUncertain;
    }

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (failure != null) return ActionStatus.failed(failure);
        var menus = player.menuActions();
        if (pending != null) {
            if (!pending.terminal()) pending = menus.poll(player, pending);
            if (!pending.terminal()) return ActionStatus.running();
            if (pending.status() != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                throwUncertain = !closing && index == clicks.size() - 1;
                return fail((closing ? "关背包" : "分堆丢东西的第 " + (index + 1) + " 下") + "没得到确认：" + pending.detail());
            }
            pending = null;
            if (closing) return ActionStatus.done();
            if (index == clicks.size() - 1) thrown = true;
            index++;
            return ActionStatus.progressed();
        }
        if (clicks == null) {
            if (!menus.ensureVisible(player)) return ActionStatus.running();
            plan(player.localPlayer());
            if (failure != null) return ActionStatus.failed(failure);
        }
        // 点一下也是一次交互：本刻机会已经用掉（或角色不归自动化控制）就等下一刻。
        if (!player.canInteractThisTick()) return ActionStatus.running();
        if (index >= clicks.size()) {
            closing = true;
            pending = menus.close(player, CLICK_TIMEOUT_TICKS);
            return ActionStatus.running();
        }
        Click click = clicks.get(index);
        pending = menus.click(player, click.slot(), click.button(), click.type(), confirmation(click), CLICK_TIMEOUT_TICKS);
        return ActionStatus.running();
    }

    // 排好这一串点击：有空格就在空格里分出来再整份丢，没有就拿在鼠标上退回多的、在界面外丢。
    private void plan(LocalPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        var inventory = self.getInventory();
        var source = menu.findSlot(inventory, inventorySlot);
        if (source.isEmpty() || !menu.getCarried().isEmpty()) {
            fail("背包界面里找不到那一堆，或者鼠标上还拿着东西");
            return;
        }
        sourceSlot = source.getAsInt();
        kind = menu.getSlot(sourceSlot).getItem().copy();
        int count = kind.getCount();
        if (amount < 1 || amount >= count) {
            fail("要丢 " + amount + " 件，那一堆有 " + count + " 件，用不着分堆");
            return;
        }
        int capacity = menu.getSlot(sourceSlot).getMaxStackSize(kind);
        for (var slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() < 36 && !slot.hasItem()
                    && slot.mayPlace(kind) && slot.getMaxStackSize(kind) >= amount) {
                destinationSlot = slot.index;
                break;
            }
        }
        clicks = destinationSlot >= 0 ? viaEmptySlot(count, capacity) : viaCursor(count);
    }

    private List<Click> viaEmptySlot(int count, int capacity) {
        List<Click> result = new ArrayList<>();
        for (StackSplit.Step step : StackSplit.plan(count, amount, capacity)) {
            int slot = step.side() == StackSplit.Side.SOURCE ? sourceSlot : destinationSlot;
            result.add(new Click(slot, step.button(), ClickType.PICKUP, step.after().source(),
                    step.after().deposited(), step.after().cursor()));
        }
        // 分好的那一格整份丢（Ctrl+Q 在界面里就是对这一格按丢弃键时按住 Ctrl）。
        result.add(new Click(destinationSlot, 1, ClickType.THROW, count - amount, 0, 0));
        return result;
    }

    // 背包满了：拿起足够的一份（一半够就拿一半），多拿的一件件退回原格，再在界面外整份丢掉。
    private List<Click> viaCursor(int count) {
        List<Click> result = new ArrayList<>();
        int take = (count + 1) / 2 >= amount ? (count + 1) / 2 : count;
        result.add(new Click(sourceSlot, take == count ? 0 : 1, ClickType.PICKUP, count - take, 0, take));
        for (int cursor = take; cursor > amount; cursor--) {
            result.add(new Click(sourceSlot, 1, ClickType.PICKUP, count - cursor + 1, 0, cursor - 1));
        }
        result.add(new Click(OUTSIDE, 0, ClickType.PICKUP, count - amount, 0, 0));
        return result;
    }

    // 点完以后原格、空格、鼠标上的件数都对上才算这一下生效；还是点之前的样子算没生效，别的样子算对不上。
    private MenuConfirmation confirmation(Click click) {
        return (context, pending) -> {
            AbstractContainerMenu menu = context.localPlayer().containerMenu;
            boolean source = count(menu.getSlot(sourceSlot).getItem()) == click.sourceAfter();
            boolean destination = destinationSlot < 0 || count(menu.getSlot(destinationSlot).getItem()) == click.destinationAfter();
            boolean cursor = count(menu.getCarried()) == click.cursorAfter();
            if (source && destination && cursor) return MenuConfirmation.Verdict.APPLIED;
            return menu.getStateId() == pending.beforeStateId() ? MenuConfirmation.Verdict.PENDING
                    : MenuConfirmation.Verdict.DIVERGED;
        };
    }

    private int count(ItemStack stack) {
        return stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, kind) ? 0 : stack.getCount();
    }

    private ActionStatus fail(String reason) {
        failure = Problem.of(Problem.Kind.STUCK, reason);
        return ActionStatus.failed(failure);
    }

    @Override
    public Interruptibility interruptibility() {
        return pending != null && !pending.terminal() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        return clicks == null ? "打开背包分出 " + amount + " 件准备丢" : "分堆丢 " + amount + " 件（第 " + (index + 1) + " 下）";
    }
}
