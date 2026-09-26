package org.maiwithu.maicraft.core.task;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.Objects;
import java.util.stream.Stream;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.VanillaHotbar;

/**
 * 供任务反复调用的“把这格物品拿到主手”步骤。
 * 快捷栏里的物品可以直接切换；背包里的物品先显示背包、搬到快捷栏，等确认后关界面再使用。
 * 返回 RUNNING 时还没准备好，调用方不能把它当成已经拿到手里。
 */
public final class FirstPersonActionGate {
    public enum Status { RUNNING, READY, FAILED }
    public record ConfirmedSwap(int source, int hotbar, ItemStack sourceBefore,
                                ItemStack hotbarBefore) {
        public ConfirmedSwap { sourceBefore = sourceBefore.copy(); hotbarBefore = hotbarBefore.copy(); }
        @Override public ItemStack sourceBefore() { return sourceBefore.copy(); }
        @Override public ItemStack hotbarBefore() { return hotbarBefore.copy(); }
    }

    private static final int CONFIRM_TICKS = 20;

    private MenuReceipt staging;
    private NativeActionReceipt selecting;
    private int requestedInventorySlot = -1;
    private int selectedHotbarSlot = -1;
    /** 已确认从槽位 S 交换到快捷栏 H；缓存中的 S 与重新发现的 H 都指向同一笔交易。 */
    private boolean stagedToHotbar;
    private boolean ready;
    private String failure = "selection was not confirmed";
    private VisibleMenuSession menuSession = new VisibleMenuSession();
    private LocalPlayer owner;
    private ConfirmedSwap pendingSwap, confirmedSwap;

    // 持续推进同一次物品选择：先确认背包交换，再关界面，最后确认快捷栏选中。
    // 交换后重新查找物品可能得到快捷栏的新编号，所以先把旧交换结算，再检查编号是否真的变了。
    public Status select(LocalPlayer player, int inventorySlot) {
        return select(ClientRuntime.requireContext(player), player, inventorySlot);
    }

    /**
     * 带调用方自己持有的操作入口推进同一次选择；复合调用方和回归测试可用同一套流程注入替身入口。
     * 入口必须仍属于该玩家，否则各步会如实返回 RUNNING 或 FAILED。
     */
    public Status select(LocalPlayerContext context, LocalPlayer player, int inventorySlot) {
        owner = player;
        // 确认从主背包交换到快捷栏后，重新查找物品的调用方必然会发现它换了位置（源槽 S 变为快捷栏 H）。
        // 应先结算该交易，再验证或比较重新发现的槽位；否则回执仍待处理时，会把正确的 S→H 变化误报为“选择目标已改变”。
        if (staging != null) {
            staging = context.menus().poll(context, staging);
            if (!staging.terminal()) return Status.RUNNING;
            if (staging.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                failure = "inventory staging was not confirmed: " + staging.detail() + swapEvidence(player);
                return Status.FAILED;
            }
            staging = null;
            confirmedSwap = pendingSwap; pendingSwap = null;
            stagedToHotbar = true;
            return Status.RUNNING; // 将选择操作作为后续 tick 的独立原生修改执行。
        }

        // 这里只支持背包和快捷栏前 36 格；副手、盔甲栏位不能经此方法搬到主手。
        if (inventorySlot < 0 || inventorySlot >= Math.min(36, player.getInventory().getContainerSize())) {
            failure = "inventory slot is unavailable: " + inventorySlot;
            return Status.FAILED;
        }
        boolean stagedAlias = stagedToHotbar && inventorySlot == selectedHotbarSlot;
        if (requestedInventorySlot != -1
                && requestedInventorySlot != inventorySlot
                && !stagedAlias) {
            failure = "selection target changed while a receipt was pending";
            return Status.FAILED;
        }
        if (requestedInventorySlot == -1) requestedInventorySlot = inventorySlot;
        // 已选好后仍要保证界面允许世界操作；ready 记的是选择已完成，没有重新核对该格后来是否换了物品。
        if (ready) return menuSession.worldReady(context) ? Status.READY : Status.RUNNING;

        if (selecting != null) {
            selecting = context.actions().poll(context, selecting);
            if (!selecting.terminal()) return Status.RUNNING;
            if (selecting.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                failure = "hotbar selection was not confirmed: " + selecting.detail();
                return Status.FAILED;
            }
            selecting = null;
            ready = true;
            return Status.RUNNING;
        }

        // 若调用方有意继续传入缓存的源槽 S，就不要把物品再交换回 H；已确认的交易已将 H 设为实际选择目标。
        if (stagedToHotbar) {
            if (!menuSession.close(context) || !context.mutationAvailable()) return Status.RUNNING;
            if (player.getInventory().selected == selectedHotbarSlot) {
                ready = true;
                return Status.READY;
            }
            selecting = context.actions().selectHotbar(
                    context, selectedHotbarSlot, CONFIRM_TICKS);
            return Status.RUNNING;
        }

        // 背包里的物品换到“当前手上那格”；扩展快捷栏模组（如 HotBaaaar）会把 selected 抬到 9 以上，
        // 而原版 SWAP 交换只认 0~8，先折回原版范围再发起交换，否则自卫选武会在收尾阶段崩溃。
        selectedHotbarSlot = inventorySlot < 9
                ? inventorySlot : VanillaHotbar.swapTarget(player.getInventory().selected);
        if (inventorySlot >= 9) {
            if (!menuSession.inventoryReady(context)) return Status.RUNNING;
            pendingSwap = new ConfirmedSwap(inventorySlot, selectedHotbarSlot,
                    player.getInventory().getItem(inventorySlot), player.getInventory().getItem(selectedHotbarSlot));
            staging = context.menus().swapInventoryToHotbar(
                    context, inventorySlot, selectedHotbarSlot, CONFIRM_TICKS);
            return Status.RUNNING;
        }
        if (!menuSession.worldReady(context)) return Status.RUNNING;
        if (player.getInventory().selected == selectedHotbarSlot) {
            ready = true;
            return Status.READY;
        }
        selecting = context.actions().selectHotbar(context, selectedHotbarSlot, CONFIRM_TICKS);
        return Status.RUNNING;
    }

    /** 交换未确认时保留两端实际物品与组件差异，后续诊断无需猜测是扣数、移槽还是动态组件变化。 */
    private String swapEvidence(LocalPlayer player) {
        if (pendingSwap == null) return "";
        var swap = pendingSwap;
        ItemStack sourceNow = player.getInventory().getItem(swap.source());
        ItemStack hotbarNow = player.getInventory().getItem(swap.hotbar());
        return "; source_slot=" + swap.source() + " before=" + identity(swap.sourceBefore()) + " now=" + identity(sourceNow)
                + "; hotbar_slot=" + swap.hotbar() + " before=" + identity(swap.hotbarBefore()) + " now=" + identity(hotbarNow)
                + "; source_components_vs_expected=" + changedComponents(sourceNow, swap.hotbarBefore())
                + "; hotbar_components_vs_expected=" + changedComponents(hotbarNow, swap.sourceBefore());
    }

    private static String identity(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount();
    }

    // 只公开发生变化的组件名，不倾倒容器内容或任意自定义文本；这些差异不改变严格交换确认规则。
    private static String changedComponents(ItemStack actual, ItemStack expected) {
        return Stream.concat(actual.getComponents().stream(), expected.getComponents().stream())
                .map(component -> component.type()).distinct()
                .filter(type -> !Objects.equals(actual.get(type), expected.get(type)))
                .map(type -> String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)))
                .sorted().limit(16).toList().toString();
    }

    public String failure() {
        return failure;
    }

    public boolean pending() { return staging != null || selecting != null; }
    public boolean started() { return requestedInventorySlot >= 0 || pending(); }
    public int requestedSlot() { return requestedInventorySlot; }
    public ConfirmedSwap takeConfirmedSwap() {
        ConfirmedSwap result = confirmedSwap; confirmedSwap = null; return result;
    }

    /** 关闭准备过程中打开的所有背包界面，包括交易中断后残留的界面。 */
    // 结束菜单会话并忘记本次选择；这里只清掉 selecting 变量，没有退役动作端口中可能仍待确认的快捷栏选择。
    public void reset() {
        if (owner != null) menuSession.cleanup(owner);
        menuSession = new VisibleMenuSession();
        owner = null;
        staging = null;
        selecting = null;
        pendingSwap = null; confirmedSwap = null;
        requestedInventorySlot = -1;
        selectedHotbarSlot = -1;
        stagedToHotbar = false;
        ready = false;
    }
}
