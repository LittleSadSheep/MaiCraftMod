// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySources;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySourcesTest;
import org.maiwithu.maicraft.core.task.container.SemanticContainerCompanionTask;
import org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Exercises the real acquisition STORAGE branch before any AE2 availability or network request is needed. */
public final class OrdinaryStorageAcquireTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        TaskFactory.register(SemanticContainerTaskRecord.class, SemanticContainerCompanionTask::new);
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h);
            BlockPos first = new BlockPos(3, 1, 3), second = new BlockPos(6, 1, 3);
            ContainerSupplySourcesTest.addBarrel(h, entities, first); ContainerSupplySourcesTest.addBarrel(h, entities, second);
            var r = new SemanticAcquireTaskRecord("ordinary-storage", 1000, List.of(ResourceLocation.parse("minecraft:iron_ingot")), 10,
                    List.of(SemanticAcquireTaskRecord.Source.STORAGE), false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(h.player, r); task.onStart(); Object need = get(task, "rootNeed");
            var attempt = task.getClass().getDeclaredMethod("attemptStorage", need.getClass()); attempt.setAccessible(true);
            check(attempt.invoke(task, need) == TaskState.RUNNING, "STORAGE did not begin ordinary container supply");
            var selected = (SemanticContainerTaskRecord) get(task, "activeRecord");
            check(selected.storageSupply() && selected.supplyPosition.equals(first) && selected.targetCount == 10,
                    "ordinary warehouse was skipped or its exact target/count was lost to the AE2 path");
            set(task, "activeChild", new EmptySettledContainer());
            var tick = task.getClass().getDeclaredMethod("tickActiveChild"); tick.setAccessible(true); tick.invoke(task);
            check(!((java.util.Set<?>) get(need, "exhaustedSources")).contains(SemanticAcquireTaskRecord.Source.STORAGE),
                    "one empty ordinary container must not exhaust the entire storage source family");
            set(task, "plannerStepsThisTick", 0); attempt.invoke(task, need);
            selected = (SemanticContainerTaskRecord) get(task, "activeRecord");
            check(selected.supplyPosition.equals(second), "the acquisition frontier repeated the empty first container instead of checking the next warehouse");
            check(h.blockUses() == 0 && h.itemUses() == 0, "source planning performed inventory/world operations before its GUI child");
        } finally { ContainerSupplySources.reset(); }
    }
    private static final class EmptySettledContainer implements Task {
        public TaskState tick(LocalPlayer player) { return TaskState.SUCCESS; }
        public void stop(LocalPlayer player, StopReason reason) {}
        public TaskResult result(TaskState state) { return new TaskResult(true, "visible container was empty and closed", false, false,
                Map.of("moved_count", 0, "effects_started", false, "outcome_uncertain", false, "bounded_storage_withdrawal", true)); }
        public String name() { return "settled empty ordinary source"; }
    }
    private static Object get(Object object, String name) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
