// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.task.TaskState;

/** 真实采矿准备可以追加工具需求，但看到仓库有货不能替主人开放新的取材方式。 */
public final class AcquisitionSourceInheritanceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.BLOCK.bindTags(Map.of(
                BlockTags.MINEABLE_WITH_SHOVEL, List.of(Blocks.DIRT.builtInRegistryHolder())));
        // 只许挖土且没有铲子时，允许报告缺工具，不允许自行消耗材料合成一把。
        prepareTool(List.of(Source.MINE), false, 0, "stone_shovel", Set.of(Source.INVENTORY, Source.MINE));
        // 最近看到的铁属于未授权仓库；它不能把便宜石铲升级成开箱取铁再合成。
        prepareTool(List.of(Source.MINE, Source.CRAFT), true, 0, "stone_shovel",
                Set.of(Source.INVENTORY, Source.MINE, Source.CRAFT));
        // 仓库取材已获准时可用富余铁升级，但该升级只取现货或用现有材料合成，不为此另挖矿。
        prepareTool(List.of(Source.MINE, Source.STORAGE, Source.CRAFT), true, 0, "iron_shovel",
                Set.of(Source.INVENTORY, Source.STORAGE, Source.CRAFT));
        prepareTool(List.of(Source.MINE, Source.STORAGE), true, 0, "iron_shovel",
                Set.of(Source.INVENTORY, Source.STORAGE));
        // 铁已经带在身上也不等于允许合成；背包观察与制造权限是两件事。
        prepareTool(List.of(Source.MINE), false, 64, "iron_shovel", Set.of(Source.INVENTORY));
        inventoryOnlyDoesNotPrepareCrafting();
        sourceOrderUsesCurrentFacts();
        System.out.println("AcquisitionSourceInheritanceTest: passed");
    }

    private static void prepareTool(List<Source> sources, boolean warehouse, int carriedIron,
                                    String expectedTool, Set<Source> expectedSources) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            if (carriedIron > 0) world.inventory.setItem(0, new ItemStack(Items.IRON_INGOT, carriedIron));
            if (warehouse) rememberIron(world);
            var record = new SemanticAcquireTaskRecord("source-inheritance", 1000,
                    List.of(ResourceLocation.withDefaultNamespace("dirt")), 24, sources, false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(world.player, record);
            task.onStart();
            Object root = field(task, "rootNeed");
            var mine = task.getClass().getDeclaredMethod("attemptMine", root.getClass());
            mine.setAccessible(true);
            check(mine.invoke(task, root) == TaskState.RUNNING, "工具准备应留在取物任务内");
            Object tool = ((Deque<?>) field(task, "needs")).peek();
            check(tool != root, "缺少耐用铲子时应先创建工具需求");
            check(Set.copyOf((List<?>) field(tool, "allowedSources")).equals(expectedSources),
                    "工具需求扩大或丢失来源许可: " + field(tool, "allowedSources"));
            check(((List<?>) field(tool, "itemIds")).getFirst().equals(
                    ResourceLocation.withDefaultNamespace(expectedTool)), "未授权仓库改变了工具等级");
            check(world.blockUses() == 0 && world.itemUses() == 0, "准备工具不应提前点击世界");
        } finally {
            // 库存提示是客户端全局缓存，每个场景结束就清理，避免下个玩家继承这次仓库观察。
            Object cache = field(null, StockEvidence.class, "CACHE");
            var clear = cache.getClass().getDeclaredMethod("clear");
            clear.setAccessible(true);
            clear.invoke(cache);
        }
    }

    private static void rememberIron(InteractionWorldTestHarness world) throws Exception {
        Object cache = field(null, StockEvidence.class, "CACHE");
        var remember = cache.getClass().getDeclaredMethod("record", Object.class, Object.class,
                Map.class, StockEvidence.Snapshot.class);
        remember.setAccessible(true);
        remember.invoke(cache, world.player, world.level, Map.of(), new StockEvidence.Snapshot(
                StockEvidence.Source.CONTAINER, Map.of(ResourceLocation.withDefaultNamespace("iron_ingot"), 64L),
                Set.of(), world.level.getGameTime()));
    }

    private static void inventoryOnlyDoesNotPrepareCrafting() throws Exception {
        // 此夹具没有配方管理器；只盘点背包应能正常报告缺料，不应查询合成或烹饪配方。
        try (var world = new InteractionWorldTestHarness()) {
            var record = new SemanticAcquireTaskRecord("inventory-only", 1000,
                    List.of(ResourceLocation.withDefaultNamespace("dirt")), 1, List.of(Source.INVENTORY),
                    false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(world.player, record);
            task.start(world.player);
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 3 && state == TaskState.RUNNING; i++) state = task.tick(world.player);
            check(state == TaskState.FAILED && "allowed_sources_exhausted".equals(
                    task.result(state).data().get("failure_code")), "背包不足应正常耗尽来源，不能因准备未授权合成而崩溃");
        }
    }

    private static void sourceOrderUsesCurrentFacts() {
        var item = ResourceLocation.withDefaultNamespace("dirt");
        var need = new AcquisitionNeed(List.of(item), 1, 0, Set.of(item), Set.of(), Set.of(),
                List.of(Source.COOK, Source.MINE, Source.CRAFT, Source.STORAGE, Source.INVENTORY));
        var ready = new AcquisitionSources.Readiness(true, false, true, false);
        check(AcquisitionSources.order(need, ready).equals(
                List.of(Source.INVENTORY, Source.STORAGE, Source.CRAFT, Source.MINE, Source.COOK)),
                "现货与已经能合成的配方优先，不能照来源参数书写顺序行动");
        need.exhaustedSources.add(Source.CRAFT);
        check(AcquisitionSources.order(need, ready).equals(
                List.of(Source.INVENTORY, Source.STORAGE, Source.MINE, Source.COOK)), "重排不能复活已经耗尽的来源");
    }

    private static Object field(Object target, String name) throws Exception {
        return field(target, target.getClass(), name);
    }

    private static Object field(Object target, Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
