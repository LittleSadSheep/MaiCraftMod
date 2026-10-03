// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.craft.CraftPlanCost;
import org.maiwithu.maicraft.core.task.craft.CraftRecoveryCandidate;
import org.maiwithu.maicraft.task.TaskState;

/** 真实取物调度先完成开局采集，再准备石制工具；工具材料不受原采集祖先误判为循环。 */
public final class AcquisitionMiningToolsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BuiltInRegistries.BLOCK.bindTags(Map.of(
                BlockTags.LOGS, List.of(Blocks.BIRCH_LOG.builtInRegistryHolder()),
                BlockTags.MINEABLE_WITH_AXE, List.of(Blocks.BIRCH_LOG.builtInRegistryHolder()),
                BlockTags.MINEABLE_WITH_PICKAXE, List.of(Blocks.STONE.builtInRegistryHolder(), Blocks.COBBLESTONE.builtInRegistryHolder())));
        BuiltInRegistries.ITEM.bindTags(Map.of(
                ItemTags.LOGS, List.of(Items.BIRCH_LOG.builtInRegistryHolder()),
                ItemTags.PLANKS, List.of(Items.BIRCH_PLANKS.builtInRegistryHolder()),
                ItemTags.STONE_TOOL_MATERIALS, List.of(Items.COBBLESTONE.builtInRegistryHolder())));
        try (var world = new InteractionWorldTestHarness()) {
            // 空背包先徒手取得开局木料，不把石斧作为第一根原木的前置条件。
            assertMining(start(world, "birch_log", 8), 3, false);
            world.inventory.setItem(0, new ItemStack(Items.BIRCH_LOG, 3));
            // 获得木头后复用既有工具链；当前桦木不能因根需求也是桦木而被配方账本排除。
            world.player.connection.getRecipeManager().replaceRecipes(List.of(
                    new RecipeHolder<>(id("planks"), new ShapelessRecipe("", CraftingBookCategory.MISC,
                            new ItemStack(Items.BIRCH_PLANKS, 4), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.BIRCH_LOG)))),
                    new RecipeHolder<>(id("sticks"), new ShapelessRecipe("", CraftingBookCategory.MISC,
                            new ItemStack(Items.STICK, 4), NonNullList.of(Ingredient.EMPTY,
                            Ingredient.of(Items.BIRCH_PLANKS), Ingredient.of(Items.BIRCH_PLANKS))))));
            var task = start(world, "birch_log", 8);
            @SuppressWarnings("unchecked")
            var needs = (Deque<AcquisitionNeed>) field(task, "needs");
            AcquisitionNeed tool = needs.peek();
            check(tool.optionalWorkTool && !tool.stockOnlyTool && tool.itemIds.equals(List.of(id("stone_axe")))
                    && !tool.lineageItems.contains(id("birch_log")) && tool.allowedSources.contains(Source.MINE),
                    "现有木料可用于石斧，缺石料继续走获准采集链");
            var candidate = new CraftRecoveryCandidate(id("stone_axe"), "test:axe", List.of(
                    new CraftRecoveryCandidate.IngredientDemand(List.of(id("stick")), 2, 2)),
                    new CraftPlanCost(2, CraftPlanCost.Surface.READY, 0, 2, "test:axe"), List.of());
            var plan = ((AcquisitionRecipePlanner) field(task, "recipePlanner")).materialPlan(candidate, tool);
            check(plan.feasible() && plan.supplies().isEmpty(), "工具木棍应由已有桦木制作，不另找异地木种");
            // 为石斧补五根原木时，已经在准备的石斧不能再次成为这些原木的效率前置。
            var logs = new AcquisitionNeed(List.of(id("birch_log")), 8, 2,
                    Set.of(id("stone_axe"), id("birch_log")), Set.of(), Set.of(), tool.allowedSources);
            needs.push(logs); invoke(task, "attemptMine", logs); assertMining(task, 5, false);
            invokeNoArgs(task, "clearActive"); needs.pop();
            // 取料后下一项库存失效：连同已发生效果结清可选分支，不能让承诺过的配方卡住伐木。
            var missing = new AcquisitionNeed(List.of(id("stick")), 2, 2, Set.of(id("stick")), Set.of(),
                    Set.of("test:stone_axe"), tool.allowedSources);
            missing.effectsObserved = true;
            tool.committedRecipeIds.add("test:stone_axe");
            needs.push(missing);
            world.inventory.clearContent();
            check(invoke(task, "exhaustNeed", missing) == TaskState.RUNNING, "可选缺料应返回原任务");
            check(needs.size() == 1 && needs.peek().effectsObserved, "结清工具准备的实际效果");
            invoke(task, "attemptMine", needs.peek());
            assertMining(task, 8, false);
            check(needs.size() == 1, "续采不能再次创建缺料石斧需求");
            // 配方已部分加工后失效也只结束这次效率准备，不把材料变动升级成原采集任务失败。
            world.inventory.setItem(0, new ItemStack(Items.BIRCH_LOG, 3));
            var changed = start(world, "birch_log", 8);
            var changedNeeds = (Deque<?>) field(changed, "needs");
            ((AcquisitionNeed) changedNeeds.peek()).effectsObserved = true;
            var fail = changed.getClass().getDeclaredMethod("failAcquisition", String.class, String.class, FailureType.class);
            fail.setAccessible(true);
            check(fail.invoke(changed, "committed_recipe_unavailable", "材料已变动", FailureType.NO_MATERIAL) == TaskState.RUNNING,
                    "已承诺的效率配方失效也应返回原采集");
            invoke(changed, "attemptMine", (AcquisitionNeed) changedNeeds.peek()); assertMining(changed, 5, false);
            // 木镐取得三块圆石后，即使还只缺一块也应先准备石镐，再继续开采。
            world.inventory.clearContent();
            world.inventory.setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
            assertMining(start(world, "cobblestone", 4), 3, true);
            world.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 3));
            var stone = start(world, "cobblestone", 4);
            var stoneNeed = (AcquisitionNeed) ((Deque<?>) field(stone, "needs")).peek();
            check(stoneNeed.itemIds.equals(List.of(id("stone_pickaxe"))) && stoneNeed.optionalWorkTool,
                    "三块圆石后升级石镐，不继续用木镐完成尾批");
        }
        System.out.println("AcquisitionMiningToolsTest: passed");
    }

    private static SemanticAcquireCompanionTask start(InteractionWorldTestHarness world, String item, int count) throws Exception {
        // 走正式矿物来源分支，确认是否真的派发采矿，而不仅检查工具偏好函数的返回值。
        var record = new SemanticAcquireTaskRecord("mining-tools", 1000, List.of(id(item)), count,
                List.of(Source.INVENTORY, Source.MINE, Source.CRAFT), false,
                SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
        var task = new SemanticAcquireCompanionTask(world.player, record); task.onStart();
        invoke(task, "attemptMine", (AcquisitionNeed) field(task, "rootNeed"));
        return task;
    }

    private static void assertMining(SemanticAcquireCompanionTask task, int count, boolean efficient) throws Exception {
        check(field(task, "activeRecord") instanceof MineBlockTaskRecord child
                && child.count == count && child.requireEfficientTool == efficient,
                "按剩余原木数量直接采集，仅在实际带工具时启用耗尽收尾");
    }

    private static Object invoke(SemanticAcquireCompanionTask task, String name, AcquisitionNeed need) throws Exception {
        // 每次手动派发代表下一刻的决策，沿用正式 onTick 每刻重新开放一次规划的预算。
        if (name.equals("attemptMine")) {
            var budget = task.getClass().getDeclaredField("plannerStepsThisTick"); budget.setAccessible(true); budget.setInt(task, 0);
        }
        var method = task.getClass().getDeclaredMethod(name, AcquisitionNeed.class);
        method.setAccessible(true); return method.invoke(task, need);
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(target);
    }

    private static void invokeNoArgs(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }

    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
