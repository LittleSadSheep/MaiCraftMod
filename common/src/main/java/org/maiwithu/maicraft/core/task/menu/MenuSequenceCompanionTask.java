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

// 按顺序执行一组菜单点击，每次等结果后再继续。当前生产源码没有直接创建这类记录的调用点，属于保留的低层任务。
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

    // 先判断列表是否已完成、菜单编号是否匹配，再处理上次点击或失败后的鼠标物品放回。
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
        // 出错后只尝试把鼠标上的物品放到指定回退格，不会撤销前面所有已完成的点击。
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
        // 这里只看点击格或鼠标物品有没有变化；有变化就作为点击生效的条件，不知道上层最终想得到哪种物品结果。
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
    // 关闭这个会话使用过的界面并清掉记录；会话只有 used 标志，没有保存原菜单身份，替换菜单的归属仍需调用方处理。
    @Override protected void cleanup() {
        menuSession.cleanup(player);
        receipt = null;
    }
    @Override protected Map<String, Object> resultData() { return Map.of("clicks", index); }
    @Override protected String successMessage() { return "confirmed " + index + " menu click(s)"; }
    @Override protected String cancelledMessage() { return "menu sequence interrupted"; }
}
