package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
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
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.tools.CraftOps;

/** 木棍已命中通用木杆标签仍可能被配方簿忽略，必须把材料匹配与配方解锁分别验证。 */
public final class CraftingRecipeBookBoundaryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registry = BuiltInRegistries.ITEM;
        var previous = registry.getTags().collect(Collectors.toMap(
                pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
        var woodenRods = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:rods/wooden"));
        try (var h = new InteractionWorldTestHarness()) {
            // 使用服务器实际同步的标签成员，不将木杆硬编码成只接受原版木棍的特殊配方。
            registry.bindTags(Map.of(woodenRods, List.of(Items.STICK.builtInRegistryHolder())));
            var rods = Ingredient.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseString("{\"tag\":\"c:rods/wooden\"}")).getOrThrow();
            var stick = new ItemStack(Items.STICK, 2);
            check(rods.test(stick) && rods.getStackingIds().contains(StackedContents.getStackingIndex(stick)),
                    "the parsed common tag accepts sticks in both native matching paths");
            check(!rods.test(new ItemStack(Items.BLAZE_ROD)), "a different rod must not match the wooden tag");
            var id = ResourceLocation.parse("minecraft:torch");
            var recipe = new RecipeHolder<CraftingRecipe>(id, new ShapedRecipe("", CraftingBookCategory.MISC,
                    ShapedRecipePattern.of(Map.of('A', Ingredient.of(Items.COAL, Items.CHARCOAL), 'B', rods),
                            List.of("A", "B")), new ItemStack(Items.TORCH, 4)));
            var menu = new InventoryMenu(h.inventory, true, h.player);
            ActorControlTestHarness.field(h.player.getClass(), "inventoryMenu").set(h.player, menu);
            h.player.containerMenu = menu;
            h.inventory.setItem(0, new ItemStack(Items.COAL, 2)); h.inventory.setItem(1, stick);
            var manager = new RecipeManager(RegistryAccess.EMPTY); manager.replaceRecipes(List.of(recipe));
            ActorControlTestHarness.field(ClientPacketListener.class, "recipeManager").set(h.h.connection, manager);
            var plan = new CraftOps().plan(id.toString(), 8, h.player, new ToolContext("tag-torch", 0));
            check(plan.executable() && plan.task().plannedBatches == 2 && plan.task().outputPerBatch == 4,
                    "the planner finds both complete torch batches through the common tag");

            // 原生配方簿先检查解锁，未解锁时连材料盘点都不会进入；这是零消耗超时的独立原因。
            var serverPlayer = h.h.allocate(ServerPlayer.class);
            ActorControlTestHarness.field(ServerPlayer.class, "recipeBook").set(serverPlayer, new ServerRecipeBook());
            new ServerPlaceRecipe<>(menu).recipeClicked(serverPlayer, recipe, false);
            check(menu.getSlot(1).getItem().isEmpty() && h.inventory.countItem(Items.STICK) == 2,
                    "an unlearned recipe silently leaves the grid and materials untouched");

            // 给木棍命名不会改变原生标签匹配，但配方簿的简单材料盘点会将它排除。
            stick.set(DataComponents.CUSTOM_NAME, Component.literal("施工木棍"));
            var contents = new StackedContents(); h.inventory.fillStackedContents(contents);
            check(rods.test(stick) && !contents.canCraft(recipe.value(), null),
                    "native ingredient matching and recipe-book eligibility differ for named materials");
        } finally { registry.bindTags(previous); }
        System.out.println("CraftingRecipeBookBoundaryTest: passed");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
