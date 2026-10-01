package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.maiwithu.maicraft.core.task.craft.CraftingPlacementPlan;

/** 回放火把竖向摆料、标签替代品、重叠输入和命名材料，保证一批选料与真实菜单对应。 */
public final class CraftingPlacementPlanTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registry = BuiltInRegistries.ITEM;
        var previous = registry.getTags().collect(Collectors.toMap(
                pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
        var rods = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:rods/wooden"));
        try (var h = new InteractionWorldTestHarness()) {
            registry.bindTags(Map.of(rods, List.of(Items.STICK.builtInRegistryHolder(), Items.BAMBOO.builtInRegistryHolder())));
            var menu = new InventoryMenu(h.inventory, true, h.player);
            ActorControlTestHarness.field(h.player.getClass(), "inventoryMenu").set(h.player, menu); h.player.containerMenu = menu;
            var book = new ClientRecipeBook(); ActorControlTestHarness.field(h.player.getClass(), "recipeBook").set(h.player, book);
            var grid = (CraftingContainer) menu.getSlot(1).container;
            var torch = new RecipeHolder<CraftingRecipe>(ResourceLocation.parse("minecraft:torch"), new ShapedRecipe("",
                    CraftingBookCategory.MISC, ShapedRecipePattern.of(Map.of('A', Ingredient.of(Items.COAL, Items.CHARCOAL),
                            'B', Ingredient.of(rods)), List.of("A", "B")), new ItemStack(Items.TORCH, 4)));
            h.inventory.setItem(0, new ItemStack(Items.COAL, 2)); h.inventory.setItem(1, new ItemStack(Items.STICK, 2));
            var plan = CraftingPlacementPlan.create(h.player, torch.value(), grid);
            check(plan.size() == 2 && plan.getFirst().targetSlot() == 1 && plan.getLast().targetSlot() == 3,
                    "a vertical torch recipe keeps its row stride in the inventory grid");
            check(!CraftingPlacementPlan.recipeBookUsable(h.player, torch), "unlearned recipes use native slot placement");
            book.add(torch);
            check(CraftingPlacementPlan.recipeBookUsable(h.player, torch), "ordinary learned recipes retain the recipe book");

            // 命名木棍仍符合木杆标签；保留其全部组件，用逐格点击路径代替排除命名物品的配方簿。
            h.inventory.getItem(1).set(DataComponents.CUSTOM_NAME, Component.literal("施工木棍"));
            plan = CraftingPlacementPlan.create(h.player, torch.value(), grid);
            check(plan.size() == 2 && plan.getLast().sample().has(DataComponents.CUSTOM_NAME)
                    && !CraftingPlacementPlan.recipeBookUsable(h.player, torch), "named legal inputs remain craftable");
            h.inventory.setItem(0, new ItemStack(Items.CHARCOAL)); h.inventory.setItem(1, new ItemStack(Items.BAMBOO));
            plan = CraftingPlacementPlan.create(h.player, torch.value(), grid);
            check(plan.size() == 2 && plan.getLast().sample().is(Items.BAMBOO), "current tag membership selects alternatives without stick special cases");
            h.inventory.setItem(1, new ItemStack(Items.BLAZE_ROD));
            check(CraftingPlacementPlan.create(h.player, torch.value(), grid).isEmpty(), "unaccepted rods remain missing materials");

            // 宽泛木板先占用橡木时必须能把它移交给精确输入，不能因槽位顺序误报缺料。
            h.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS)); h.inventory.setItem(1, new ItemStack(Items.BIRCH_PLANKS));
            var overlap = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK),
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS), Ingredient.of(Items.OAK_PLANKS)));
            plan = CraftingPlacementPlan.create(h.player, overlap, grid);
            check(plan.getFirst().sample().is(Items.BIRCH_PLANKS) && plan.getLast().sample().is(Items.OAK_PLANKS),
                    "overlapping ingredients receive a complete allocation");
            check(h.inventory.countItem(Items.OAK_PLANKS) == 1 && menu.getSlot(1).getItem().isEmpty(), "planning never moves materials");
        } finally { registry.bindTags(previous); }
        System.out.println("CraftingPlacementPlanTest: passed");
    }

    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
