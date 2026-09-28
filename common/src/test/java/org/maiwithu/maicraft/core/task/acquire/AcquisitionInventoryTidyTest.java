// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.integration.ae2.Ae2SupplyTaskRecord;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 原取料因满包失败后自动存闲置物再续取；未知存入只交回观察，不继续取材掩盖未结效果。 */
public final class AcquisitionInventoryTidyTest {
    private static final ResourceLocation QUARTZ = ResourceLocation.parse("minecraft:quartz");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        scenario(false); scenario(true);
        System.out.println("AcquisitionInventoryTidyTest: passed");
    }
    @SuppressWarnings("unchecked")
    private static void scenario(boolean uncertain) throws Exception {
        Class.forName(Ae2SupplyTaskRecord.class.getName());
        var field = TaskFactory.class.getDeclaredField("RUNNERS"); field.setAccessible(true);
        var runners = (Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>>) field.get(null);
        var previous = runners.get(Ae2SupplyTaskRecord.class);
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            for (int i = 0; i < 35; i++) h.inventory.setItem(i, new ItemStack(Items.BONE));
            h.inventory.setItem(35, new ItemStack(Items.DIAMOND_PICKAXE));
            var calls = new ArrayList<Ae2ResourceSupply.Operation>();
            TaskFactory.register(Ae2SupplyTaskRecord.class, (player, record) -> new Task() {
                private TaskResult result;
                public String name() { return "重放满包后的原生存取回执"; }
                public void start(LocalPlayer ignored) { calls.add(record.request.operation()); }
                public TaskState tick(LocalPlayer ignored) {
                    var operation = record.request.operation();
                    if (operation == Ae2ResourceSupply.Operation.OBSERVE) {
                        remember(h); result = TaskResult.ok("stock read", Map.of("outcome_uncertain", false));
                    } else if (operation == Ae2ResourceSupply.Operation.DEPOSIT) {
                        check(record.request.wirelessOnly() && !record.request.allowCrafting(), "tidying stays at the carried terminal");
                        var moved = new LinkedHashMap<String, Integer>();
                        for (var group : record.request.groups()) {
                            check(group.itemId().equals(ResourceLocation.parse("minecraft:bone")), "work tools are never approved for storage");
                            if (!uncertain) {
                                for (int slot = 0; slot < 35; slot++) h.inventory.setItem(slot, ItemStack.EMPTY);
                                moved.put(group.itemId().toString(), group.count());
                            }
                        }
                        var data = Map.<String, Object>of("operation", "deposit", "deposited", moved,
                                "confirmed_deposited_total", uncertain ? 0 : 35, "effects_started", !uncertain, "outcome_uncertain", uncertain);
                        result = uncertain ? TaskResult.fail("unconfirmed transfer", data) : TaskResult.ok("deposited", data);
                    } else if (h.inventory.items.stream().noneMatch(ItemStack::isEmpty)) {
                        result = TaskResult.fail("inventory full", Map.of("failure_code", "inventory_full", "outcome_uncertain", false));
                    } else {
                        h.inventory.setItem(0, new ItemStack(Items.QUARTZ, 2)); result = TaskResult.ok("supplied", Map.of("outcome_uncertain", false));
                    }
                    return result.success() ? TaskState.SUCCESS : TaskState.FAILED;
                }
                public void stop(LocalPlayer ignored, StopReason why) {}
                public TaskResult result(TaskState state) { return result; }
            });
            var record = new SemanticAcquireTaskRecord("tidy-acquire", 1000, List.of(QUARTZ), 2,
                    List.of(SemanticAcquireTaskRecord.Source.WIRELESS), false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(h.player, record, ignored -> true, new AcquisitionInventoryTidy(Set::copyOf));
            task.onStart(); TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 80 && state == TaskState.RUNNING; i++) state = task.onTick();
            var data = task.result(state).data();
            check(calls.subList(0, 3).equals(List.of(Ae2ResourceSupply.Operation.OBSERVE, Ae2ResourceSupply.Operation.SUPPLY,
                    Ae2ResourceSupply.Operation.DEPOSIT)), "capacity rejection triggers actual deposit before any new source");
            check(h.inventory.getItem(35).is(Items.DIAMOND_PICKAXE) && ((List<?>) data.get("inventory_maintenance")).size() == 1,
                    "the retained tool and maintenance receipt survive the whole acquisition");
            if (uncertain) check(state == TaskState.FAILED && calls.size() == 3 && "inventory_tidy_uncertain".equals(data.get("failure_code")),
                    "uncertain tidying stops before another inventory transfer");
            else check(state == TaskState.SUCCESS && calls.size() == 5 && h.inventory.countItem(Items.QUARTZ) == 2
                    && h.inventory.countItem(Items.BONE) == 0, "the same acquisition refreshes stock and completes without a model retry");
        } finally { if (previous == null) runners.remove(Ae2SupplyTaskRecord.class); else runners.put(Ae2SupplyTaskRecord.class, previous); }
    }
    private static void remember(InteractionWorldTestHarness h) {
        try {
            var field = StockEvidence.class.getDeclaredField("NETWORK_CACHE"); field.setAccessible(true); Object cache = field.get(null);
            var record = cache.getClass().getDeclaredMethod("record", Object.class, Object.class, Map.class, StockEvidence.Snapshot.class); record.setAccessible(true);
            record.invoke(cache, h.player, h.level, Map.of(), new StockEvidence.Snapshot(StockEvidence.Source.AE2, Map.of(QUARTZ, 317L), Set.of(), h.level.getGameTime()));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
