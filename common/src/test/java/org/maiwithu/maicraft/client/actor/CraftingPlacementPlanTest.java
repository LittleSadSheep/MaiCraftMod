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
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Player;
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
import org.maiwithu.maicraft.core.task.craft.CraftIngredientReservations;

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
            // 两格相同原料必须共用同一份堆叠容量，一件木板不能被重复预约给两个合成格。
            var repeated = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK),
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.OAK_PLANKS)));
            check(CraftingPlacementPlan.create(h.player, repeated, grid).isEmpty(), "one item cannot satisfy two input slots");
            // 多堆同种建材只扣一次预留；实际菜单选料必须用余量，配方簿不能绕过整机材料份额。
            h.inventory.setItem(1,new ItemStack(Items.OAK_PLANKS,2));
            var oak=BuiltInRegistries.ITEM.getKey(Items.OAK_PLANKS);
            var reservedRecipe=new RecipeHolder<CraftingRecipe>(ResourceLocation.parse("test:reserved_planks"),repeated);book.add(reservedRecipe);
            CraftIngredientReservations.withReserved(Map.of(oak,1),()->{
                var allocation=CraftingPlacementPlan.create(h.player,repeated,grid);
                check(allocation.size()==2&&allocation.stream().allMatch(e->menu.getSlot(e.sourceSlot()).getContainerSlot()==1),"合成只能使用另一堆的两件余量");
                check(!CraftingPlacementPlan.recipeBookUsable(h.player,reservedRecipe),"含预留材料时不能让配方簿自由选料");
                CraftIngredientReservations.withReserved(Map.of(oak,2),()->{
                    check(CraftingPlacementPlan.create(h.player,repeated,grid).isEmpty(),"不能将预留的第二件重复分配给配方");return null;
                });
                check(CraftIngredientReservations.reserved(oak)==1,"嵌套预留结束后应恢复父任务份额");return null;
            });
            check(CraftIngredientReservations.reserved(oak)==0&&h.inventory.countItem(Items.OAK_PLANKS)==3,"选料只读且不保留跨任务约束");
            h.inventory.setItem(0, new ItemStack(Items.COAL)); h.inventory.setItem(1, new ItemStack(Items.STICK));
            var table = new CraftingMenu(4, h.inventory, ContainerLevelAccess.NULL); h.player.containerMenu = table;
            var tableGrid = (CraftingContainer) table.getSlot(1).container;
            plan = CraftingPlacementPlan.create(h.player, torch.value(), tableGrid);
            check(plan.getFirst().targetSlot() == 1 && plan.getLast().targetSlot() == 4,
                    "the same vertical recipe uses the actual three-column table stride");
            // 形状中的空格仍留空，不能因压缩非空材料表而把对角输入挤到同一行。
            var holes = new ShapedRecipe("", CraftingBookCategory.MISC, ShapedRecipePattern.of(
                    Map.of('A', Ingredient.of(Items.COAL), 'B', Ingredient.of(Items.STICK)), List.of("A ", " B")),
                    new ItemStack(Items.TORCH));
            plan = CraftingPlacementPlan.create(h.player, holes, tableGrid);
            check(plan.getFirst().targetSlot() == 1 && plan.getLast().targetSlot() == 5, "shaped holes survive a larger crafting surface");
            // 实际菜单拒绝拿取煤时，煤仍然在背包中；必须报告原生槽位条件，不能将它归类为缺煤。
            int coalSlot = plan.getFirst().sourceSlot();
            table.slots.set(coalSlot, new Slot(h.inventory, 0, 0, 0) {
                @Override public boolean mayPickup(Player player) { return false; }
            });
            boolean rejected = false;
            try { CraftingPlacementPlan.create(h.player, holes, tableGrid); }
            catch (IllegalStateException nativeCondition) { rejected = nativeCondition.getMessage().contains("materials are present"); }
            check(rejected && h.inventory.countItem(Items.COAL) == 1, "native pickup restrictions do not become material shortage");
        } finally { registry.bindTags(previous); }
        System.out.println("CraftingPlacementPlanTest: passed");
    }

    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
