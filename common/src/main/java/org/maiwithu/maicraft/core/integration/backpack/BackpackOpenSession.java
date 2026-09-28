// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;

/** 拿起本人随身背包并原生右键打开，等待槽位同步；只关闭本会话确认的菜单，鼠标有余物则保留现场。 */
public final class BackpackOpenSession {
    public enum Status { RUNNING, READY, CLOSED, FAILED }
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private LocalPlayer owner;
    private Object level;
    private InteractionHand hand;
    private int slot = -1;
    private long started = -1;
    private NativeActionReceipt opening;
    private MenuReceipt closing;
    private AbstractContainerMenu menu;
    private String failure;
    private boolean closed, uncertain;

    public static int carriedSlot(LocalPlayer player) {
        // 主背包和副手都可走原生持物使用；穿戴栏与其他模组饰品栏不冒充主背包槽号。
        for (int slot = 0; slot < 36; slot++) if (BackpackMenuAccess.isBackpack(player.getInventory().getItem(slot))) return slot;
        return BackpackMenuAccess.isBackpack(player.getOffhandItem()) ? 40 : -1;
    }

    public Status open(LocalPlayerContext context) {
        if (failure != null) return Status.FAILED;
        if (closed) return Status.CLOSED;
        LocalPlayer player = context.player();
        if (owner == null) {
            if (player.containerMenu != player.inventoryMenu || context.minecraft().screen != null
                    || !player.inventoryMenu.getCarried().isEmpty()) return fail("another menu or carried cursor is already active", false);
            slot = carriedSlot(player);
            if (slot < 0) return fail("no Sophisticated Backpack is available in the main inventory or offhand", false);
            owner = player; level = player.level(); started = player.level().getGameTime();
            hand = slot == 40 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        }
        if (player != owner || player.level() != level) return fail("backpack owner or world changed", opening != null);
        // 已提交的原生使用先结算；同步较慢只等待，不向同一只背包反复发右键。
        if (opening != null) {
            opening = context.actions().poll(context, opening);
            if (!opening.terminal()) return Status.RUNNING;
            if (opening.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED)
                return fail("native backpack open was not confirmed: " + opening.detail(), true);
            var read = BackpackMenuAccess.read(player);
            if ("awaiting_sync".equals(read.status()) && player.level().getGameTime() - started < 120) return Status.RUNNING;
            if (read.snapshot() == null) return fail("opened backpack is not observable: " + read.status(), false);
            if (!ItemStack.isSameItemSameComponents(read.snapshot().backpack(), player.getItemInHand(hand)))
                return fail("opened menu does not match the backpack in the initiating hand", false);
            menu = read.snapshot().menu(); return Status.READY;
        }
        if (player.level().getGameTime() - started >= 120) return fail("backpack preparation timed out", selection.pending());
        if (hand == InteractionHand.MAIN_HAND) {
            var status = selection.select(player, slot);
            if (status == FirstPersonActionGate.Status.FAILED) return fail(selection.failure(), selection.pending());
            if (status != FirstPersonActionGate.Status.READY) return Status.RUNNING;
        }
        if (!context.mutationAvailable()) return Status.RUNNING;
        if (!BackpackMenuAccess.isBackpack(player.getItemInHand(hand))) return fail("selected backpack changed before native use", false);
        opening = context.actions().useItem(context, hand, NativeConfirmation.menuChanged(player.containerMenu.containerId), 60);
        return Status.RUNNING;
    }

    public Status close(LocalPlayerContext context) {
        if (closed) return Status.CLOSED;
        if (context.player() != owner || owner.level() != level || menu == null)
            return fail("backpack menu ownership changed before close", true);
        // 发出关闭后玩家菜单会先切回背包，再收到关闭回执；必须先结算这笔关闭，不能误判成外来换界面。
        if (closing != null) {
            closing = context.menus().poll(context, closing);
            if (!closing.terminal()) return Status.RUNNING;
            if (closing.status() != MenuReceipt.Status.CONFIRMED_APPLIED || owner.containerMenu != owner.inventoryMenu
                    || context.minecraft().screen != null) return fail("backpack close was not confirmed", true);
            closed = true; selection.reset(); return Status.CLOSED;
        }
        if (owner.containerMenu != menu) return fail("backpack menu was replaced before closing", true);
        // 不关有物品停在鼠标上的菜单，避免原生关闭把未结算余物扔在脚下。
        if (!menu.getCarried().isEmpty()) return fail("backpack cursor must settle before closing", true);
        if (!context.mutationAvailable()) return Status.RUNNING;
        closing = context.menus().close(context, 40); return Status.RUNNING;
    }

    public void cancel(LocalPlayerContext context) {
        // 取消时先结清已提交的开包，再仅收拾本会话的空鼠标菜单；外来界面和未结余物原样保留。
        if (owner == null) return;
        if (context.player() != owner || owner.level() != level) { uncertain = true; return; }
        if (opening != null && !opening.terminal()) {
            opening = context.actions().poll(context, opening);
            if (!opening.terminal()) {
                uncertain = true;
                if (context.mutationAvailable() && opening.kind() == NativeActionReceipt.Kind.USE_ITEM)
                    opening = context.actions().releaseUsingItem(context, opening);
            }
            uncertain |= opening.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED;
        }
        if (context.player() == owner && owner.level() == level && owner.containerMenu == menu && menu.getCarried().isEmpty())
            context.menus().closeForTaskBoundary(context, 40, "backpack access cancelled");
        else if (owner != null && owner.containerMenu == owner.inventoryMenu) selection.reset();
        else if (owner != null) uncertain = true;
    }
    private Status fail(String reason, boolean unknown) { failure = reason; uncertain |= unknown; return Status.FAILED; }
    public String failure() { return failure; }
    public boolean uncertain() { return uncertain; }
    public AbstractContainerMenu menu() { return menu; }
}
