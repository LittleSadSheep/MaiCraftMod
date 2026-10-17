// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import java.util.function.BiPredicate;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.player.PlayerContext;

/** 描述一次菜单操作预期看到的变化；只读取当前菜单，不在确认过程中再点别的槽位。 */
@FunctionalInterface
public interface MenuConfirmation {
    enum Verdict { PENDING, APPLIED, NOT_APPLIED, DIVERGED }

    Verdict observe(PlayerContext context, PendingMenuAction pending);

    static MenuConfirmation stateChanged() {
        // 只证明同一菜单的版本变过；真正需要某物品出现时，调用方还要核对具体槽位。
        return (context, pending) -> {
            var menu = context.localPlayer().containerMenu;
            return menu.containerId == pending.containerId()
                    && menu.getStateId() != pending.beforeStateId()
                    ? Verdict.APPLIED : Verdict.PENDING;
        };
    }

    /**
     * 点一格、快速移动这一下的确认：被点的那一格或光标上的东西变了（客户端已按原版规则先算出结果）就算生效，
     * 服务器改过菜单版本也算。原先只等菜单版本变：客户端先算对了的点击服务器一个包都不回，版本不变，
     * 每一下都要等满五秒才按说不清收场，往箱子里存一件东西要等五十秒。点了以后东西对不对，由调用方按两侧内容核对。
     */
    static MenuConfirmation slotOrCursorChanged(int slot, ItemStack slotBefore, ItemStack carriedBefore) {
        ItemStack frozenSlot = slotBefore.copy();
        ItemStack frozenCarried = carriedBefore.copy();
        return (context, pending) -> {
            var menu = context.localPlayer().containerMenu;
            if (menu.containerId != pending.containerId()) return Verdict.PENDING;
            if (menu.getStateId() != pending.beforeStateId()) return Verdict.APPLIED;
            boolean slotChanged = slot >= 0 && slot < menu.slots.size()
                    && !ItemStack.matches(menu.getSlot(slot).getItem(), frozenSlot);
            boolean cursorChanged = !ItemStack.matches(menu.getCarried(), frozenCarried);
            return slotChanged || cursorChanged ? Verdict.APPLIED : Verdict.PENDING;
        };
    }

    static MenuConfirmation inventorySwap(
            int sourceInventorySlot,
            int hotbarSlot,
            ItemStack sourceBefore,
            ItemStack hotbarBefore) {
        return inventorySwap(sourceInventorySlot, hotbarSlot, sourceBefore, hotbarBefore, MenuConfirmation::same);
    }

    /** 动态电量等由对应模组定义可变化字段；交换仍须同时核对两端物品数量与其余身份。 */
    static MenuConfirmation inventorySwap(int sourceInventorySlot, int hotbarSlot,
            ItemStack sourceBefore, ItemStack hotbarBefore, BiPredicate<ItemStack, ItemStack> equivalent) {
        // 保存交换前两叠物品，之后区分已对调、完全没变和出现第三种情况。
        ItemStack frozenSource = sourceBefore.copy();
        ItemStack frozenHotbar = hotbarBefore.copy();
        return (context, pending) -> {
            ItemStack source = context.localPlayer().getInventory().getItem(sourceInventorySlot);
            ItemStack hotbar = context.localPlayer().getInventory().getItem(hotbarSlot);
            if (equivalent.test(source, frozenHotbar) && equivalent.test(hotbar, frozenSource)) return Verdict.APPLIED;
            if (equivalent.test(source, frozenSource) && equivalent.test(hotbar, frozenHotbar)) return Verdict.NOT_APPLIED;
            return Verdict.DIVERGED;
        };
    }

    static MenuConfirmation closedToInventory() {
        // 页面先消失而返料稍后同步时继续等待；回到默认菜单且鼠标、背包合成余料均结清，才完成关闭。
        return (context, pending) -> context.localPlayer().containerMenu == context.localPlayer().inventoryMenu
                && Minecraft.getInstance().screen == null
                && context.localPlayer().inventoryMenu.getCarried().isEmpty()
                && !GuiPreparation.inventoryGridOccupied(context.localPlayer()) ? Verdict.APPLIED : Verdict.PENDING;
    }

    static MenuConfirmation pending() {
        return (context, pending) -> Verdict.PENDING;
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount() && ItemStack.isSameItemSameComponents(left, right);
    }
}
