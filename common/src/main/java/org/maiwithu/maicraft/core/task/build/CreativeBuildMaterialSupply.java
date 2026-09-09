package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;

/**
 * 负责创造模式的取料和清理动作：打开可见背包、申请原版改槽、等待确认，再更新材料归属。
 * 材料可跨多个施工格保留，之后还要用就不每放一块取还一次。
 */
final class CreativeBuildMaterialSupply {
    enum Status { READY, WAITING, FAILED }
    private final CreativeBuildInventory inventory = new CreativeBuildInventory();
    private VisibleMenuSession menu = new VisibleMenuSession();
    private NativeActionReceipt receipt;
    private int pendingSlot = -1, selectedSlot = -1;
    private ItemStack expected = ItemStack.EMPTY;
    private String failure, phase = "idle";
    private boolean uncertain;
    private org.maiwithu.maicraft.core.FailureType failureType = org.maiwithu.maicraft.core.FailureType.UNKNOWN;
    private Map<String, Integer> retained = Map.of();

    // 先等待上一次改槽结束，再找所需材料。已有材料可直接选用；需要腾位时先清空自有材料，下次再取新材料。
    Status ensure(LocalPlayerContext context, Item wanted, ToIntFunction<Item> nextUse) {
        Status settled = settle(context);
        if (settled != Status.READY) return settled;
        var snapshot = snapshot(context.player());
        var selection = inventory.choose(wanted, snapshot, nextUse);
        observe(snapshot);
        if (selection.kind() == CreativeBuildInventory.Kind.EXISTING) {
            selectedSlot = selection.slot();
            if (!menu.close(context)) return Status.WAITING;
            menu = new VisibleMenuSession();
            phase = "ready"; return Status.READY;
        }
        if (selection.kind() == CreativeBuildInventory.Kind.BLOCKED) {
            failureType = org.maiwithu.maicraft.core.FailureType.NO_SPACE;
            return fail("creative inventory has no empty slot or unchanged task-owned material to evict", false);
        }
        ItemStack after = selection.kind() == CreativeBuildInventory.Kind.EVICT
                ? ItemStack.EMPTY : new ItemStack(wanted);
        return write(context, selection, after);
    }

    // 一次只清理一个不再需要的自有材料；全部处理完才关闭本任务打开的背包界面。
    Status releaseUnused(LocalPlayerContext context, ToIntFunction<Item> nextUse) {
        Status settled = settle(context);
        if (settled != Status.READY) return settled;
        var snapshot = snapshot(context.player());
        var selection = inventory.unused(snapshot, nextUse); observe(snapshot);
        if (selection.kind() == CreativeBuildInventory.Kind.BLOCKED) {
            phase = "ready";
            if (!menu.close(context)) return Status.WAITING;
            menu = new VisibleMenuSession(); return Status.READY;
        }
        return write(context, selection, ItemStack.EMPTY);
    }

    // 背包必须已显示、本刻允许修改，且槽位仍与选择时一致，才提交一次改槽；不满足就留待下次。
    private Status write(LocalPlayerContext context, CreativeBuildInventory.Selection selection, ItemStack after) {
        if (!menu.inventoryReady(context) || !context.mutationAvailable()) return Status.WAITING;
        ItemStack before = context.player().getInventory().getItem(selection.slot());
        if (before.getCount() != selection.expected().getCount()
                || !ItemStack.isSameItemSameComponents(before, selection.expected())) return Status.WAITING;
        pendingSlot = selection.slot(); expected = after.copy();
        phase = after.isEmpty() ? "releasing_unused_material" : "taking_material";
        receipt = context.actions().creativeSetSlot(context, pendingSlot, expected, 30);
        return Status.WAITING;
    }

    // 只有确认已应用才继续；结果不确定或变成了别的内容时停止，避免在未结清的槽位上接着改。
    private Status settle(LocalPlayerContext context) {
        if (failure != null) return Status.FAILED;
        if (receipt == null) return Status.READY;
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return Status.WAITING;
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED)
            return fail("creative slot update was not confirmed: " + receipt.detail(),
                    receipt.status() == NativeActionReceipt.Status.UNCERTAIN || receipt.status() == NativeActionReceipt.Status.DIVERGED);
        var snapshot = snapshot(context.player());
        if (!expected.isEmpty() && !inventory.confirmedCreated(pendingSlot, expected, snapshot))
            return fail("creative slot changed after its confirmed update", true);
        inventory.reconcile(snapshot); observe(snapshot);
        receipt = null; pendingSlot = -1; expected = ItemStack.EMPTY;
        return Status.READY;
    }

    /** 选择器把材料换到快捷栏后，先转移归属记录，再读取新的背包快照。 */
    void swapped(LocalPlayer player, FirstPersonActionGate.ConfirmedSwap swap) {
        if (swap == null) return;
        var snapshot = snapshot(player);
        inventory.confirmedSwap(swap.source(), swap.hotbar(), swap.sourceBefore(), swap.hotbarBefore(), snapshot);
        observe(snapshot);
    }

    boolean hasOwned(LocalPlayer player) {
        var snapshot = snapshot(player); boolean any = !inventory.owned(snapshot).isEmpty(); observe(snapshot); return any;
    }
    int slot() { return selectedSlot; }
    String failure() { return failure; }
    boolean uncertain() { return uncertain; }
    org.maiwithu.maicraft.core.FailureType failureType() { return failureType; }

    /** 失败或取消时结束尚未结清的改槽并清理界面；这里保留背包材料，不再另发清空槽位操作。 */
    void stop(LocalPlayer player) {
        if (receipt != null && !receipt.terminal()) {
            try {
                var context = ClientRuntime.requireContext(player);
                context.actions().retireOneShotForTaskBoundary(context, receipt, "creative construction cache stopped");
            } catch (RuntimeException unavailable) { /* The actor owns revocation after body loss. */ }
        }
        menu.cleanup(player);
        observe(snapshot(player));
    }

    Map<String, Object> progress() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase); data.put("cached_material_types", retained.size());
        if (receipt != null) data.put("slot_update_pending", !receipt.terminal());
        return Map.copyOf(data);
    }
    Map<String, Integer> retained() { return retained; }

    private Status fail(String reason, boolean uncertain) {
        failure = reason; this.uncertain = uncertain; phase = "failed"; return Status.FAILED;
    }
    // 汇总仍属本任务的材料，供进度和最终结果说明“还留了什么”；不把玩家物品算进去。
    private void observe(List<ItemStack> snapshot) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (var entry : inventory.owned(snapshot)) {
            var stack = entry.expected();
            items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        retained = Map.copyOf(items);
    }
    // 复制普通背包的物品，避免随后换槽或数量变化把之前的比较依据一起改掉。
    static List<ItemStack> snapshot(LocalPlayer player) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < Math.min(36, player.getInventory().getContainerSize()); slot++)
            result.add(player.getInventory().getItem(slot).copy());
        return List.copyOf(result);
    }
}
