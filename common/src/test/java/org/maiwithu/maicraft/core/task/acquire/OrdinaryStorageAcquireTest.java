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
import org.maiwithu.maicraft.core.task.container.ContainerSearchScope;
import org.maiwithu.maicraft.core.tools.work.SemanticAcquireApi;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import java.util.Set;

/** 在检查 AE2 可用性或发起网络请求前，先测试真实的 STORAGE 获取分支。 */
public final class OrdinaryStorageAcquireTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        TaskFactory.register(SemanticContainerTaskRecord.class, SemanticContainerCompanionTask::new);
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h);
            BlockPos first = new BlockPos(3, 1, 3), second = new BlockPos(6, 1, 3);
            ContainerSupplySourcesTest.addBarrel(h, entities, first); ContainerSupplySourcesTest.addBarrel(h, entities, second);
            // 先前看过两箱铁锭，第一箱被取空后才可沿着第二条已有线索继续补料。
            ContainerSupplySourcesTest.rememberContents(h, first, ResourceLocation.parse("minecraft:iron_ingot"), 3);
            ContainerSupplySourcesTest.rememberContents(h, second, ResourceLocation.parse("minecraft:iron_ingot"), 7);
            // 陌生木桶更近也排在已知有货箱之后，无货旧箱则留到未知箱都调查完。
            ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(2, 1, 2));
            BlockPos empty = new BlockPos(8, 1, 5); ContainerSupplySourcesTest.addBarrel(h, entities, empty);
            ContainerSupplySourcesTest.rememberContents(h, empty, ResourceLocation.parse("minecraft:iron_ingot"), 0);
            var r = new SemanticAcquireTaskRecord("ordinary-storage", 1000, List.of(ResourceLocation.parse("minecraft:iron_ingot")), 10,
                    List.of(SemanticAcquireTaskRecord.Source.STORAGE), false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(h.player, r); task.onStart(); Object need = get(task, "rootNeed");
            var attempt = task.getClass().getDeclaredMethod("attemptStorage", need.getClass(), SemanticAcquireTaskRecord.Source.class); attempt.setAccessible(true);
            check(attempt.invoke(task, need, SemanticAcquireTaskRecord.Source.STORAGE) == TaskState.RUNNING, "STORAGE did not begin ordinary container supply");
            var selected = (SemanticContainerTaskRecord) get(task, "activeRecord");
            check(selected.storageSupply() && selected.supplyPosition.equals(first) && selected.targetCount == 10,
                    "ordinary warehouse was skipped or its exact target/count was lost to the AE2 path");
            set(task, "activeChild", new EmptySettledContainer());
            var tick = task.getClass().getDeclaredMethod("tickActiveChild"); tick.setAccessible(true); tick.invoke(task);
            check(!((Set<?>) get(need, "exhaustedSources")).contains(SemanticAcquireTaskRecord.Source.STORAGE),
                    "one empty ordinary container must not exhaust the entire storage source family");
            set(task, "plannerStepsThisTick", 0); attempt.invoke(task, need, SemanticAcquireTaskRecord.Source.STORAGE);
            selected = (SemanticContainerTaskRecord) get(task, "activeRecord");
            check(selected.supplyPosition.equals(second), "the acquisition frontier repeated the empty first container instead of checking the next warehouse");
            set(task, "activeChild", new EmptySettledContainer()); tick.invoke(task);
            // 原来的八箱上限不能让后续未知箱和无货箱被漏掉；范围及每箱一次访问负责约束调查。
            set(need, "containerAttempts", 8); set(task, "plannerStepsThisTick", 0); attempt.invoke(task, need, SemanticAcquireTaskRecord.Source.STORAGE);
            selected = (SemanticContainerTaskRecord) get(task, "activeRecord");
            check(selected.supplyPosition.equals(new BlockPos(2, 1, 2)) && selected.priorMemoryState.equals("unvisited"), "unknown container is next even after eight prior attempts");
            set(task, "activeChild", new EmptySettledContainer()); tick.invoke(task);
            set(task, "plannerStepsThisTick", 0); attempt.invoke(task, need, SemanticAcquireTaskRecord.Source.STORAGE);
            selected = (SemanticContainerTaskRecord) get(task, "activeRecord");
            check(selected.supplyPosition.equals(empty) && selected.priorMemoryState.equals("remembered_absent"), "previously empty container is rechecked last");
            check(h.blockUses() == 0 && h.itemUses() == 0, "source planning performed inventory/world operations before its GUI child");
        } finally { ContainerSupplySources.reset(); }
        callOriginSurvivesQueueAndNestedSupply();
        largeWarehouseKeepsEveryAttempt();
    }

    private static void callOriginSurvivesQueueAndNestedSupply() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 公开调用提交后再移动玩家；未写来源时默认允许翻箱，未写半径时仓库最多三十二格。
            var record = SemanticAcquireApi.newRecord(new ToolContext("queued-container-search", 0),
                    JsonParser.parseString("{\"item_id\":\"minecraft:iron_ingot\",\"count\":2}").getAsJsonObject(), h.player);
            BlockPos origin = h.player.blockPosition(); h.position(new Vec3(6.5, 1, 3.5));
            var task = new SemanticAcquireCompanionTask(h.player, record); task.onStart();
            var scope = (ContainerSearchScope) get(task, "storageScope");
            check(scope.origin().equals(origin) && scope.radius() == 32 && record.allowedSources.contains(SemanticAcquireTaskRecord.Source.STORAGE), "request-time origin and default container access are preserved");
            var nested = scope.inherit(() -> ContainerSearchScope.capture(h.player, 48));
            check(nested.origin().equals(origin) && nested.radius() == 32, "recursive supply cannot recenter or enlarge the search");
            var order = AcquisitionSources.order((AcquisitionNeed) get(task, "rootNeed"), new AcquisitionSources.Readiness(true, false, false, false));
            check(order.indexOf(SemanticAcquireTaskRecord.Source.STORAGE) < order.indexOf(SemanticAcquireTaskRecord.Source.CRAFT), "container requests precede ready crafting");
        }
    }

    private static void largeWarehouseKeepsEveryAttempt() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h); h.position(new Vec3(7.5, 10, 7.5));
            // 从上方可见八十一只箱子；模拟每只原生菜单已查空并结清，核对调度及回执不按六十四项截断。
            for (int x = 2; x <= 10; x++) for (int z = 2; z <= 10; z++)
                ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(x, 1, z));
            var record = new SemanticAcquireTaskRecord("large-warehouse", 1000, List.of(ResourceLocation.parse("minecraft:iron_ingot")), 1,
                    List.of(SemanticAcquireTaskRecord.Source.STORAGE), false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(h.player, record); task.onStart(); var need = get(task, "rootNeed");
            var attempt = task.getClass().getDeclaredMethod("attemptStorage", need.getClass(), SemanticAcquireTaskRecord.Source.class); attempt.setAccessible(true);
            var tick = task.getClass().getDeclaredMethod("tickActiveChild"); tick.setAccessible(true);
            for (int index = 0; index < 70; index++) {
                set(task, "plannerStepsThisTick", 0); attempt.invoke(task, need, SemanticAcquireTaskRecord.Source.STORAGE);
                check(get(task, "activeRecord") instanceof SemanticContainerTaskRecord, "each unvisited visible box remains eligible");
                set(task, "activeChild", new EmptySettledContainer()); tick.invoke(task);
            }
            check(((List<?>) get(task, "attempts")).size() == 70 && ((List<?>) get(task, "issues")).size() == 70,
                    "all seventy native outcomes and incomplete-source observations remain in the default receipt");
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
