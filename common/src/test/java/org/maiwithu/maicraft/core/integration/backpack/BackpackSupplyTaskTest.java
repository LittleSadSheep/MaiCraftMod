// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import org.maiwithu.maicraft.core.task.container.ContainerTransferCompanionTask;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 回放开包、按回执入账、关包与未知效果停手；开包和搬运替身不构成真实模组服务器验收。 */
public final class BackpackSupplyTaskTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (var operation : BackpackSupplyTaskRecord.Operation.values()) run(operation, false);
        run(BackpackSupplyTaskRecord.Operation.WITHDRAW, true);
        wrongBackpack();
        System.out.println("BackpackSupplyTaskTest: passed");
    }
    // 开包会话已把开错的包放好鼠标物品并关闭：任务不读库存、不搬运、不再关包，以确定的 backpack_open_mismatch 结束。
    private static void wrongBackpack() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY); int[] closed = {0}, cancelled = {0}, reads = {0};
            var quartz = BuiltInRegistries.ITEM.getKey(Items.QUARTZ);
            var record = new BackpackSupplyTaskRecord("wrong", 10000, 8, BackpackSupplyTaskRecord.Operation.WITHDRAW, List.of(quartz), 3, Map.of());
            var access = new BackpackSupplyTask.Access() {
                public BackpackOpenSession.Status open(LocalPlayerContext context) { return BackpackOpenSession.Status.FAILED; }
                public BackpackOpenSession.Status close(LocalPlayerContext context) { closed[0]++; return BackpackOpenSession.Status.CLOSED; }
                public BackpackMenuAccess.Read read(LocalPlayer player) { reads[0]++; return new BackpackMenuAccess.Read("not_open", "fixture", null); }
                public void cancel(LocalPlayerContext context) { cancelled[0]++; }
                public String failure() { return "opened menu does not match the initiating carried backpack: native menu opened main::35 instead of main::8"; }
                public boolean uncertain() { return false; }
                public boolean wrongMenuClosed() { return true; }
                public Map<String, Object> settlement() { return Map.of("wrong_menu_closed", true, "cursor_placements", List.of()); }
            };
            var task = new BackpackSupplyTask(h.player, record, access); task.start(h.player); var state = TaskState.RUNNING;
            for (int i = 0; i < 4 && !state.isTerminal(); i++) { h.nextTick(); state = task.tick(h.player); }
            var result = task.result(state);
            check(state == TaskState.FAILED && "backpack_open_mismatch".equals(result.data().get("failure_code"))
                    && Boolean.TRUE.equals(result.data().get("menu_closed")) && Boolean.FALSE.equals(result.data().get("outcome_uncertain")),
                    "a settled wrong backpack is a certain, closed failure the caller may move past");
            check(closed[0] == 0 && cancelled[0] == 0 && reads[0] == 0 && result.data().containsKey("wrong_menu_settlement"),
                    "the wrong backpack is neither read nor closed twice, and its settlement stays in the receipt");
        }
    }
    private static void run(BackpackSupplyTaskRecord.Operation operation, boolean uncertain) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            var storage = new SimpleContainer(1); storage.setItem(0, new ItemStack(Items.QUARTZ)); storage.getItem(0).setCount(512);
            var menu = new Fixture(h.player, storage); menu.setCarried(ItemStack.EMPTY);
            var quartz = BuiltInRegistries.ITEM.getKey(Items.QUARTZ); int[] closed = {0}, cancelled = {0}, transfers = {0};
            if (operation == BackpackSupplyTaskRecord.Operation.DEPOSIT) h.inventory.setItem(0, new ItemStack(Items.QUARTZ, 15));
            var record = new BackpackSupplyTaskRecord("fixture", 10000, 2, operation,
                    operation == BackpackSupplyTaskRecord.Operation.WITHDRAW ? List.of(quartz) : List.of(),
                    operation == BackpackSupplyTaskRecord.Operation.WITHDRAW ? 15 : 0,
                    operation == BackpackSupplyTaskRecord.Operation.DEPOSIT ? Map.of(quartz, 10) : Map.of());
            // 先加载原生任务单登记，再用一次性替身重放“已确认”或“不确定”回执。
            new ContainerTransferTaskRecord("register", 1000, 1, List.of(new ContainerTransferTaskRecord.Move(0, 1, 1)), false);
            TaskFactory.register(ContainerTransferTaskRecord.class, (player, request) -> new Task() {
                public TaskState tick(LocalPlayer ignored) {
                    transfers[0]++; var move = request.moves.getFirst();
                    if (uncertain) { menu.setCarried(new ItemStack(Items.QUARTZ)); return TaskState.FAILED; }
                    menu.getSlot(move.from()).getItem().shrink(move.count());
                    if (menu.getSlot(move.to()).getItem().isEmpty()) menu.getSlot(move.to()).set(new ItemStack(Items.QUARTZ, move.count()));
                    else menu.getSlot(move.to()).getItem().grow(move.count());
                    return TaskState.SUCCESS;
                }
                public void stop(LocalPlayer ignored, StopReason reason) {}
                public String name() { return "confirmed backpack transfer fixture"; }
                public TaskResult result(TaskState state) { return uncertain ? TaskResult.fail("fixture unsettled", Map.of("outcome_uncertain", true))
                        : TaskResult.ok("fixture transferred", Map.of("moved_counts", List.of(request.moves.getFirst().count()), "outcome_uncertain", false)); }
            });
            var access = new BackpackSupplyTask.Access() {
                public BackpackOpenSession.Status open(LocalPlayerContext context) { h.player.containerMenu = menu; return BackpackOpenSession.Status.READY; }
                public BackpackOpenSession.Status close(LocalPlayerContext context) { closed[0]++; h.player.containerMenu = h.player.inventoryMenu; return BackpackOpenSession.Status.CLOSED; }
                public BackpackMenuAccess.Read read(LocalPlayer player) {
                    return new BackpackMenuAccess.Read("observed", "fixture", BackpackMenuAccess.capture(player, menu, "bag-fixture", ItemStack.EMPTY,
                            1, i -> i == 0, i -> false, i -> false));
                }
                public void cancel(LocalPlayerContext context) { cancelled[0]++; }
                public String failure() { return null; }
                public boolean uncertain() { return false; }
            };
            var task = new BackpackSupplyTask(h.player, record, access); task.start(h.player); var state = TaskState.RUNNING;
            for (int i = 0; i < 12 && !state.isTerminal(); i++) { h.nextTick(); state = task.tick(h.player); }
            var result = task.result(state);
            check(result.success() != uncertain && (boolean) result.data().get("outcome_uncertain") == uncertain, "uncertain transfers cannot become successful supplies");
            check(closed[0] == (uncertain ? 0 : 1) && cancelled[0] == 0, "only settled transactions close their menu exactly once");
            check(transfers[0] == (operation == BackpackSupplyTaskRecord.Operation.OBSERVE ? 0 : 1), "observation never extracts and transfer never repeats");
            if (uncertain) check(menu.getCarried().is(Items.QUARTZ), "unsettled cursor remains available for recovery");
            else check(storage.getItem(0).getCount() == (operation == BackpackSupplyTaskRecord.Operation.WITHDRAW ? 497
                    : operation == BackpackSupplyTaskRecord.Operation.DEPOSIT ? 522 : 512), "opposite storage delta matches the confirmed transaction");
        } finally { TaskFactory.register(ContainerTransferTaskRecord.class, ContainerTransferCompanionTask::new); }
    }
    private static final class Fixture extends AbstractContainerMenu {
        Fixture(Player player, SimpleContainer storage) {
            super(MenuType.GENERIC_9x3, 8); addSlot(new Slot(storage, 0, 0, 0) { @Override public int getMaxStackSize(ItemStack stack) { return 1024; } });
            for (int i = 0; i < 36; i++) addSlot(new Slot(player.getInventory(), i, 0, 0));
        }
        public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        public boolean stillValid(Player player) { return true; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
