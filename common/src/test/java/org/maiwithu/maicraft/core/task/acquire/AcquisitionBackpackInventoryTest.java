// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.ae2.Ae2SupplyTaskRecord;
import org.maiwithu.maicraft.core.integration.backpack.BackpackSupplyTaskRecord;
import org.maiwithu.maicraft.core.integration.backpack.BackpackCarriers.Carrier;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 随身背包先于AE和世界采集；真正没货才换包，读取失败不能被当成空库存。 */
public final class AcquisitionBackpackInventoryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        restrictedPickupKeepsStorageClosed(false);
        restrictedPickupKeepsStorageClosed(true);
        for (String scenario : List.of("available", "second_bag", "unreadable", "uncertain", "worn")) run(scenario);
        check(!AcquisitionBackpackInventory.permitted(List.of(SemanticAcquireTaskRecord.Source.MINE)), "mine-only does not open carried storage");
        check(!AcquisitionBackpackInventory.permitted(List.of(SemanticAcquireTaskRecord.Source.WIRELESS)), "wireless-only stays at AE");
        check(AcquisitionBackpackInventory.permitted(List.of(SemanticAcquireTaskRecord.Source.STORAGE)), "storage permission includes one's carried backpack");
        System.out.println("AcquisitionBackpackInventoryTest: passed");
    }
    private static void restrictedPickupKeepsStorageClosed(boolean alreadyCarried) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 只准捡附近掉落时，先核对主背包；不足就检查地面，不能把数量核对扩写成打开随身背包。
            if (alreadyCarried) h.inventory.setItem(0, new ItemStack(Items.OBSIDIAN));
            var request = new SemanticAcquireTaskRecord("nearby-with-carried-bag", 1000,
                    List.of(ResourceLocation.withDefaultNamespace("obsidian")), 1,
                    List.of(SemanticAcquireTaskRecord.Source.NEARBY), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(h.player, request,
                    player -> { throw new AssertionError("仅附近拾取不能查询无线终端"); },
                    new AcquisitionInventoryTidy(Set::copyOf),
                    new AcquisitionBackpackInventory(player -> { throw new AssertionError("仅附近拾取不能打开随身背包"); }));
            task.onStart(); var state = TaskState.RUNNING;
            for (int tick = 0; tick < 10 && !state.isTerminal(); tick++) state = task.onTick();
            check(state == (alreadyCarried ? TaskState.SUCCESS : TaskState.FAILED),
                    "已有物品直接达标；附近没有掉落时如实结束，不绕去背包取货");
            check(h.itemUses() == 0 && h.blockUses() == 0, "补拾取检查不能额外右键物品或方块");
        }
    }
    @SuppressWarnings("unchecked") private static void run(String scenario) throws Exception {
        Class.forName(BackpackSupplyTaskRecord.class.getName()); Class.forName(Ae2SupplyTaskRecord.class.getName());
        var field = TaskFactory.class.getDeclaredField("RUNNERS"); field.setAccessible(true);
        var runners = (Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>>) field.get(null);
        var previousBag = runners.get(BackpackSupplyTaskRecord.class); var previousAe = runners.get(Ae2SupplyTaskRecord.class);
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY); var quartz = ResourceLocation.parse("minecraft:quartz");
            var opened = new ArrayList<Integer>();
            TaskFactory.register(Ae2SupplyTaskRecord.class, (player, record) -> { throw new AssertionError("AE cannot precede available or unknown backpack stock"); });
            TaskFactory.register(BackpackSupplyTaskRecord.class, (player, record) -> new Task() {
                private TaskResult result;
                public String name() { return "随身背包原生回执调度夹具"; }
                public TaskState tick(LocalPlayer ignored) {
                    opened.add(record.backpackSlot);
                    if (scenario.equals("worn")) check(record.carrier.equals(new Carrier("curios", "back", 0)) && record.backpackSlot == -1,
                            "worn acquisition preserves native handler identity and never aliases a main slot");
                    check(record.operation == BackpackSupplyTaskRecord.Operation.WITHDRAW && record.amount == 2 && record.items.equals(List.of(quartz)),
                            "only the current exact need is requested from carried stock");
                    if (scenario.equals("second_bag") && record.backpackSlot == 0)
                        result = TaskResult.fail("observed no matching stock", Map.of("failure_code", "backpack_stock_insufficient", "menu_closed", true, "outcome_uncertain", false));
                    else if (scenario.equals("unreadable") || scenario.equals("uncertain"))
                        result = TaskResult.fail("observation failed", Map.of("failure_code", "backpack_open_failed", "menu_closed", true, "outcome_uncertain", scenario.equals("uncertain")));
                    else { h.inventory.setItem(0, new ItemStack(Items.QUARTZ, 2)); result = TaskResult.ok("withdrawal settled", Map.of("menu_closed", true, "outcome_uncertain", false)); }
                    return result.success() ? TaskState.SUCCESS : TaskState.FAILED;
                }
                public void stop(LocalPlayer ignored, StopReason reason) {}
                public TaskResult result(TaskState state) { return result; }
            });
            var request = new SemanticAcquireTaskRecord("backpack-priority", 1000, List.of(quartz), 2,
                    List.of(SemanticAcquireTaskRecord.Source.INVENTORY, SemanticAcquireTaskRecord.Source.WIRELESS), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(h.player, request, player -> true, new AcquisitionInventoryTidy(Set::copyOf),
                    new AcquisitionBackpackInventory(player -> scenario.equals("worn") ? List.of(new Carrier("curios", "back", 0))
                            : scenario.equals("second_bag") ? List.of(Carrier.vanilla(0), Carrier.vanilla(1)) : List.of(Carrier.vanilla(0))));
            task.onStart(); var state = TaskState.RUNNING;
            for (int i = 0; i < 30 && !state.isTerminal(); i++) state = task.onTick();
            var result = task.result(state);
            if (scenario.equals("unreadable")) check(state == TaskState.FAILED && result.data().get("failure_code").equals("backpack_access_unverified"), "unreadable bag is not an empty source");
            else if (scenario.equals("uncertain")) check(state == TaskState.FAILED && Boolean.TRUE.equals(result.data().get("outcome_uncertain")), "unknown transfer blocks all new sources");
            else check(state == TaskState.SUCCESS && h.inventory.countItem(Items.QUARTZ) == 2, "carried stock satisfies the same acquisition without model retry");
            check(opened.equals(scenario.equals("worn") ? List.of(-1) : scenario.equals("second_bag") ? List.of(0, 1) : List.of(0)), "each physical backpack is attempted once in order");
        } finally { runners.put(BackpackSupplyTaskRecord.class, previousBag); runners.put(Ae2SupplyTaskRecord.class, previousAe); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
