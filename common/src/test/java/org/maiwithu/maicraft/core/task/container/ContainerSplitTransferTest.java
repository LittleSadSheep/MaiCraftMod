package org.maiwithu.maicraft.core.task.container;

import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuPort;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.task.TaskState;

/** 用真实 Slot 取半堆／放回规则和延迟点击回执验证分堆；不把本地槽操作伪称为服务器网络验收。 */
public final class ContainerSplitTransferTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (int count : new int[]{49, 33, 29}) exactNativeSlots(64, count, 0);
        exactNativeSlots(63, 32, 7); guardedChangesAndCancellation(); sourceReturnPermission(); parentChoosesSplit(); parentCountsConfirmedClicks();
        System.out.println("ContainerSplitTransferTest: native slot halves, delayed receipts, capacity and foreign cursor guards passed");
    }
    private static void exactNativeSlots(int source, int amount, int destination) throws Exception {
        try (var fixture = new Fixture(source, amount, destination)) {
            // 真实箱子槽保存命名物品的组件；分堆不能把它变成另一种无组件的石砖。
            fixture.stock.getItem(0).set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Counted bricks"));
            if (destination > 0) fixture.menu.getSlot(27).set(fixture.stock.getItem(0).copyWithCount(destination));
            fixture.reset(amount);
            var status = fixture.step();
            check(fixture.clicks == 1 && fixture.menu.getCarried().isEmpty(), "submission must wait for its native slot confirmation");
            fixture.step(); check(fixture.clicks == 1, "a pending click must never be replayed");
            for (int tick = 0; tick < 100 && status == ContainerSplitTransfer.Status.RUNNING; tick++) {
                fixture.confirm(); status = fixture.step();
                check(fixture.menu.getSlot(27).getItem().getCount() <= destination + amount, "player destination never temporarily exceeds the request");
            }
            check(status == ContainerSplitTransfer.Status.COMPLETE && fixture.stock.getItem(0).getCount() == source - amount
                    && fixture.menu.getSlot(27).getItem().getCount() == destination + amount && fixture.menu.getCarried().isEmpty(),
                    "every confirmed split finishes with exact opposite source and destination deltas");
            check(fixture.clicks == ContainerSplitPlanner.plan(source, amount, 64).size(), "native execution must use the bounded planned number of clicks");
            check(!fixture.transfer.preserveMenu(), "a settled empty cursor allows normal owned GUI cleanup");
        }
    }
    private static void guardedChangesAndCancellation() throws Exception {
        try (var f = new Fixture(64, 29, 0)) {
            f.step(); check(f.transfer.preserveMenu(), "cancellation preserves a menu with an unresolved click");
            f.confirm(); f.menu.setCarried(new ItemStack(Items.DIAMOND));
            int sent = f.clicks;
            check(f.step() == ContainerSplitTransfer.Status.FAILED && f.clicks == sent && f.menu.getCarried().is(Items.DIAMOND)
                    && f.transfer.preserveMenu(), "foreign cursor is neither returned to the source nor closed away");
        }
        try (var f = new Fixture(64, 49, 0)) {
            f.step(); f.confirm(); f.step(); f.confirm();
            f.menu.getSlot(27).set(new ItemStack(Items.STONE_BRICKS, 31)); int sent = f.clicks;
            check(f.step() == ContainerSplitTransfer.Status.FAILED && f.clicks == sent, "an external destination debit cannot be absorbed into a new split plan");
        }
        try (var f = new Fixture(64, 29, 0)) {
            f.menu.slots.set(27, new Slot(f.world.inventory, 9, 0, 0) {
                @Override public int getMaxStackSize(ItemStack stack) { return 16; }
            });
            // 即使源格还能取半堆，整笔目标容量不足也必须在任何拾起前停下。
            var low = new ContainerTransferCompanionTask(f.world.player,
                    new ContainerTransferTaskRecord("split-capacity", 1000, f.menu.containerId, List.of(f.move), false));
            var begin = ContainerTransferCompanionTask.class.getDeclaredMethod("beginMove", ContainerTransferTaskRecord.Move.class); begin.setAccessible(true);
            check(begin.invoke(low, f.move) == TaskState.FAILED && f.clicks == 0 && f.menu.getCarried().isEmpty(), "native destination capacity gates the entire request");
        }
    }
    private static void sourceReturnPermission() throws Exception {
        try (var f = new Fixture(64, 29, 0)) {
            f.menu.slots.set(0, new Slot(f.stock, 0, 0, 0) { @Override public boolean mayPlace(ItemStack stack) { return false; } });
            boolean rejected = false;
            try { f.reset(29); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && f.clicks == 0 && f.stock.getItem(0).getCount() == 64, "a read-only output slot rejects an impossible remainder before pickup");
            f.reset(32); var status = f.step();
            for (int i = 0; i < 8 && status == ContainerSplitTransfer.Status.RUNNING; i++) { f.confirm(); status = f.step(); }
            check(status == ContainerSplitTransfer.Status.COMPLETE && f.clicks == 2, "a half that needs no return remains usable from an output slot");
        }
    }
    private static void parentChoosesSplit() throws Exception {
        try (var f = new Fixture(64, 49, 0)) {
            var parent = new ContainerTransferCompanionTask(f.world.player,
                    new ContainerTransferTaskRecord("split-wiring", 1000, f.menu.containerId, List.of(f.move), false));
            var begin = ContainerTransferCompanionTask.class.getDeclaredMethod("beginMove", ContainerTransferTaskRecord.Move.class); begin.setAccessible(true);
            check(begin.invoke(parent, f.move) == TaskState.RUNNING, "the original task accepts the split quantity");
            var phase = ContainerTransferCompanionTask.class.getDeclaredField("phase"); phase.setAccessible(true);
            check(phase.get(parent).toString().equals("SPLIT"), "partial requests must reach the new executor");
            check(begin.invoke(parent, new ContainerTransferTaskRecord.Move(0, -1, 64)) == TaskState.RUNNING
                    && phase.get(parent).toString().equals("QUICK"), "whole-stack requests retain native quick move");
        }
    }
    private static void parentCountsConfirmedClicks() throws Exception {
        try (var f = new Fixture(64, 49, 0)) {
            var parent = new ContainerTransferCompanionTask(f.world.player,
                    new ContainerTransferTaskRecord("split-evidence", 1000, f.menu.containerId, List.of(f.move), false));
            var begin = ContainerTransferCompanionTask.class.getDeclaredMethod("beginMove", ContainerTransferTaskRecord.Move.class); begin.setAccessible(true);
            var tick = ContainerTransferCompanionTask.class.getDeclaredMethod("splitTick", LocalPlayerContext.class); tick.setAccessible(true);
            begin.invoke(parent, f.move); tick.invoke(parent, f.context);
            check(parent.resultData().get("confirmed_split_clicks").equals(0), "a submitted click is not a confirmed click");
            for (int i = 0; i < 7; i++) {
                f.confirm(); tick.invoke(parent, f.context);
                check(parent.resultData().get("confirmed_split_clicks").equals(i + 1), "each confirmed receipt contributes exactly one click");
            }
            check(parent.resultData().get("moved_counts").equals(List.of(49)), "optimized clicks still report the exact original move quantity");
        }
    }
    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final SimpleContainer stock = new SimpleContainer(27);
        final ChestMenu menu;
        final LocalPlayerContext context;
        ContainerTransferTaskRecord.Move move;
        ContainerSplitTransfer transfer;
        MenuReceipt pending;
        MenuConfirmation confirmation;
        int slot, button, clicks;
        long tick;
        Fixture(int source, int amount, int destination) throws Exception {
            stock.setItem(0, new ItemStack(Items.STONE_BRICKS, source));
            menu = ChestMenu.threeRows(55, world.inventory, stock); world.player.containerMenu = menu;
            if (destination > 0) menu.getSlot(27).set(new ItemStack(Items.STONE_BRICKS, destination));
            MenuPort port = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class}, (proxy, method, args) -> switch (method.getName()) {
                case "click" -> {
                    check(pending == null || pending.terminal(), "only one click may await confirmation");
                    check(args[3] == ClickType.PICKUP && ((int) args[1] == 0 || (int) args[1] == 27), "only ordinary clicks on the two declared slots are permitted");
                    slot = (int) args[1]; button = (int) args[2]; confirmation = (MenuConfirmation) args[4]; clicks++;
                    var constructor = MenuReceipt.class.getDeclaredConstructor(MenuReceipt.Kind.class, LocalPlayerContext.class,
                            int.class, int.class, int.class, boolean.class, MenuConfirmation.class); constructor.setAccessible(true);
                    pending = constructor.newInstance(MenuReceipt.Kind.CLICK, args[0], menu.containerId, 0, 20, false, confirmation); yield pending;
                }
                case "poll" -> args[1];
                default -> throw new AssertionError("unexpected menu call " + method.getName());
            });
            context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                case "player" -> world.player;
                case "menus" -> port;
                case "mutationAvailable" -> true;
                case "bodyEpoch", "controlRevision" -> 1L;
                case "tickRevision" -> tick;
                default -> throw new AssertionError("unexpected context access " + method.getName());
            });
            reset(amount);
        }
        void reset(int amount) { move = new ContainerTransferTaskRecord.Move(0, 27, amount); transfer = new ContainerSplitTransfer(world.player, menu, move, amount); }
        ContainerSplitTransfer.Status step() { tick++; return transfer.tick(context); }
        void confirm() throws Exception {
            if (pending == null || pending.terminal()) return;
            Slot target = menu.getSlot(slot); ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) {
                int amount = button == 0 ? target.getItem().getCount() : (target.getItem().getCount() + 1) / 2;
                menu.setCarried(target.safeTake(amount, Integer.MAX_VALUE, world.player));
            } else menu.setCarried(target.safeInsert(carried, button == 0 ? carried.getCount() : 1));
            check(confirmation.observe(context, pending) == MenuConfirmation.Verdict.APPLIED, "the actual native Slot result must satisfy all three count checks");
            var finish = MenuReceipt.class.getDeclaredMethod("finish", MenuReceipt.Status.class, String.class); finish.setAccessible(true);
            finish.invoke(pending, MenuReceipt.Status.CONFIRMED_APPLIED, "native slot fixture confirmed");
        }
        @Override public void close() throws Exception { world.close(); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
