// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.integration.machine.MachineBuildEvidence;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 背包没有空位时停止换来源；已有未结取物仍先保留不确定边界，不能借容量报错重试。 */
public final class AcquisitionCapacityFailureTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        scenario(false, false); scenario(false, true); scenario(true, false);
        System.out.println("AcquisitionCapacityFailureTest: passed");
    }

    private static void scenario(boolean uncertain, boolean typedFailure) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 36个主背包槽均有整组圆石，石英现货不能放入；该前置与是否允许采矿无关。
            for (int slot = 0; slot < 36; slot++) h.inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            var quartz = ResourceLocation.parse("minecraft:quartz");
            var record = new SemanticAcquireTaskRecord("capacity-stop", 1000, List.of(quartz), 2,
                    List.of(SemanticAcquireTaskRecord.Source.WIRELESS, SemanticAcquireTaskRecord.Source.MINE), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(h.player, record); task.onStart();
            Object need = get(task, "rootNeed");
            var request = new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(quartz, List.of(quartz), 2,
                    Ae2ResourceSupply.SelectionMode.AGGREGATE)), false, Ae2ResourceSupply.Operation.SUPPLY, true);
            var childRecord = Ae2ResourceSupply.taskRecord("quartz-stock", 1000, request);
            var start = task.getClass().getDeclaredMethod("startChild", need.getClass(), SemanticAcquireTaskRecord.Source.class,
                    TaskRecord.class, String.class);
            start.setAccessible(true); start.invoke(task, need, SemanticAcquireTaskRecord.Source.WIRELESS, childRecord, "withdraw quartz");
            set(task, "activeChild", new CapacityFailure(uncertain, typedFailure));
            var tick = task.getClass().getDeclaredMethod("tickActiveChild"); tick.setAccessible(true);
            check(tick.invoke(task) == TaskState.FAILED && get(task, "activeChild") == null,
                    "capacity failure must not advance to the permitted mining source");
            var data = task.result(TaskState.FAILED).data();
            if (uncertain) {
                // 子任务尚未确定是否取出了物品时，先结清这笔操作，不能只让规划者清空背包后盲目重发。
                check("storage_effect_uncertain".equals(data.get("failure_code"))
                        && !data.containsKey("inventory_capacity"), "uncertain effects retain priority over capacity recovery");
            } else {
                var capacity = (Map<?, ?>) data.get("inventory_capacity");
                check("inventory_capacity_blocked".equals(data.get("failure_code")) && "no_space".equals(data.get("failure_type"))
                        && capacity.get("empty_main_slots").equals(0L) && capacity.get("missing").equals(2), "capacity facts reach the inventory goal");
                var options = (List<?>) data.get("recovery_options");
                check(options.size() == 2 && "prepare_inventory_capacity".equals(((Map<?, ?>) options.getFirst()).get("id")),
                        "recovery addresses inventory space instead of granting more sources");
                var machine = new LinkedHashMap<String, Object>(); MachineBuildEvidence.retainSupplyFailure(machine, data);
                check(capacity.equals(machine.get("inventory_capacity")), "machine results retain the same capacity prerequisite");
            }
            check(h.inventory.countItem(Items.COBBLESTONE) == 2304 && h.inventory.countItem(Items.QUARTZ) == 0
                    && h.blockUses() == 0 && h.itemUses() == 0, "no items are discarded or mined during capacity handoff");
        }
    }

    private record CapacityFailure(boolean uncertain, boolean typed) implements Task {
        public TaskState tick(LocalPlayer player) { return TaskState.FAILED; }
        public void stop(LocalPlayer player, StopReason reason) {}
        public String name() { return "模拟无线取料的容量门槛"; }
        public TaskResult result(TaskState state) { return TaskResult.fail("inventory cannot hold quartz", typed
                ? Map.of("failure_type", "no_space", "outcome_uncertain", uncertain)
                : Map.of("failure_code", "inventory_full", "outcome_uncertain", uncertain)); }
    }
    private static Object get(Object instance, String name) throws Exception { var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(instance); }
    private static void set(Object instance, String name, Object value) throws Exception { var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); f.set(instance, value); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
