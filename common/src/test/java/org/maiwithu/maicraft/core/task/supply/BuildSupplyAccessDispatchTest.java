// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildSupplyAccessTest;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 坑底缺料时先派出坑子任务，确认人在地面后才取料；夹具站位仅用于测试状态交接，不代表真人施工。 */
public final class BuildSupplyAccessDispatchTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var runners = runners(); var before = runners.get(BuildTaskRecord.class);
        try {
            TaskFactory.register(BuildTaskRecord.class, BuildCompanionTask::new);
            accessPrecedesSupplyAndDoesNotCountAsBuilding();
            falseArrivalStopsBeforeStorage();
        } finally {
            if (before == null) runners.remove(BuildTaskRecord.class); else runners.put(BuildTaskRecord.class, before);
        }
        System.out.println("BuildSupplyAccessDispatchTest: passed");
    }

    private static void accessPrecedesSupplyAndDoesNotCountAsBuilding() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var task = task(h); task.start(h.player);
            check(task.tick(h.player) == TaskState.RUNNING, "the resumed parent remains active");
            var access = (BuildTaskRecord) field(task, "activeRecord").get(task);
            var supply = (SemanticMaterialSupplyCoordinator) field(task, "supply").get(task);
            check(access.supplyAccessOnly() && field(task, "activeChild").get(task) instanceof BuildCompanionTask
                    && !supply.active(), "a real construction-access runner must precede ordinary storage acquisition");
            check(access.targets.size() == 27 && access.previewManaged()
                    && access.toolSupply().sources().equals(List.of(SemanticAcquireTaskRecord.Source.STORAGE)),
                    "access keeps the full reviewed footprint and original storage-only source policy");
            check(task.progress().get("phase").equals("preparing_supply_access")
                    && field(task, "buildRounds").getInt(task) == 0, "access has its own progress phase and is not a built batch");
            // 只注入已经站到地面的观察，然后推进真实子任务预检；这一步不能把未建的木地板算完成。
            h.position(new Vec3(2.5, 4, 4.5)); h.nextTick();
            for (int i = 0; i < 4 && field(task, "activeChild").get(task) != null; i++) {
                check(task.tick(h.player) == TaskState.RUNNING, "access completion leaves the parent building goal pending"); h.nextTick();
            }
            check(field(task, "activeChild").get(task) == null && !supply.active(), "exit confirmation must settle before starting the storage trip");
            var batches = (List<?>) task.resultData().get("batches");
            check(batches.size() == 1 && ((Map<?, ?>) batches.getFirst()).get("kind").equals("build_access")
                    && Boolean.FALSE.equals(task.resultData().get("goal_satisfied")), "access receives a separate receipt without completing the house");
            check(!(boolean) field(task, "batchVerified").get(task)
                    && ((Map<?, ?>) field(task, "finalBuildData").get(task)).isEmpty(), "an access receipt cannot replace normal construction verification");
            check(!SemanticBuildSupplyCompanionTask.batchCompleted(TaskState.SUCCESS, TaskResult.ok("access only",
                    Map.of("supply_access_only", true, "supply_access_ready", true))), "the shared batch predicate also rejects an access-only success");
            task.tick(h.player);
            check(supply.active() && new BlockPos(2, 4, 4).equals(field(supply, "investigationOrigin").get(supply)),
                    "后续取料从安全出口地面开始，再由建筑任务接管施工站位");
            // 对比原来的供料规则；正常背包检查与仓储合成仍保留，出坑准备不能额外开放采矿等来源。
            check(field(supply, "sources").get(supply).equals(SemanticMaterialSupplyCoordinator.resolveSources(
                    SemanticMaterialSupplyCoordinator.MaterialPolicy.STORAGE_AVAILABLE, List.of(SemanticAcquireTaskRecord.Source.STORAGE))),
                    "making construction access does not add mining or other acquisition permissions");
            // 模拟材料已经在背包里，下一轮仍派正常施工，不能把出口专用模式传下去。
            supply.cancel(h.player); h.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 9)); h.nextTick();
            task.tick(h.player);
            check(!((BuildTaskRecord) field(task, "activeRecord").get(task)).supplyAccessOnly()
                    && field(task, "buildRounds").getInt(task) == 1, "resupply resumes a normal carried-material build batch");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void falseArrivalStopsBeforeStorage() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var task = task(h); task.start(h.player); task.tick(h.player);
            // 子任务即便错误自报到达，父任务仍重新观察坑底身体，不能据此让普通仓库寻路接管。
            field(task, "activeChild").set(task, new Task() {
                public TaskState tick(LocalPlayer p) { return TaskState.SUCCESS; }
                public void stop(LocalPlayer p, StopReason why) {}
                public String name() { return "incorrect fixture arrival"; }
                public TaskResult result(TaskState terminal) { return TaskResult.ok("reported access", Map.of(
                        "supply_access_only", true, "supply_access_ready", true, "goal_satisfied", false)); }
            });
            check(task.tick(h.player) == TaskState.FAILED
                    && task.resultData().get("failure_code").equals("construction_supply_access_failed"),
                    "a body still inside the pit invalidates a claimed access success");
            check(!((SemanticMaterialSupplyCoordinator) field(task, "supply").get(task)).active()
                    && field(task, "buildRounds").getInt(task) == 0, "failed preparation neither fetches nor fabricates construction progress");
            task.result(TaskState.FAILED);
        }
    }

    private static SemanticBuildSupplyCompanionTask task(InteractionWorldTestHarness h) throws Exception {
        var plan = new BuildTaskRecord("resumed-pit", 1000, BuildSupplyAccessTest.preparePit(h), false, true);
        var record = new SemanticBuildSupplyTaskRecord("resumed-supply", 1000, plan,
                SemanticMaterialSupplyCoordinator.MaterialPolicy.STORAGE_AVAILABLE,
                List.of(SemanticAcquireTaskRecord.Source.STORAGE), false, List.of("owner's store"), false);
        return new SemanticBuildSupplyCompanionTask(h.player, record, (owner, frozen) -> Decision.DISABLED);
    }
    @SuppressWarnings("unchecked") private static Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>> runners() throws Exception {
        return (Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>>) field(TaskFactory.class, "RUNNERS").get(null);
    }
    private static Field field(Object instance, String name) throws Exception {
        Class<?> type = instance instanceof Class<?> value ? value : instance.getClass();
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
