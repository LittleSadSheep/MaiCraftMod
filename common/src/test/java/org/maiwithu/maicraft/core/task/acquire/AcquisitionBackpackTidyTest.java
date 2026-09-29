// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.integration.ae2.Ae2SupplyTaskRecord;
import org.maiwithu.maicraft.core.integration.backpack.BackpackSupplyTaskRecord;
import org.maiwithu.maicraft.core.integration.backpack.BackpackCarriers.Carrier;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 满包先存精妙余料并续取；精妙也满时切AE，未知存入则保留现场并阻止新取料。 */
public final class AcquisitionBackpackTidyTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (String scenario : List.of("backpack", "backpack_full", "uncertain")) run(scenario);
        System.out.println("AcquisitionBackpackTidyTest: passed");
    }
    @SuppressWarnings("unchecked") private static void run(String scenario) throws Exception {
        Class.forName(BackpackSupplyTaskRecord.class.getName()); Class.forName(Ae2SupplyTaskRecord.class.getName());
        var field = TaskFactory.class.getDeclaredField("RUNNERS"); field.setAccessible(true);
        var runners = (Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>>) field.get(null);
        var bagFactory = runners.get(BackpackSupplyTaskRecord.class); var aeFactory = runners.get(Ae2SupplyTaskRecord.class);
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            for (int i = 0; i < 35; i++) h.inventory.setItem(i, new ItemStack(Items.BONE));
            h.inventory.setItem(35, new ItemStack(Items.DIAMOND_PICKAXE));
            var calls = new ArrayList<String>(); var bone = ResourceLocation.parse("minecraft:bone");
            TaskFactory.register(BackpackSupplyTaskRecord.class, (player, record) -> scripted(() -> {
                calls.add("bag:" + record.operation.name());
                if (record.operation == BackpackSupplyTaskRecord.Operation.WITHDRAW) {
                    if (h.inventory.items.stream().noneMatch(ItemStack::isEmpty)) return TaskResult.fail("full main inventory",
                            Map.of("failure_code", "inventory_full", "menu_closed", true, "outcome_uncertain", false));
                    h.inventory.setItem(0, new ItemStack(Items.QUARTZ, 2)); return TaskResult.ok("withdrawn", Map.of("menu_closed", true, "outcome_uncertain", false));
                }
                check(record.operation == BackpackSupplyTaskRecord.Operation.DEPOSIT && record.deposits.equals(Map.of(bone, 35)), "only approved spare bones are stashed");
                if (scenario.equals("uncertain")) return deposit(false, true);
                if (scenario.equals("backpack_full")) return deposit(false, false);
                clearBones(h); return deposit(true, false);
            }));
            TaskFactory.register(Ae2SupplyTaskRecord.class, (player, record) -> scripted(() -> {
                check(scenario.equals("backpack_full") && record.request.operation() == Ae2ResourceSupply.Operation.DEPOSIT,
                        "AE is only the settled full-backpack deposit fallback");
                calls.add("ae:DEPOSIT"); clearBones(h); return deposit(true, false);
            }));
            var quartz = ResourceLocation.parse("minecraft:quartz");
            var record = new SemanticAcquireTaskRecord("bag-tidy", 1000, List.of(quartz), 2,
                    List.of(SemanticAcquireTaskRecord.Source.INVENTORY, SemanticAcquireTaskRecord.Source.WIRELESS), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(h.player, record, player -> scenario.equals("backpack_full"),
                    new AcquisitionInventoryTidy(Set::copyOf, player -> List.of(Carrier.vanilla(35))), new AcquisitionBackpackInventory(player -> List.of(Carrier.vanilla(35))));
            task.onStart(); var state = TaskState.RUNNING;
            for (int i = 0; i < 60 && !state.isTerminal(); i++) state = task.onTick();
            var result = task.result(state);
            List<String> expected = scenario.equals("uncertain") ? List.of("bag:WITHDRAW", "bag:DEPOSIT")
                    : scenario.equals("backpack_full") ? List.of("bag:WITHDRAW", "bag:DEPOSIT", "ae:DEPOSIT", "bag:WITHDRAW")
                    : List.of("bag:WITHDRAW", "bag:DEPOSIT", "bag:WITHDRAW");
            check(calls.equals(expected), "one acquisition performs the exact storage fallback and resumes its blocked source: " + calls);
            check(h.inventory.getItem(35).is(Items.DIAMOND_PICKAXE), "ordinary tools survive automatic tidy");
            if (scenario.equals("uncertain")) check(state == TaskState.FAILED && result.data().get("failure_code").equals("inventory_tidy_uncertain"), "unknown deposit cannot resume withdrawal");
            else check(state == TaskState.SUCCESS && h.inventory.countItem(Items.QUARTZ) == 2 && h.inventory.countItem(Items.BONE) == 0,
                    "confirmed stash frees capacity and the unchanged request receives its material");
        } finally { runners.put(BackpackSupplyTaskRecord.class, bagFactory); runners.put(Ae2SupplyTaskRecord.class, aeFactory); }
    }
    private static void clearBones(InteractionWorldTestHarness h) { for (int i = 0; i < 35; i++) h.inventory.setItem(i, ItemStack.EMPTY); }
    private static TaskResult deposit(boolean moved, boolean uncertain) {
        var data = Map.<String, Object>of("operation", "deposit", "deposited", moved ? Map.of("minecraft:bone", 35) : Map.of(),
                "confirmed_deposited_total", moved ? 35 : 0, "effects_started", moved || uncertain, "outcome_uncertain", uncertain);
        return moved ? TaskResult.ok("stash settled", data) : TaskResult.fail(uncertain ? "unknown stash" : "destination full", data);
    }
    private static Task scripted(Supplier<TaskResult> action) {
        return new Task() {
            private TaskResult result;
            public String name() { return "原生收纳回执回放"; }
            public TaskState tick(LocalPlayer player) { result = action.get(); return result.success() ? TaskState.SUCCESS : TaskState.FAILED; }
            public void stop(LocalPlayer player, StopReason reason) {}
            public TaskResult result(TaskState state) { return result; }
        };
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
