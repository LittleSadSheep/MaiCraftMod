package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import org.maiwithu.maicraft.core.task.craft.CraftCompanionTask;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 从真实背包材料到两批火把入包，核对未解锁标签配方、命名材料和失败后原料返还。 */
public final class CraftingTaskTagTest {
    private static final ResourceLocation TORCH = ResourceLocation.parse("minecraft:torch");

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registry = BuiltInRegistries.ITEM;
        var previous = registry.getTags().collect(Collectors.toMap(
                pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
        var rods = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:rods/wooden"));
        try {
            registry.bindTags(Map.of(rods, List.of(Items.STICK.builtInRegistryHolder())));
            scenario(rods, false, true, false);
            scenario(rods, true, true, false);
            scenario(rods, false, false, false);
            scenario(rods, false, true, true);
            bookTimeout(rods);
        } finally { registry.bindTags(previous); }
        System.out.println("CraftingTaskTagTest: passed");
    }

    private static RecipeHolder<CraftingRecipe> recipe(TagKey<Item> rods) {
        return new RecipeHolder<>(TORCH, new ShapedRecipe("", CraftingBookCategory.MISC, ShapedRecipePattern.of(
                Map.of('A', Ingredient.of(Items.COAL, Items.CHARCOAL), 'B', Ingredient.of(rods)), List.of("A", "B")),
                new ItemStack(Items.TORCH, 4)));
    }

    private static void install(InteractionWorldTestHarness h, RecipeHolder<CraftingRecipe> recipe) throws Exception {
        var manager = new RecipeManager(RegistryAccess.EMPTY); manager.replaceRecipes(List.of(recipe));
        ActorControlTestHarness.field(ClientPacketListener.class, "recipeManager").set(h.h.connection, manager);
        h.inventory.setItem(0, new ItemStack(Items.COAL, 2)); h.inventory.setItem(1, new ItemStack(Items.STICK, 2));
    }

    private static void scenario(TagKey<Item> rods, boolean named, boolean deliver, boolean removeMaterial) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.enableCraftingTransactions(); var recipe = recipe(rods); install(h, recipe);
            if (named) {
                // 已解锁配方仍可能因为材料带名字而无法用配方簿；原生标签谓词继续接受这根实际木棍。
                h.player.getRecipeBook().add(recipe);
                h.inventory.getItem(1).set(DataComponents.CUSTOM_NAME, Component.literal("施工木棍"));
            }
            var menu = h.player.containerMenu; var grid = (CraftingContainer) menu.getSlot(1).container;
            h.mode.afterCraftingClick = () -> {
                // 夹具仅模拟服务器对真实输入的原生配方结算，取出和材料消耗仍由真实 ResultSlot 完成。
                ItemStack result = deliver && recipe.value().matches(grid.asCraftInput(), h.level)
                        ? recipe.value().assemble(grid.asCraftInput(), RegistryAccess.EMPTY) : ItemStack.EMPTY;
                menu.getSlot(0).set(result);
            };
            var task = new CraftCompanionTask(h.player, new CraftTaskRecord("torch-native", 1000, TORCH, 8, 2, 4, null));
            task.start(h.player); TaskState state = TaskState.RUNNING; boolean removed = false;
            for (int tick = 0; tick < 400 && state == TaskState.RUNNING; tick++) {
                MenuVisibility.rendered(h.h.minecraft.screen); state = task.tick(h.player);
                if (removeMaterial && !removed && h.inventory.countItem(Items.TORCH) == 4) {
                    // 第一批已入包后库存发生实际变化，第二批应诚实报告缺料，同时保留已完成的四个火把。
                    h.inventory.setItem(0, ItemStack.EMPTY); removed = true;
                }
                h.nextTick();
            }
            var result = task.result(state);
            check(state == (deliver && !removeMaterial ? TaskState.SUCCESS : TaskState.FAILED), "native task reaches its expected terminal state");
            check(result.data().get("crafting_placement_method").equals("native_grid_clicks") && h.mode.recipePlacements == 0,
                    "the task selects native slot placement for unlearned or named-material recipes");
            if (deliver && !removeMaterial) {
                check(h.inventory.countItem(Items.TORCH) == 8 && h.inventory.countItem(Items.STICK) == 0
                        && h.inventory.countItem(Items.COAL) == 0 && result.data().get("completed_batches").equals(2),
                        "two exact batches consume two sticks and two coal and actually store eight torches");
            } else {
                check(result.data().get("failure_type").equals(removeMaterial ? "no_material" : "unknown")
                        && result.data().containsKey("crafting_placement_evidence"), "only observed absent materials justify no_material");
                check(Boolean.TRUE.equals(result.data().get("crafting_grid_cleanup_verified")) && menu.getCarried().isEmpty(),
                        "failed placement returns real grid materials and settles the cursor");
                check(h.inventory.countItem(Items.TORCH) == (removeMaterial ? 4 : 0), "partial output remains a measured inventory fact");
                if (!removeMaterial) check(h.inventory.countItem(Items.STICK) == 2 && h.inventory.countItem(Items.COAL) == 2,
                        "an unconfirmed result consumes no recipe inputs after cleanup");
            }
        }
    }

    private static void bookTimeout(TagKey<Item> rods) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.enableCraftingTransactions(); var recipe = recipe(rods); install(h, recipe); h.player.getRecipeBook().add(recipe);
            var task = new CraftCompanionTask(h.player, new CraftTaskRecord("book-timeout", 1000, TORCH, 4, null));
            task.start(h.player); TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 100 && state == TaskState.RUNNING; tick++) {
                MenuVisibility.rendered(h.h.minecraft.screen); state = task.tick(h.player); h.nextTick();
            }
            var result = task.result(state);
            check(state == TaskState.FAILED && result.data().get("failure_type").equals("unknown")
                    && h.mode.recipePlacements == 1 && result.data().containsKey("crafting_placement_evidence"),
                    "a recipe-book timeout preserves evidence and never invents material shortage or a second placement");
        }
    }

    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
