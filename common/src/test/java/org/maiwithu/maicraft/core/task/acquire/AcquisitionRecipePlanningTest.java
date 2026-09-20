// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
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

/** 用真实背包分配与原生配方验证：展示可以截短，内部补料必须保留完整候选。 */
public final class AcquisitionRecipePlanningTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            overlappingIngredients(world);
            completeRecoveryCandidates(world);
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
        var facts = missing.recoveryCandidates().getFirst().data();
        var ingredient = (Map<?, ?>) ((List<?>) facts.get("ingredients")).getFirst();
        check(((List<?>) ingredient.get("acceptable_item_ids")).size() == 80,
                "内部补料不能继承展示的六十四种原料截断");
    }

    private static CraftOps.Plan plan(InteractionWorldTestHarness world) {
        return new CraftOps().plan("minecraft:stick", 4, world.player, new ToolContext("recipe-test", 0));
    }

    private static RecipeHolder<?> recipe(String id, Ingredient... ingredients) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("test", id),
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK, 4),
                        NonNullList.of(Ingredient.EMPTY, ingredients)));
    }

    private static void install(InteractionWorldTestHarness world, List<RecipeHolder<?>> recipes) throws Exception {
        var manager = new RecipeManager(RegistryAccess.EMPTY);
        manager.replaceRecipes(recipes);
        set(ClientPacketListener.class, world.player.connection, "recipeManager", manager);
        set(Level.class, world.level, "registryAccess", RegistryAccess.EMPTY);
        world.player.containerMenu = new InventoryMenu(world.inventory, false, world.player);
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
