// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.tools.CraftOps;
import org.maiwithu.maicraft.core.task.craft.CraftPlanCost;
import org.maiwithu.maicraft.core.task.craft.CraftRecoveryCandidate;

/** 用真实背包分配与原生配方验证：展示可以截短，内部补料必须保留完整候选。 */
public final class AcquisitionRecipePlanningTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            overlappingIngredients(world);
            completeRecoveryCandidates(world);
            finiteMaterialRoutes(world);
            prerequisiteSelection(world);
            world.inventory.setItem(0, new ItemStack(Items.STICK, 4));
            var alreadyCarried = plan(world);
            check(!alreadyCarried.executable() && alreadyCarried.immediate().success(),
                    "目标数量已够时直接完成，不再补原料");
            check(world.blockUses() == 0 && world.itemUses() == 0, "配方推演不得提前操作游戏");
        }
        System.out.println("AcquisitionRecipePlanningTest: passed");
    }

    private static void overlappingIngredients(InteractionWorldTestHarness world) throws Exception {
        // 两块木板要分别满足“任意木板”和“必须橡木”；不能先把唯一橡木分给前者再误报缺料。
        install(world, List.of(recipe("overlap", Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS),
                Ingredient.of(Items.OAK_PLANKS))));
        world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
        world.inventory.setItem(1, new ItemStack(Items.BIRCH_PLANKS));
        var ready = plan(world);
        check(ready.executable() && ready.task().count == 4, "重叠材料必须按真实容量分配");
        world.inventory.setItem(0, ItemStack.EMPTY);
        var missing = plan(world);
        check(!missing.executable() && missing.recoveryCandidates().size() == 1, "缺橡木时留下原配方供补料");
        var candidate = missing.recoveryCandidates().getFirst();
        check(candidate.recipeId().equals("test:overlap") && candidate.cost().missingMaterials() == 1
                && candidate.cost().stableId().equals("minecraft:stick|test:overlap"),
                "候选保留缺料数量及跨输出稳定顺序");
    }

    private static void completeRecoveryCandidates(InteractionWorldTestHarness world) throws Exception {
        world.inventory.clearContent();
        var accepted = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).limit(80)
                .map(ItemStack::new).toArray(ItemStack[]::new);
        var recipes = new ArrayList<RecipeHolder<?>>();
        for (int i = 0; i < 10; i++) recipes.add(recipe("choice_" + i, Ingredient.of(accepted)));
        install(world, recipes);
        var missing = plan(world);
        check(missing.recoveryCandidates().size() == 10, "内部保留超过展示上限的全部十条路线");
        var reported = (List<?>) missing.immediate().data().get("candidate_recipes");
        check(reported.size() == 8, "对外报告仍遵守长度上限");
        var ingredient = missing.recoveryCandidates().getFirst().ingredients().getFirst();
        check(ingredient.itemIds().size() == 80,
                "内部补料不能继承展示的六十四种原料截断");
    }

    private static CraftOps.Plan plan(InteractionWorldTestHarness world) {
        return new CraftOps().plan("minecraft:stick", 4, world.player, new ToolContext("recipe-test", 0));
    }

    private static RecipeHolder<?> recipe(String id, Ingredient... ingredients) {
        return recipe(id, Items.STICK, ingredients);
    }

    private static RecipeHolder<?> recipe(String id, Item output, Ingredient... ingredients) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("test", id),
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(output, 4),
                        NonNullList.of(Ingredient.EMPTY, ingredients)));
    }

    private static void finiteMaterialRoutes(InteractionWorldTestHarness world) throws Exception {
        world.inventory.clearContent();
        var plank = BuiltInRegistries.ITEM.getKey(Items.OAK_PLANKS);
        var stick = BuiltInRegistries.ITEM.getKey(Items.STICK);
        var need = new AcquisitionNeed(List.of(stick), 4, 0, Set.of(stick), Set.of(), Set.of(),
                List.of(SemanticAcquireTaskRecord.Source.INVENTORY, SemanticAcquireTaskRecord.Source.CRAFT));
        var planks = recipe("planks", Items.OAK_PLANKS, Ingredient.of(Items.OAK_LOG));
        install(world, List.of(planks));
        var planner = new AcquisitionRecipePlanner(world.player, false, 16, List.of());
        check(planner.ingredientStructureCost(List.of(plank), need) == 1, "原木变木板还需一层合成");
        check(planner.ingredientStructureCost(List.of(plank, BuiltInRegistries.ITEM.getKey(Items.BIRCH_LOG)), need) == 0,
                "原料可替代时保留较直接的来源，不强制制造另一个变体");
        var candidate = new CraftRecoveryCandidate(stick, "test:sticks",
                List.of(new CraftRecoveryCandidate.IngredientDemand(List.of(plank), 2, 2)),
                new CraftPlanCost(2, CraftPlanCost.Surface.READY, 0, 2, "test:sticks"), List.of());
        check(planner.structureCost(candidate, need) == 4, "候选成本同时计算缺料量与原料转换层数");
        // 构造往返配方复现循环：木板依赖原木，原木又依赖木板；两者都没带时不能当作现成来源。
        install(world, List.of(planks, recipe("reverse", Items.OAK_LOG, Ingredient.of(Items.OAK_PLANKS))));
        planner = new AcquisitionRecipePlanner(world.player, false, 16, List.of());
        check(planner.ingredientStructureCost(List.of(plank), need) == AcquisitionRecipePlanner.UNREACHABLE_STRUCTURE_COST,
                "循环路线不能排成零成本叶子");
        world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
        check(planner.ingredientStructureCost(List.of(plank), need) == 0, "已经拿到材料后按现货重新评价");
    }

    private static void install(InteractionWorldTestHarness world, List<RecipeHolder<?>> recipes) throws Exception {
        var manager = new RecipeManager(RegistryAccess.EMPTY);
        manager.replaceRecipes(recipes);
        set(ClientPacketListener.class, world.player.connection, "recipeManager", manager);
        set(Level.class, world.level, "registryAccess", RegistryAccess.EMPTY);
        world.player.containerMenu = new InventoryMenu(world.inventory, false, world.player);
    }

    private static void prerequisiteSelection(InteractionWorldTestHarness world) throws Exception {
        world.inventory.clearContent();
        install(world, List.of());
        var stick = BuiltInRegistries.ITEM.getKey(Items.STICK);
        var plank = BuiltInRegistries.ITEM.getKey(Items.OAK_PLANKS);
        var coal = BuiltInRegistries.ITEM.getKey(Items.COAL);
        var wool = BuiltInRegistries.ITEM.getKey(Items.WHITE_WOOL);
        var need = new AcquisitionNeed(List.of(stick), 4, 0, Set.of(stick), Set.of(), Set.of(),
                List.of(SemanticAcquireTaskRecord.Source.CRAFT, SemanticAcquireTaskRecord.Source.HUNT));
        var planner = new AcquisitionRecipePlanner(world.player, false, 16, List.of());
        var onePlank = new CraftRecoveryCandidate.IngredientDemand(List.of(plank), 1, 1);
        var repeated = candidate(stick, "three_slots", List.of(onePlank, onePlank, onePlank));
        check(planner.chooseIngredient(repeated, need).missing() == 3, "三个相同配方格应合成一项缺三份的需求");
        var ancestor = candidate(stick, "cycle", List.of(
                new CraftRecoveryCandidate.IngredientDemand(List.of(stick), 1, 1),
                new CraftRecoveryCandidate.IngredientDemand(List.of(coal), 1, 1)));
        check(planner.chooseIngredient(ancestor, need) == null, "关键材料绕回祖先时不能先去补其他配件");
        // 羊毛可能需要伤害羊；尚未允许伤害时先暴露这个条件，不能先采煤、最后才发现整件事做不了。
        var restricted = candidate(stick, "permission", List.of(
                new CraftRecoveryCandidate.IngredientDemand(List.of(coal), 2, 2),
                new CraftRecoveryCandidate.IngredientDemand(List.of(wool), 1, 1)));
        check(planner.chooseIngredient(restricted, need).itemIds().equals(List.of(wool)),
                "先检查受伤害许可限制的原料");
        // 两条路线各只差一件，拿到任意一种即可做成；多件路线不能把不同配方的半套材料相加。
        var first = candidate(stick, "first", List.of(onePlank));
        var second = candidate(stick, "second",
                List.of(new CraftRecoveryCandidate.IngredientDemand(List.of(coal), 1, 1)));
        var merged = planner.chooseFrontier(first, List.of(first, second), need);
        check(merged.ingredient().missing() == 1 && Set.copyOf(merged.ingredient().itemIds()).equals(Set.of(plank, coal))
                        && merged.recipeIds().equals(Set.of("first", "second")),
                "一件替代原料足以解锁任意一条完整配方");
        var separate = planner.chooseFrontier(repeated, List.of(repeated, second), need);
        check(separate.ingredient().missing() == 3 && separate.recipeIds().equals(Set.of("three_slots")),
                "不能把另一条路线的一件原料混进缺三件的配方");
    }

    private static CraftRecoveryCandidate candidate(ResourceLocation output, String id,
                                                     List<CraftRecoveryCandidate.IngredientDemand> ingredients) {
        int missing = ingredients.stream().mapToInt(CraftRecoveryCandidate.IngredientDemand::missing).sum();
        return new CraftRecoveryCandidate(output, id, ingredients,
                new CraftPlanCost(missing, CraftPlanCost.Surface.READY, 0, missing, id), List.of());
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
