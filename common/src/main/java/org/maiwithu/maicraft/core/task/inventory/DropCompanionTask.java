package org.maiwithu.maicraft.core.task.inventory;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.FailureType;

import org.maiwithu.maicraft.task.TaskState;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.Precondition;
import net.minecraft.world.inventory.ClickType;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过显示出来的背包界面丢弃指定物品，逐次等待结果，不直接修改背包总数。
 * 这里需要把 Inventory 下标转换成菜单槽号；当前盔甲与副手的转换有 A43 所列的实际风险。
 */
public final class DropCompanionTask extends AbstractCompanionTask<DropItemsTaskRecord> {
    private static final long DROP_PROGRESS_LEASE_TICKS = 10L * 20L;

    private int dropped;
    private String doneMessage = "done";
    private MenuReceipt receipt;
    private int target;
    private int pendingDrop;
    private final VisibleMenuSession menuSession = new VisibleMenuSession();

    public DropCompanionTask(LocalPlayer player, DropItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                () -> PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                        : new Precondition.Failure("no " + r.label + " in inventory to drop",
                                FailureType.NO_MATERIAL));
    }

    @Override
    // 最多丢身上现有数量；请求十个但只找到三个时，本轮目标会降成三个并在完成文字中说明。
    protected void onStart() {
        Inventory inv = player.getInventory();
        int have = PlayerInv.count(inv, r.item);
        target = Math.min(r.count, have);
    }

    @Override
    // 先等上一次丢弃的菜单结果，确认后再累计数量并找下一堆；目标达到后关闭背包。
    protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            receipt = context.menus().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                fail("item drop was not confirmed: " + receipt.detail(), FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            receipt = null;
            dropped += pendingDrop;
            pendingDrop = 0;
            r.extendDeadlineTo(player.level().getGameTime() + DROP_PROGRESS_LEASE_TICKS);
        }
        if (dropped >= target) {
            doneMessage = "dropped " + dropped + "x " + r.label
                    + (dropped < r.count ? " (only had " + dropped + ")" : "");
            return menuSession.close(context) ? TaskState.SUCCESS : TaskState.RUNNING;
        }
        if (!menuSession.inventoryReady(context)) return TaskState.RUNNING;
        int inventorySlot = PlayerInv.findSlot(player.getInventory(), r.item);
        if (inventorySlot < 0) {
            fail("the item stack disappeared before all requested drops were confirmed",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        // 当前只转换快捷栏下标，错误地把盔甲／副手的 Inventory 下标直接当菜单槽号；可能丢错物品（A43）。
        int menuSlot = inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
        int before = player.getInventory().getItem(inventorySlot).getCount();
        // 整堆都需要丢时用“丢整堆”；只需其中一部分时一次丢一个，避免超过本轮目标。
        int button = before <= target - dropped ? 1 : 0;
        int expectedDrop = button == 1 ? before : 1;
        receipt = context.menus().click(context, menuSlot, button, ClickType.THROW,
                (c, ignored) -> c.player().getInventory().getItem(inventorySlot).getCount() < before
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING,
                20);
        // 先记本次打算丢的数量，确认后才累加；当前确认只要求来源格数量减少，并不验证准确减少量或地上实体。
        pendingDrop = expectedDrop;
        return TaskState.RUNNING;
    }

    @Override
    protected void cleanup() { menuSession.cleanup(player); receipt = null; }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("dropped", dropped);
        data.put("remaining_in_inventory", PlayerInv.count(player.getInventory(), r.item));
        return data;
    }

    @Override
    protected String successMessage() {
        return doneMessage;
    }

    @Override
    protected String timeoutMessage() {
        return "drop timed out unexpectedly";
    }

    @Override
    protected String cancelledMessage() {
        return "drop interrupted";
    }
}
