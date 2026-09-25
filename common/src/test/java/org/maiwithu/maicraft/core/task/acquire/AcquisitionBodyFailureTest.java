package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 合成因身体门槛停止后，取材层也应立即交回原目标，不能换配方或转去森林采集。 */
public final class AcquisitionBodyFailureTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var r = new SemanticAcquireTaskRecord("body-stop", 1000, List.of(ResourceLocation.parse("minecraft:paper")), 3,
                    List.of(SemanticAcquireTaskRecord.Source.CRAFT, SemanticAcquireTaskRecord.Source.MINE), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(h.player, r); task.onStart();
            Object need = get(task, "rootNeed");
            var start = task.getClass().getDeclaredMethod("startChild", need.getClass(), SemanticAcquireTaskRecord.Source.class, TaskRecord.class, String.class);
            start.setAccessible(true); start.invoke(task, need, SemanticAcquireTaskRecord.Source.CRAFT,
                    new CraftTaskRecord("paper", 1000, ResourceLocation.parse("minecraft:paper"), 3, null), "craft paper");
            set(task, "activeChild", new Failure());
            var tick = task.getClass().getDeclaredMethod("tickActiveChild"); tick.setAccessible(true);
            check(tick.invoke(task) == TaskState.FAILED && get(task, "activeChild") == null, "body prerequisite stops acquisition without starting another source");
            var data = task.result(TaskState.FAILED).data();
            check("crafting_body_preparation_required".equals(data.get("failure_code"))
                    && Boolean.TRUE.equals(data.get("body_preparation_required"))
                    && ((Map<?, ?>) data.get("food_preparation")).get("food").equals(10), "the original hunger evidence reaches the inventory goal");
            check(((List<?>) data.get("recovery_options")).size() == 2 && h.blockUses() == 0 && h.itemUses() == 0,
                    "recovery addresses the body and does not open new acquisition sources");
        }
        System.out.println("AcquisitionBodyFailureTest: passed");
    }
    private static final class Failure implements Task {
        public TaskState tick(LocalPlayer player) { return TaskState.FAILED; }
        public void stop(LocalPlayer player, StopReason reason) { }
        public String name() { return "模拟缺食物的合成"; }
        public TaskResult result(TaskState state) { return TaskResult.fail("no ordinary food", Map.of("body_preparation_required", true,
                "food_preparation", Map.of("food", 10, "health", 7), "failure_code", "build_food_unavailable", "failure_type", "no_material")); }
    }
    private static Object get(Object object, String name) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
