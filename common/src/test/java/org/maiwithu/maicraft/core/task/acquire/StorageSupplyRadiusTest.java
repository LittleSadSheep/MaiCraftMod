// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.task.craft.CraftRecoveryCandidate;
import org.maiwithu.maicraft.core.task.craft.CraftPlanCost;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySources;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySourcesTest;
import org.maiwithu.maicraft.core.task.container.SemanticContainerCompanionTask;
import org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 出坑后可向三十一格外已加载仓库取料；测试半径分离，不扩大采矿许可或把库存提示当成实际取到材料。 */
public final class StorageSupplyRadiusTest {
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone");
    private static final ResourceLocation BRICKS = ResourceLocation.parse("minecraft:stone_bricks");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        publicRadiusAndSourcePermissionsStaySeparate();
        coordinatorSearchesWarehouseAndRecipeEvidenceAtThirtyOneBlocks();
        System.out.println("StorageSupplyRadiusTest: passed");
    }

    private static void publicRadiusAndSourcePermissionsStaySeparate() throws Exception {
        var sources = List.of(SemanticAcquireTaskRecord.Source.STORAGE, SemanticAcquireTaskRecord.Source.MINE, SemanticAcquireTaskRecord.Source.NEARBY);
        var explicit = new SemanticAcquireTaskRecord("public-eight", 1000, List.of(BRICKS), 4, sources, false,
                SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 8);
        check(explicit.searchRadius == 8 && explicit.storageSearchRadius == 8,
                "a public explicit radius remains unchanged for every source, including storage");
        var bounded = new SemanticAcquireTaskRecord("internal-bounded", 1000, List.of(BRICKS), 4, sources, false,
                SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16, Integer.MAX_VALUE);
        check(bounded.searchRadius == 16 && bounded.storageSearchRadius == 48, "only storage receives the capped larger radius");
        var noStorage = new SemanticAcquireTaskRecord("no-storage", 1000, List.of(BRICKS), 4,
                List.of(SemanticAcquireTaskRecord.Source.MINE), false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16, 48);
        check(noStorage.storageSearchRadius == 16 && !noStorage.allowedSources.contains(SemanticAcquireTaskRecord.Source.STORAGE),
                "an internal radius argument cannot grant a missing storage permission");
        try (var h = new InteractionWorldTestHarness()) {
            for (var policy : List.of(SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY, SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY)) {
                var coordinator = begin(h, policy, List.of());
                var record = (SemanticAcquireTaskRecord) get(coordinator, "childRecord");
                check(record.searchRadius == 16 && record.storageSearchRadius == 16
                        && !record.allowedSources.contains(SemanticAcquireTaskRecord.Source.STORAGE),
                        "a coordinator without storage authorization retains its original local radius");
                coordinator.cancel(h.player);
            }
        }
    }

    private static void coordinatorSearchesWarehouseAndRecipeEvidenceAtThirtyOneBlocks() throws Exception {
        var runners = runners(); var before = runners.get(SemanticContainerTaskRecord.class);
        try (var h = new InteractionWorldTestHarness()) {
            TaskFactory.register(SemanticContainerTaskRecord.class, SemanticContainerCompanionTask::new);
            var entities = ContainerSupplySourcesTest.worldEntities(h); BlockPos barrel = new BlockPos(1, 1, 1);
            ContainerSupplySourcesTest.addBarrel(h, entities, barrel);
            // 玩家位置只作为三十一格外的查询中心；仓库仍来自夹具中真实已加载区块，不启动导航或加载新区域。
            h.position(new Vec3(1.5, 1, 32.5));
            var sources = List.of(SemanticAcquireTaskRecord.Source.STORAGE, SemanticAcquireTaskRecord.Source.MINE, SemanticAcquireTaskRecord.Source.NEARBY);
            var coordinator = begin(h, SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY, sources);
            var record = (SemanticAcquireTaskRecord) get(coordinator, "childRecord");
            check(record.searchRadius == 16 && record.storageSearchRadius == 48
                    && record.allowedSources.containsAll(sources) && !record.allowHarm,
                    "resupply only extends authorized warehouse search, leaving nearby/mining radius and harm policy intact");
            check(ContainerSupplySources.candidates(h.player, h.player.blockPosition(), record.searchRadius,
                    record.itemIds, Set.of(), List.of()).isEmpty(), "the old sixteen-block query must reproduce the missed warehouse");
            var task = (SemanticAcquireCompanionTask) get(coordinator, "child"); task.onStart(); Object need = get(task, "rootNeed");
            Method attempt = task.getClass().getDeclaredMethod("attemptStorage", need.getClass()); attempt.setAccessible(true);
            check(attempt.invoke(task, need) == TaskState.RUNNING, "ordinary storage acquisition starts through the actual expanded call site");
            var selected = (SemanticContainerTaskRecord) get(task, "activeRecord");
            check(selected.storageSupply() && selected.supplyPosition.equals(barrel), "the thirty-one-block warehouse is selected before a manufacturing fallback");

            // 明确注入一份曾经打开菜单看到的库存记录，检验配方提示半径；不能直接读木桶方块实体猜货物。
            Object cache = staticGet(ContainerSupplySources.class, "CACHE");
            Method remember = cache.getClass().getDeclaredMethod("record", Object.class, Object.class, Map.class, StockEvidence.Snapshot.class); remember.setAccessible(true);
            remember.invoke(cache, h.player, h.level, Map.of(barrel, entities.get(barrel)),
                    new StockEvidence.Snapshot(StockEvidence.Source.CONTAINER, Map.of(STONE, 1124L), Set.of(), h.level.getGameTime()));
            Object candidate = stoneBrickRecipe();
            check(hint(task, candidate, need) == 0 && ((Map<?, ?>) get(task, "recipeObservedStock")).get(STONE).equals(1124L),
                    "the actual recipe hint call sees observed stock at the same extended warehouse radius");
            var explicit = new SemanticAcquireTaskRecord("public-sixteen", 1000, List.of(BRICKS), 4, sources, false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var local = new SemanticAcquireCompanionTask(h.player, explicit); local.onStart();
            check(hint(local, candidate, get(local, "rootNeed")) == 1 && ((Map<?, ?>) get(local, "recipeObservedStock")).isEmpty(),
                    "the same remembered warehouse remains outside an explicit public sixteen-block request");
            var forbidden = new SemanticAcquireTaskRecord("storage-forbidden", 1000, List.of(BRICKS), 4,
                    List.of(SemanticAcquireTaskRecord.Source.CRAFT), false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 48);
            var craftOnly = new SemanticAcquireCompanionTask(h.player, forbidden); craftOnly.onStart();
            check(hint(craftOnly, candidate, get(craftOnly, "rootNeed")) == 1, "a wide non-storage request still cannot use warehouse evidence");
            check(h.blockUses() == 0 && h.itemUses() == 0 && h.inventory.isEmpty(), "radius planning and recipe hints perform no world click or hidden inventory transfer");
            coordinator.cancel(h.player);
        } finally {
            ContainerSupplySources.reset();
            if (before == null) runners.remove(SemanticContainerTaskRecord.class); else runners.put(SemanticContainerTaskRecord.class, before);
        }
    }

    private static SemanticMaterialSupplyCoordinator begin(InteractionWorldTestHarness h, SemanticMaterialSupplyCoordinator.MaterialPolicy policy,
            List<SemanticAcquireTaskRecord.Source> sources) {
        var coordinator = new SemanticMaterialSupplyCoordinator();
        coordinator.begin(h.player, "construction-supply", 1000,
                new SemanticMaterialSupplyCoordinator.Demand(List.of(BRICKS), 4, "the next building batch"), policy, sources, false, List.of());
        return coordinator;
    }
    private static Object stoneBrickRecipe() throws Exception {
        // 用实际传递的配方候选验证仓库提示，不再反射创建执行器的私有展示结构。
        return new CraftRecoveryCandidate(BRICKS, "minecraft:stone_bricks",
                List.of(new CraftRecoveryCandidate.IngredientDemand(List.of(STONE), 4, 4)),
                new CraftPlanCost(4, CraftPlanCost.Surface.READY, 0, 4, "minecraft:stone_bricks"), List.of());
    }
    private static int hint(Object task, Object candidate, Object need) throws Exception {
        Method method = task.getClass().getDeclaredMethod("observedStockPriority", candidate.getClass(), need.getClass());
        method.setAccessible(true); return (int) method.invoke(task, candidate, need);
    }
    @SuppressWarnings("unchecked") private static Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>> runners() throws Exception {
        return (Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>>) staticGet(TaskFactory.class, "RUNNERS");
    }
    private static Object staticGet(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null); }
    private static Object get(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
