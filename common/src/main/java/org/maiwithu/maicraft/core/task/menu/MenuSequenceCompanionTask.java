package org.maiwithu.maicraft.core.task.menu;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

public final class MenuSequenceCompanionTask
        extends AbstractCompanionTask<MenuSequenceTaskRecord> {
    private MenuReceipt receipt;
    private int index;
    private boolean rollingBack;
    private String pendingFailure;
    private int rollbackSlot = -1;
    private final VisibleMenuSession menuSession = new VisibleMenuSession();

    public MenuSequenceCompanionTask(LocalPlayer player, MenuSequenceTaskRecord record) {
        super(player, record);
    }

    @Override protected TaskState onTick() {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (index >= r.clicks.size()) {
            return menuSession.close(context) ? TaskState.SUCCESS : TaskState.RUNNING;
        }
        if (r.expectedContainerId >= 0
                && player.containerMenu.containerId != r.expectedContainerId) {
            fail("menu changed before click " + index + " (expected container "
                    + r.expectedContainerId + ")", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (receipt != null) {
            receipt = context.menus().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            if (rollingBack) {
                if (receipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED
                        && player.containerMenu.getCarried().isEmpty()) {
                    fail(pendingFailure, FailureType.UNKNOWN);
                } else {
                    fail(pendingFailure + "; cursor rollback was not confirmed: " + receipt.detail(),
                            FailureType.UNKNOWN);
                }
                return TaskState.FAILED;
            }
            if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                pendingFailure = "menu click " + index + " was not confirmed: " + receipt.detail();
                rollbackSlot = r.clicks.get(index).rollbackSlot();
                receipt = null;
                if (player.containerMenu.getCarried().isEmpty()) {
                    fail(pendingFailure, FailureType.UNKNOWN);
                    return TaskState.FAILED;
                }
                rollingBack = true;
                return TaskState.RUNNING;
            }
            receipt = null;
            index++;
            return TaskState.RUNNING;
        }
        if (rollingBack) {
            if (rollbackSlot < 0 || rollbackSlot >= player.containerMenu.slots.size()) {
                fail(pendingFailure + "; no valid rollback slot for the carried stack",
                        FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            if (!menuSession.ready(context)) return TaskState.RUNNING;
            receipt = context.menus().click(context, rollbackSlot, 0, net.minecraft.world.inventory.ClickType.PICKUP,
                    (c, ignored) -> c.player().containerMenu.getCarried().isEmpty()
                            ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING,
                    20);
            return TaskState.RUNNING;
        }
        MenuSequenceTaskRecord.Click click = r.clicks.get(index);
        if (click.slot() < 0 || click.slot() >= player.containerMenu.slots.size()) {
            fail("menu slot is unavailable: " + click.slot(), FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (!menuSession.ready(context)) return TaskState.RUNNING;
        ItemStack slotBefore = player.containerMenu.getSlot(click.slot()).getItem().copy();
        ItemStack carriedBefore = player.containerMenu.getCarried().copy();
        MenuConfirmation changed = (c, ignored) -> {
            ItemStack slotNow = c.player().containerMenu.getSlot(click.slot()).getItem();
            ItemStack carriedNow = c.player().containerMenu.getCarried();
            return same(slotBefore, slotNow) && same(carriedBefore, carriedNow)
                    ? MenuConfirmation.Verdict.NOT_APPLIED : MenuConfirmation.Verdict.APPLIED;
        };
        receipt = context.menus().click(context, click.slot(), click.button(), click.type(),
                changed, 20);
        return TaskState.RUNNING;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
    }
    @Override protected void cleanup() {
        menuSession.cleanup(player);
        receipt = null;
    }
    @Override protected Map<String, Object> resultData() { return Map.of("clicks", index); }
    @Override protected String successMessage() { return "confirmed " + index + " menu click(s)"; }
    @Override protected String cancelledMessage() { return "menu sequence interrupted"; }
}
