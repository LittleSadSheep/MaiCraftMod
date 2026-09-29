// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.craft.CraftPlanCost;
import org.maiwithu.maicraft.core.task.craft.CraftRecoveryCandidate;
import org.maiwithu.maicraft.task.TaskState;

/** 离线回放配方前沿：核对替代材料失败回退与已发生效果后的停止边界。 */
public final class RecipeFrontierFallbackTest {
    private static final ResourceLocation CHEST = id("minecraft:chest");
    private static final ResourceLocation OAK_PLANKS = id("minecraft:oak_planks");
    private static final ResourceLocation BIRCH_PLANKS = id("minecraft:birch_planks");
    private static final ResourceLocation OAK_LOG = id("minecraft:oak_log");
    private static final ResourceLocation BIRCH_LOG = id("minecraft:birch_log");
    private static final String CHEST_RECIPE = "minecraft:chest";

    private RecipeFrontierFallbackTest() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<AssertionError> failures = new ArrayList<>();
        runCase("窄化替代材料失败后保留父配方", RecipeFrontierFallbackTest::narrowedAlternativeKeepsParentRoute, failures);
        runCase("真实效果出现后保留承诺停止边界", RecipeFrontierFallbackTest::observedEffectsStillStopRouteSwitching, failures);
        if (!failures.isEmpty()) {
            AssertionError failed = new AssertionError("配方前沿回放发现 " + failures.size() + " 项行为不符合预期");
            failures.forEach(failed::addSuppressed);
            throw failed;
        }
        System.out.println("RecipeFrontierFallbackTest: all replay checks passed");
    }

    private static void narrowedAlternativeKeepsParentRoute() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.inventory.clearContent();
            install(world, List.of(
                    recipe("oak_planks", Items.OAK_PLANKS, Ingredient.of(Items.OAK_LOG)),
                    recipe("birch_planks", Items.BIRCH_PLANKS, Ingredient.of(Items.BIRCH_LOG))));
            var record = record(List.of(CHEST), 4);
            var task = new SemanticAcquireCompanionTask(world.player, record);
            task.onStart();
            AcquisitionNeed parent = (AcquisitionNeed) get(task, "rootNeed");
            CraftRecoveryCandidate chest = candidate(CHEST_RECIPE, List.of(OAK_PLANKS, BIRCH_PLANKS), 16);
            var planner = new AcquisitionRecipePlanner(world.player, false, 16, List.of());

            // 缺少任一木板时先由整树成本选一条木种路线，回到子需求的只剩对应原木。
            RecipeMaterialPlan.Result chosenPlan = planner.materialPlan(chest, parent);
            AcquisitionRecipePlanner.Frontier frontier = planner.chooseFrontier(chest, List.of(chest), parent);
            check(chosenPlan.feasible() && frontier != null, "胸箱配方应能生成一个有限补料前沿");
            check(frontier.ingredient().itemIds().size() == 1,
                    "本回放应先把两种可替代木板收窄到一条原木补料路线");
            ResourceLocation selectedLog = frontier.ingredient().itemIds().getFirst();
            ResourceLocation remainingPlank = selectedLog.equals(OAK_LOG) ? BIRCH_PLANKS : OAK_PLANKS;
            ResourceLocation remainingLog = remainingPlank.equals(OAK_PLANKS) ? OAK_LOG : BIRCH_LOG;
            CraftRecoveryCandidate sibling = candidate("test:chest_sibling_probe", List.of(remainingPlank), 16);
            RecipeMaterialPlan.Result siblingPlan = new AcquisitionRecipePlanner(world.player, false, 16, List.of())
                    .materialPlan(sibling, parent);
            check(siblingPlan.feasible() && siblingPlan.supplies().stream()
                            .anyMatch(need -> need.alternatives().equals(List.of(remainingLog))),
                    "被收窄掉的另一种木板仍须有可获取的原木前置路线");

            System.out.println("narrowed frontier=" + frontier.ingredient().itemIds()
                    + "; accepted parent alternatives=" + chest.ingredients().getFirst().itemIds()
                    + "; sibling supplies=" + siblingPlan.supplies());
            // 子需求仅模拟无库存、无世界变化的来源耗尽；真实交互仍由离线 harness 保持为零。
            AcquisitionNeed child = childNeed(parent, frontier);
            parent.committedRecipeIds.addAll(frontier.recipeIds());
            push(task, child);
            TaskState returned = invoke(task, "exhaustNeed", AcquisitionNeed.class, child);
            System.out.println("no-effect child result=" + returned + "; rejected parent recipes=" + parent.rejectedRecipes);
            check(!parent.rejectedRecipes.contains(CHEST_RECIPE),
                    "一个被收窄的木种路线无效果失败时，不应永久排除仍有可获取替代原料的 minecraft:chest 配方");
            var retry = planner.chooseFrontier(chest, List.of(chest), parent);
            check(retry != null && retry.ingredient().itemIds().equals(List.of(remainingLog)),
                    "同一个规划器必须立即切到剩余分支，不能从缓存再次取得刚失败的原木");
            // 以后真正拿到已排除木种的现货时仍可使用；排除的是获取入口，不是抹掉已有库存。
            world.inventory.setItem(0, new ItemStack(selectedLog.equals(OAK_LOG) ? Items.OAK_LOG : Items.BIRCH_LOG, 4));
            world.nextTick();
            check(planner.materialPlan(chest, parent).supplies().isEmpty(), "新观察到的足量现货仍能完成原配方");
            check(world.itemUses() == 0 && world.blockUses() == 0,
                    "离线回放不得执行取物或方块交互");
        }
    }

    private static void observedEffectsStillStopRouteSwitching() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var record = record(List.of(CHEST), 4);
            var task = new SemanticAcquireCompanionTask(world.player, record);
            task.onStart();
            AcquisitionNeed parent = (AcquisitionNeed) get(task, "rootNeed");
            AcquisitionNeed child = new AcquisitionNeed(List.of(OAK_LOG), 4, 1,
                    Set.of(CHEST, OAK_LOG), Set.of(CHEST_RECIPE), Set.of(CHEST_RECIPE), record.allowedSources);
            parent.committedRecipeIds.add(CHEST_RECIPE);
            // 注入已观察到子任务效果的事实，复核原有承诺边界仍会停止而不是盲目改走桦木路线。
            child.effectsObserved = true;
            child.materialTreeFrontier = true;
            push(task, child);
            TaskState result = invoke(task, "exhaustNeed", AcquisitionNeed.class, child);
            check(result == TaskState.FAILED
                            && "committed_prerequisite_unmet".equals(get(task, "failureCode")),
                    "承诺配方的材料已产生效果后，子前置耗尽必须停下来等待恢复");
            check(parent.committedRecipeIds.contains(CHEST_RECIPE)
                            && !parent.rejectedRecipes.contains(CHEST_RECIPE),
                    "观察到效果后不能清除承诺并自动切换到另一个父配方");
            System.out.println("effectful child result=" + result + "; committed=" + parent.committedRecipeIds
                    + "; rejected=" + parent.rejectedRecipes);
        }
    }

    private static AcquisitionNeed childNeed(
            AcquisitionNeed parent, AcquisitionRecipePlanner.Frontier frontier) {
        Set<ResourceLocation> lineageItems = new LinkedHashSet<>(parent.lineageItems);
        lineageItems.addAll(frontier.ingredient().itemIds());
        Set<String> lineageRecipes = new LinkedHashSet<>(parent.lineageRecipes);
        lineageRecipes.addAll(frontier.recipeIds());
        var child = new AcquisitionNeed(frontier.ingredient().itemIds(), frontier.ingredient().missing(),
                parent.depth + 1, lineageItems, lineageRecipes, frontier.recipeIds(), parent.allowedSources);
        child.materialTreeFrontier = true; return child;
    }

    private static CraftRecoveryCandidate candidate(String recipeId, List<ResourceLocation> alternatives, int missing) {
        return new CraftRecoveryCandidate(CHEST, recipeId,
                List.of(new CraftRecoveryCandidate.IngredientDemand(alternatives, missing, missing)),
                new CraftPlanCost(missing, CraftPlanCost.Surface.READY, 0, missing, recipeId), List.of());
    }

    private static void install(InteractionWorldTestHarness world, List<RecipeHolder<?>> recipes) throws Exception {
        RecipeManager manager = new RecipeManager(RegistryAccess.EMPTY);
        manager.replaceRecipes(recipes);
        set(ClientPacketListener.class, world.player.connection, "recipeManager", manager);
        set(Level.class, world.level, "registryAccess", RegistryAccess.EMPTY);
        world.player.containerMenu = new InventoryMenu(world.inventory, false, world.player);
    }

    private static RecipeHolder<?> recipe(String name, Item output, Ingredient... ingredients) {
        return new RecipeHolder<>(id("test:" + name), new ShapelessRecipe("", CraftingBookCategory.MISC,
                new ItemStack(output, 4), NonNullList.of(Ingredient.EMPTY, ingredients)));
    }

    private static SemanticAcquireTaskRecord record(List<ResourceLocation> items, int count) {
        return new SemanticAcquireTaskRecord("recipe-frontier-replay", 1000, items, count,
                List.of(SemanticAcquireTaskRecord.Source.INVENTORY,
                        SemanticAcquireTaskRecord.Source.CRAFT,
                        SemanticAcquireTaskRecord.Source.MINE), false,
                SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }

    private static void push(SemanticAcquireCompanionTask task, AcquisitionNeed need) throws Exception {
        @SuppressWarnings("unchecked")
        Deque<AcquisitionNeed> stack = (Deque<AcquisitionNeed>) get(task, "needs");
        stack.push(need);
    }

    private static TaskState invoke(
            SemanticAcquireCompanionTask task, String methodName,
            Class<?> argumentType, Object argument) throws Exception {
        var method = task.getClass().getDeclaredMethod(methodName, argumentType);
        method.setAccessible(true);
        return (TaskState) method.invoke(task, argument);
    }

    private static Object get(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> type, Object target, String fieldName, Object value) throws Exception {
        var field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ReplayCase {
        void run() throws Exception;
    }

    private static void runCase(String name, ReplayCase replay, List<AssertionError> failures) {
        try {
            replay.run();
            System.out.println("PASS: " + name);
        } catch (Throwable failure) {
            System.out.println("FAIL: " + name + " -> " + failure);
            failure.printStackTrace(System.out);
            failures.add(failure instanceof AssertionError assertion
                    ? assertion : new AssertionError(name, failure));
        }
    }
}
