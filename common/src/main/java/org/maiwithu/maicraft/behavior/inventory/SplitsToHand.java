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
 * 主手那一堆只留恰好几件在主手：放置物台这类"右键放下手上整叠"的投法，手上几件就放下去几件，
 * 多放的会被压机、机械手一起加工掉。
 *
 * <p>打开背包，用左右键把要的件数分进一个空格：快捷栏有空格就分进那一格，关上背包再选中它；
 * 快捷栏满了就分进主背包的空格，再用原生交换（对那一格按选中格的数字键）把它换进主手，
 * 主手原来那一堆换到那个空格里。每一下都等游戏确认那几格变成预期的样子才点下一下。
 * 主手件数本来就不多于要的件数时什么都不做；背包里一个空格都没有时如实失败，不硬分。
 */
public final class SplitsToHand implements Action {

    /** 每一下点击等确认的期限。 */
    private static final int CLICK_TIMEOUT_TICKS = 20;
    /** 主背包的格号范围（快捷栏之外）。 */
    private static final int FIRST_MAIN_SLOT = 9;
    private static final int LAST_MAIN_SLOT = 35;

    /** 一下点击：点哪一格、哪个键、哪种点法，点完后主手那格、空格、鼠标上各是几件。 */
    private record Click(int slot, int button, ClickType type, int sourceAfter, int destinationAfter, int cursorAfter) {}

    private final int amount;
    private List<Click> clicks;
    private int index;
    private int sourceSlot;
    private int destinationSlot = -1;
    /** 分进的是快捷栏的哪一格；分进主背包时为 -1，最后交换回主手，不用改选中。 */
    private int hotbarDestination = -1;
    private ItemStack kind;
    private PendingMenuAction pending;
    private boolean closing;
    private HotbarSelection selecting;
    private Problem failure;

    /** @param amount 主手留几件，至少 1 */
    public SplitsToHand(int amount) {
        if (amount < 1) throw new IllegalArgumentException("主手至少留 1 件：" + amount);
        this.amount = amount;
    }

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (failure != null) return ActionStatus.failed(failure);
        if (selecting != null) return selecting.tick(context);
        var menus = player.menuActions();
        if (pending != null) {
            if (!pending.terminal()) pending = menus.poll(player, pending);
            if (!pending.terminal()) return ActionStatus.running();
            if (pending.status() != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                return fail((closing ? "关背包" : "分出 " + amount + " 件的第 " + (index + 1) + " 下")
                        + "没得到确认：" + pending.detail());
            }
            pending = null;
            if (closing) return afterClosing();
            index++;
            return ActionStatus.progressed();
        }
        if (clicks == null) {
            // 主手不多于要的件数：手上就是要放下去的那些，不用分。
            if (player.localPlayer().getMainHandItem().getCount() <= amount) return ActionStatus.done();
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
        // 每一下之前界面都要真画出来过：上一下提交后要等新的一帧，窗口没在画（最小化、被挡）时等它画，
        // 不然点下去游戏层直接报错，整个任务按内部错误收场（实机压板站投料两次都这样）。
        if (!menus.ensureVisible(player)) return ActionStatus.running();
        Click click = clicks.get(index);
        pending = menus.click(player, click.slot(), click.button(), click.type(), confirmation(click),
                CLICK_TIMEOUT_TICKS);
        return ActionStatus.running();
    }

    // 关上背包之后：分进的是快捷栏空格就选中它；分进主背包的那份已经交换回主手了。
    private ActionStatus afterClosing() {
        if (hotbarDestination < 0) return ActionStatus.done();
        selecting = new HotbarSelection(hotbarDestination);
        return ActionStatus.progressed();
    }

    // 排好这一串点击：先找空格（快捷栏优先），按最少点击把要的件数分进去；分进主背包的再加一下交换回主手。
    private void plan(LocalPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        var inventory = self.getInventory();
        var source = menu.findSlot(inventory, inventory.selected);
        if (source.isEmpty() || !menu.getCarried().isEmpty()) {
            fail("背包界面里找不到主手那一格，或者鼠标上还拿着东西");
            return;
        }
        sourceSlot = source.getAsInt();
        kind = menu.getSlot(sourceSlot).getItem().copy();
        int count = kind.getCount();
        if (count <= amount) {
            clicks = List.of();
            return;
        }
        for (int slot = 0; slot <= LAST_MAIN_SLOT && destinationSlot < 0; slot++) {
            if (slot == inventory.selected || !inventory.getItem(slot).isEmpty()) continue;
            var found = menu.findSlot(inventory, slot);
            if (found.isPresent()) {
                destinationSlot = found.getAsInt();
                hotbarDestination = slot < FIRST_MAIN_SLOT ? slot : -1;
            }
        }
        if (destinationSlot < 0) {
            fail("背包里没有空格，分不出 " + amount + " 件，手上那 " + count + " 件会整叠放下去");
            return;
        }
        int capacity = menu.getSlot(sourceSlot).getMaxStackSize(kind);
        List<Click> result = new ArrayList<>();
        for (StackSplit.Step step : StackSplit.plan(count, amount, capacity)) {
            int slot = step.side() == StackSplit.Side.SOURCE ? sourceSlot : destinationSlot;
            result.add(new Click(slot, step.button(), ClickType.PICKUP, step.after().source(),
                    step.after().deposited(), step.after().cursor()));
        }
        if (hotbarDestination < 0) {
            // 分进主背包的那份换进主手：对那一格按选中格的数字键，两格对调。
            result.add(new Click(destinationSlot, inventory.selected, ClickType.SWAP, amount, count - amount, 0));
        }
        clicks = result;
    }

    // 点完以后主手那格、空格、鼠标上的件数都对上才算这一下生效；还是点之前的样子算没生效，别的样子算对不上。
    private MenuConfirmation confirmation(Click click) {
        return (context, pending) -> {
            AbstractContainerMenu menu = context.localPlayer().containerMenu;
            boolean source = count(menu.getSlot(sourceSlot).getItem()) == click.sourceAfter();
            boolean destination = count(menu.getSlot(destinationSlot).getItem()) == click.destinationAfter();
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
        if (selecting != null) return selecting.interruptibility();
        return pending != null && !pending.terminal() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        if (selecting != null) return selecting.describe();
        return clicks == null ? "打开背包，主手只留 " + amount + " 件" : "分出 " + amount + " 件留在主手（第 " + (index + 1) + " 下）";
    }
}
