// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.dimension.PortalPreparationPolicy;
import org.maiwithu.maicraft.core.task.inventory.DropItemsTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** spoil_policy=drop 的余土丢弃通道：只丢核实的普通余土、按背包净减量对账；默认不传参数仍走存入通道。 */
public final class SpoilDropCoordinatorTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("minecraft:dirt");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        DropItemsTaskRecord.ensureRegistered();
        dropsOnlyProvenOrdinarySurplus();
        rejectsForeignOrUncarriedSpoil();
        failsWithoutRetryingOnUnconfirmedZeroDecrease();
        defaultPolicyStaysDepositOnly();
    }

    /** 已核实的余土整份交给丢弃子任务，按净减量入账；自留底线之外的随身工具不进清单。 */
    private static void dropsOnlyProvenOrdinarySurplus() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            h.inventory.setItem(0, new ItemStack(Items.DIRT, 10));
            h.inventory.setItem(1, new ItemStack(Items.DIAMOND_PICKAXE));
            var drop = new SpoilDropCoordinator();
            drop.begin(h.player, "spoil-drop", 1000, Map.of(DIRT, 6));
            drop.tick(h.player, ignored -> { throw new AssertionError("selection should only prepare a child"); });
            var record = (DropItemsTaskRecord) field(drop, "childRecord");
            check(record.item == Items.DIRT && record.count == 6, "the drop child carries the exact proven surplus");
            // 子任务完成后按背包净减量对账，不读回执里的投掷数。
            h.inventory.getItem(0).setCount(4);
            replace(drop, "child", new SettledDrop(false));
            drop.tick(h.player, task -> task.tick(h.player));
            var done = drop.tick(h.player, ignored -> { throw new AssertionError("completed spoil must not start more work"); });
            check(done.status() == SpoilDropCoordinator.Status.DROPPED && h.inventory.getItem(0).getCount() == 4
                    && h.inventory.getItem(1).is(Items.DIAMOND_PICKAXE), "only the surplus left the inventory; tools and floor survive");
            check(done.receipt().get("confirmed_dropped").equals(Map.of("minecraft:dirt", 6))
                    && Boolean.FALSE.equals(done.receipt().get("outcome_uncertain"))
                    && "drop".equals(done.receipt().get("spoil_policy")), "the receipt accounts net inventory decrease as dropped spoil");
            check(h.blockUses() == 0 && h.itemUses() == 0, "coordinator delegates all world actions to the drop child");
        }
    }

    /** 非普通物品、带授权外数量或不在背包里的数量都拒绝接单。 */
    private static void rejectsForeignOrUncarriedSpoil() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            h.inventory.setItem(0, new ItemStack(Items.DIRT, 4));
            var drop = new SpoilDropCoordinator();
            rejects(() -> drop.begin(h.player, "foreign", 1000, Map.of(ResourceLocation.parse("minecraft:diamond"), 1)),
                    "tools and treasures are not spoil");
            rejects(() -> drop.begin(h.player, "uncarried", 1000, Map.of(DIRT, 5)),
                    "counts above the carried amount are not proven spoil");
        }
    }

    /** 零减量且回执不确定时保留现场失败，不自动重发；背包先变了的取单直接拒绝。 */
    private static void failsWithoutRetryingOnUnconfirmedZeroDecrease() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            h.inventory.setItem(0, new ItemStack(Items.DIRT, 10));
            var drop = new SpoilDropCoordinator();
            drop.begin(h.player, "uncertain", 1000, Map.of(DIRT, 6));
            drop.tick(h.player, ignored -> { throw new AssertionError("selection should only prepare a child"); });
            replace(drop, "child", new SettledDrop(true));
            var tick = drop.tick(h.player, task -> task.tick(h.player));
            check(tick.status() == SpoilDropCoordinator.Status.FAILED
                    && Boolean.TRUE.equals(tick.receipt().get("outcome_uncertain"))
                    && "excavation_spoil_drop_unsettled".equals(tick.receipt().get("failure_code"))
                    && h.inventory.getItem(0).getCount() == 10, "uncertain zero decrease keeps the scene and fails honestly");
            // 接单后背包数量跌破自留底线：余土与自留的划分已失真，拒发投掷。
            var changed = new SpoilDropCoordinator();
            h.inventory.getItem(0).setCount(12);
            changed.begin(h.player, "changed", 1000, Map.of(DIRT, 6));
            h.inventory.getItem(0).setCount(9);
            var failed = changed.tick(h.player, ignored -> { throw new AssertionError("changed inventory must not spawn drops"); });
            check(failed.status() == SpoilDropCoordinator.Status.FAILED
                    && "excavation_spoil_inventory_changed_before_drop".equals(failed.receipt().get("failure_code")),
                    "inventory below the retained floor blocks the drop batch");
        }
    }

    /** 默认参数与非法值：余土处置保持存入容器，丢弃只来自显式授权。 */
    private static void defaultPolicyStaysDepositOnly() {
        check(PortalPreparationPolicy.parse(new JsonObject()).spoilPolicy() == BuildTaskRecord.SpoilPolicy.DEPOSIT,
                "missing spoil_policy keeps the deposit-only default");
        JsonObject drop = new JsonObject(); drop.addProperty("spoil_policy", "drop");
        check(PortalPreparationPolicy.parse(drop).spoilPolicy() == BuildTaskRecord.SpoilPolicy.DROP,
                "explicit drop authorization is parsed and propagated");
        JsonObject invalid = new JsonObject(); invalid.addProperty("spoil_policy", "burn");
        rejects(() -> PortalPreparationPolicy.checkSpoilPolicy(invalid), "invalid enum value cannot pass plan validation");
    }

    private static final class SettledDrop implements Task {
        final boolean uncertain; SettledDrop(boolean uncertain) { this.uncertain = uncertain; }
        public TaskState tick(LocalPlayer player) { return TaskState.SUCCESS; }
        public void stop(LocalPlayer player, StopReason reason) {}
        public TaskResult result(TaskState state) {
            return new TaskResult(true, "simulated settled drop", false, uncertain,
                    Map.of("item", "dirt", "dropped", 0, "outcome_uncertain", uncertain));
        }
        public String name() { return "settled drop fixture"; }
    }

    private static void rejects(Runnable action, String message) {
        try { action.run(); throw new AssertionError("expected rejection: " + message); }
        catch (IllegalArgumentException expected) { }
    }
    private static Object field(Object target, String name) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void replace(Object target, String name, Object value) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
