// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/** 原生定义只经序列化读取；深度、节点、UTF-8超预算或序列化失败都明确未知，不执行配方补猜字段。 */
public final class NativeRecipeDefinitionTest {
    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var invalid = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.BRICK),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STICK))) {
            @Override public RecipeSerializer<?> getSerializer() { throw new IllegalStateException("fixture unreadable serializer"); }
        };
        JsonObject unknown = NativeRecipeDefinition.read(invalid, registries);
        check(unknown.get("definition_status").getAsString().equals("unknown") && !unknown.has("definition"), "无法编码时不能退回展示概率冒充原生定义");
        var huge = new ShapelessRecipe("界".repeat(20_000), CraftingBookCategory.MISC, new ItemStack(Items.BRICK),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STICK)));
        unknown = NativeRecipeDefinition.read(huge, registries);
        check(unknown.get("definition_status").getAsString().equals("unknown")
                && unknown.get("definition_issue").getAsString().contains("budget") && !unknown.has("definition"), "按UTF-8预算整份拒绝，不返回半份原生规则");
        JsonObject deep = new JsonObject(), child = deep;
        for (int i = 0; i < 18; i++) { JsonObject next = new JsonObject(); child.add("child", next); child = next; }
        check(!NativeRecipeDefinition.withinBudget(deep), "深层嵌套须在序列化字符串前拒绝");
        JsonObject broad = new JsonObject(); JsonArray values = new JsonArray();
        for (int i = 0; i < NativeRecipeDefinition.MAX_NODES; i++) values.add(i);
        broad.add("values", values); check(!NativeRecipeDefinition.withinBudget(broad), "节点总量有界，不把整个后继配方树收入正文");
        JsonObject small = new JsonObject(); small.addProperty("type", "example:processing"); small.addProperty("chance", .25);
        check(NativeRecipeDefinition.withinBudget(small), "原生定义字段原样保留，不按某个模组或配方ID改写概率");
        System.out.println("NativeRecipeDefinitionTest: unknown encoding and native definition budgets passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
