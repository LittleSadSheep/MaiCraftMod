// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;

/** 炉子数量与收尾测试共用的已加载小世界；背包变化由场景显式给出，不连接服务端。 */
final class CookingTestWorld implements AutoCloseable {
    final InteractionWorldTestHarness game = new InteractionWorldTestHarness();

    CookingTestWorld() throws Exception {
        var recipes = new RecipeManager(RegistryAccess.EMPTY);
        recipes.replaceRecipes(List.of(new RecipeHolder<>(id("test_smelting"), new SmeltingRecipe("",
                CookingBookCategory.MISC, Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), 0, 200))));
        field(ClientPacketListener.class, "recipeManager").set(game.player.connection, recipes);
        field(ClientLevel.class, "connection").set(game.level, game.player.connection);
        field(Level.class, "registryAccess").set(game.level, RegistryAccess.EMPTY);
        var inventoryMenu = new InventoryMenu(game.inventory, false, game.player);
        field(Player.class, "inventoryMenu").set(game.player, inventoryMenu);
        game.player.containerMenu = inventoryMenu;
        game.set(new BlockPos(1, 1, 3), Blocks.FURNACE.defaultBlockState());
    }

    SemanticCookTaskRecord request(int count) {
        return new SemanticCookTaskRecord("cook-quantity", 100000, id("iron_ingot"), count,
                SemanticCookTaskRecord.Preference.AUTO, List.of(id("coal")), List.of(Source.INVENTORY), false, List.of());
    }

    void recipes(List<RecipeHolder<?>> recipes) {
        game.player.connection.getRecipeManager().replaceRecipes(recipes);
    }

    void inventory(int output, int raw, int fuel) {
        game.inventory.clearContent();
        int slot = put(0, Items.IRON_INGOT, output);
        slot = put(slot, Items.RAW_IRON, raw);
        put(slot, Items.COAL, fuel);
    }

    private int put(int slot, Item item, int count) {
        while (count > 0) {
            int stack = Math.min(64, count);
            game.inventory.setItem(slot++, new ItemStack(item, stack));
            count -= stack;
        }
        return slot;
    }

    static Object read(Object target, String name) throws Exception { return field(target.getClass(), name).get(target); }
    static void set(Object target, String name, Object value) throws Exception { field(target.getClass(), name).set(target, value); }
    static void phase(Object target, String phase) throws Exception {
        var field = field(target.getClass(), "phase");
        for (Object value : field.getType().getEnumConstants())
            if (((Enum<?>) value).name().equals(phase)) { field.set(target, value); return; }
        throw new IllegalArgumentException(phase);
    }
    static Object invoke(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
    static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
    @Override public void close() throws Exception { game.close(); }
}
