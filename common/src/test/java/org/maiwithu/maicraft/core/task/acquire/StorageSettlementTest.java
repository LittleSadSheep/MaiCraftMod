// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** The real acquisition parent must settle committed storage work before accepting inventory progress. */
public final class StorageSettlementTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        scenario(true, TaskState.FAILED, Map.of("outcome_uncertain", true, "effects_started", true),
                TaskState.FAILED, "storage_effect_uncertain");
        scenario(true, TaskState.FAILED, Map.of("outcome_uncertain", false, "effects_started", true),
                TaskState.FAILED, "storage_settlement_incomplete");
        scenario(true, TaskState.SUCCESS, Map.of("effects_started", true), TaskState.SUCCESS, null);
        scenario(false, TaskState.FAILED, Map.of("effects_started", false), TaskState.SUCCESS, null);
        System.out.println("StorageSettlementTest: inventory progress cannot hide unsettled native storage effects");
    }

    private static void scenario(boolean barrier, TaskState childState, Map<String, Object> childData,
                                 TaskState expected, String failureCode) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var record = new SemanticAcquireTaskRecord("storage-settlement", 1000,
                    List.of(ResourceLocation.parse("minecraft:iron_ingot")), 1,
                    List.of(SemanticAcquireTaskRecord.Source.STORAGE), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var parent = new SemanticAcquireCompanionTask(world.player, record);
            parent.onStart();
            var child = new PendingStorage(barrier, childState, childData);
            set(parent, "activeChild", child); set(parent, "activeRecord", record);
            set(parent, "activeNeed", get(parent, "rootNeed"));
            set(parent, "activeSource", SemanticAcquireTaskRecord.Source.STORAGE);
            set(parent, "activeDetail", "already submitted exact extraction");
            // Inventory packet arrived before the existing child's terminal receipt was observed.
            world.inventory.setItem(0, new ItemStack(Items.IRON_INGOT));
            var tick = parent.getClass().getDeclaredMethod("tickAcquisition"); tick.setAccessible(true);
            check(tick.invoke(parent) == expected, "inventory progress bypassed the storage settlement outcome");
            check(child.ticks == (barrier ? 1 : 0), "only committed child work may continue after the final fact becomes true");
            check(world.inventory.getItem(0).getCount() == 1 && world.blockUses() == 0 && world.itemUses() == 0,
                    "settlement cannot take more materials, discard progress or issue a new native action");
            check(java.util.Objects.equals(get(parent, "failureCode"), failureCode), "wrong storage settlement failure evidence");
        }
    }

    private static final class PendingStorage implements Task {
        final boolean barrier; final TaskState state; final Map<String, Object> data; int ticks; boolean settling;
        PendingStorage(boolean barrier, TaskState state, Map<String, Object> data) {
            this.barrier = barrier; this.state = state; this.data = data;
        }
        public boolean mustSettleBeforeSatisfiedCancellation() { return barrier; }
        public void requestSatisfiedSettlement() { settling = true; }
        public TaskState tick(LocalPlayer player) {
            check(settling, "the parent must explicitly request settlement without new work after its final fact is true");
            ticks++; return state;
        }
        public void stop(LocalPlayer player, StopReason reason) {}
        public TaskResult result(TaskState terminal) { return new TaskResult(state == TaskState.SUCCESS, "storage receipt", false, false, data); }
        public String name() { return "pending native storage receipt"; }
    }
    private static Object get(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
