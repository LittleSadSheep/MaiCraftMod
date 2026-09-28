// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySourcesTest;
import org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 通过回执驱动协调器；模拟回执不代表真实 GUI 已接受物品转移。 */
public final class InventoryDepositCoordinatorTest {
    // 多个箱子分担余料时只累计真实存入的数量，玩家留下的游标和工具都不能被当作可丢弃的垃圾。
    private static final ResourceLocation DIRT = ResourceLocation.parse("minecraft:dirt");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        partialContainersDepositOnlyTheProvenAmount();
        preservesRetainedStockAndForeignCursor();
        rejectsUnrelatedOrUnbalancedReceipts();
        carriedStorageDoesNotWalkToAnOrdinaryBarrel();
    }
    private static void carriedStorageDoesNotWalkToAnOrdinaryBarrel() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 夹具没有执行原生菜单构造器，先补齐真实游戏中本来为空的鼠标栈。
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            // 身边有已知土料桶也不能突破“只用随身仓库”；没有终端时不生成去桶边的子任务。
            var entities = ContainerSupplySourcesTest.worldEntities(h);
            ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(3, 1, 3));
            h.inventory.setItem(0, new ItemStack(Items.DIRT, 10));
            var supply = new InventoryDepositCoordinator(); supply.beginCarried(h.player, "carried-tidy", 1000, Map.of(DIRT, 6));
            var tick = supply.tick(h.player, ignored -> { throw new AssertionError("carried-only storage cannot open an ordinary barrel"); });
            check(tick.status() == InventoryDepositCoordinator.Status.FAILED && h.inventory.getItem(0).getCount() == 10
                    && "carried_storage_only".equals(tick.receipt().get("storage_access_scope")), "no available carried access leaves inventory unchanged");
            var carried = InventoryDepositCoordinator.aeDepositRequest(Map.of(DIRT, 6), true);
            var normal = InventoryDepositCoordinator.aeDepositRequest(Map.of(DIRT, 6), false);
            check(carried.wirelessOnly() && !carried.allowCrafting() && carried.totalCount() == 6 && !normal.wirelessOnly(),
                    "the actual AE request binds exact deposit counts and carried access");
        }
    }
    private static void partialContainersDepositOnlyTheProvenAmount() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            var entities = ContainerSupplySourcesTest.worldEntities(h); BlockPos first = new BlockPos(3, 1, 3), second = new BlockPos(6, 1, 3);
            ContainerSupplySourcesTest.addBarrel(h, entities, first); ContainerSupplySourcesTest.addBarrel(h, entities, second);
            // 自动整理只续用已知土料箱，空的陌生箱子不因还有容量就成为倾倒目标。
            ContainerSupplySourcesTest.rememberContents(h, first, DIRT, 1);
            ContainerSupplySourcesTest.rememberContents(h, second, DIRT, 1);
            h.inventory.setItem(0, new ItemStack(Items.DIRT, 10)); h.inventory.setItem(1, new ItemStack(Items.DIAMOND_PICKAXE));
            var supply = new InventoryDepositCoordinator(); supply.begin(h.player, "spoil", 1000, Map.of(DIRT, 6), List.of(), 16);
            supply.tick(h.player, ignored -> { throw new AssertionError("selection should only prepare a child"); });
            var record = (SemanticContainerTaskRecord) field(supply, "childRecord");
            check(record.supplyPosition.equals(first) && record.operation == SemanticContainerTaskRecord.Operation.DEPOSIT && record.count == 6,
                    "first warehouse receives an exact-position deposit bounded by proven spoil");
            replace(supply, "child", new SettledDeposit(2)); h.inventory.getItem(0).setCount(8);
            supply.tick(h.player, task -> task.tick(h.player));
            supply.tick(h.player, ignored -> { throw new AssertionError("next warehouse selection should not drive a body action"); });
            record = (SemanticContainerTaskRecord) field(supply, "childRecord");
            check(record.supplyPosition.equals(second) && record.count == 4, "partial capacity continues with only the confirmed remainder at another warehouse");
            replace(supply, "child", new SettledDeposit(4)); h.inventory.getItem(0).setCount(4);
            supply.tick(h.player, task -> task.tick(h.player));
            var done = supply.tick(h.player, ignored -> { throw new AssertionError("completed spoil must not start more work"); });
            check(done.status() == InventoryDepositCoordinator.Status.DEPOSITED && h.inventory.getItem(0).getCount() == 4
                    && h.inventory.getItem(1).is(Items.DIAMOND_PICKAXE), "initial/retained materials and tools survive the deposit cycle");
            check(done.receipt().get("confirmed_deposited").equals(Map.of("minecraft:dirt", 6)), "only native-receipt-confirmed amounts are reported deposited");
            check(h.blockUses() == 0 && h.itemUses() == 0, "coordinator has no direct world, drop or menu actions outside its delegated GUI child");
        }
    }
    private static void preservesRetainedStockAndForeignCursor() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY); h.inventory.setItem(0, new ItemStack(Items.DIRT, 10));
            var supply = new InventoryDepositCoordinator(); supply.begin(h.player, "retained", 1000, Map.of(DIRT, 6), List.of(), 16);
            h.inventory.getItem(0).setCount(9);
            var changed = supply.tick(h.player, ignored -> { throw new AssertionError("retained inventory loss must prevent GUI work"); });
            check(changed.status() == InventoryDepositCoordinator.Status.FAILED
                    && changed.receipt().get("failure_code").equals("excavation_spoil_inventory_changed_before_deposit"), "inventory loss cannot be hidden by depositing retained items");
            h.inventory.getItem(0).setCount(10); supply.begin(h.player, "cursor", 1000, Map.of(DIRT, 6), List.of(), 16);
            h.player.inventoryMenu.setCarried(new ItemStack(Items.DIRT));
            check(supply.tick(h.player, ignored -> { throw new AssertionError("foreign cursor must not be touched"); }).status() == InventoryDepositCoordinator.Status.FAILED
                    && h.player.inventoryMenu.getCarried().getCount() == 1, "stranger cursor is neither cleared nor used as spoil");
            check(h.blockUses() == 0 && h.itemUses() == 0, "rejection does not discard or move any items");
        }
    }
    private static void rejectsUnrelatedOrUnbalancedReceipts() {
        check(InventoryDepositCoordinator.verifiedCount(receipt(3), DIRT, 4) == 3, "bounded exact deposit receipt accepted");
        rejects(receipt(5), DIRT, 4);
        rejects(Map.of("operation", "withdraw", "bounded_storage_deposit", true, "moved_count", 1, "moved_items", Map.of("minecraft:dirt", 1)), DIRT, 4);
        rejects(Map.of("operation", "deposit", "bounded_storage_deposit", true, "moved_count", 2, "moved_items", Map.of("minecraft:dirt", 1)), DIRT, 4);
        rejects(Map.of("operation", "deposit", "bounded_storage_deposit", true, "moved_count", 1, "moved_items", Map.of("minecraft:diamond", 1)), DIRT, 4);
    }
    private static Map<String, Object> receipt(int count) { return Map.of("operation", "deposit", "bounded_storage_deposit", true,
            "moved_count", count, "moved_items", Map.of("minecraft:dirt", count), "outcome_uncertain", false, "effects_started", count > 0); }
    private static final class SettledDeposit implements Task {
        final int count; SettledDeposit(int count) { this.count = count; }
        public TaskState tick(LocalPlayer player) { return TaskState.SUCCESS; }
        public void stop(LocalPlayer player, StopReason reason) {}
        public TaskResult result(TaskState state) { return new TaskResult(true, "simulated settled visible deposit", false, false, receipt(count)); }
        public String name() { return "settled GUI deposit fixture"; }
    }
    private static void rejects(Map<String, Object> data, ResourceLocation item, int maximum) { try { InventoryDepositCoordinator.verifiedCount(data, item, maximum); throw new AssertionError("invalid deposit accepted"); } catch (IllegalArgumentException expected) { } }
    private static Object field(Object target, String name) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void replace(Object target, String name, Object value) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
