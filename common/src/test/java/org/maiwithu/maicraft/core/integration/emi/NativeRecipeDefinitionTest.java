// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** 原生定义只经序列化读取；深度、节点、UTF-8超预算或序列化失败都明确未知，不执行配方补猜字段。 */
public final class NativeRecipeDefinitionTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        synchronizedShapedRecipe(registries);
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
    private static void synchronizedShapedRecipe(RegistryAccess registries) {
        // 完整重放服务端编码、客户端解码：证明缺符号表是原生同步行为，回退读取仍保持实际格子、数量和镜像匹配。
        var original = new ShapedRecipe("wire-grid", CraftingBookCategory.MISC,
                ShapedRecipePattern.of(Map.of('A', Ingredient.of(Items.STICK), 'B', Ingredient.of(Items.IRON_INGOT)), "AB", " A"),
                new ItemStack(Items.BRICK, 3), false);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            ShapedRecipe.Serializer.STREAM_CODEC.encode(buffer, original);
            var synced = ShapedRecipe.Serializer.STREAM_CODEC.decode(buffer);
            var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            check(Recipe.CODEC.encodeStart(ops, synced).error().isPresent(), "network shape cannot use the original symbol-table encoder");
            var observed = NativeRecipeDefinition.read(synced, registries);
            check("available".equals(observed.get("definition_status").getAsString())
                    && "synchronized_vanilla_shaped_recipe".equals(observed.get("provenance").getAsString()),
                    "synchronized vanilla shape provides an explicit native definition");
            var restored = (ShapedRecipe) Recipe.CODEC.parse(ops, observed.get("definition")).getOrThrow();
            var normal = CraftingInput.of(2, 2, List.of(new ItemStack(Items.STICK), new ItemStack(Items.IRON_INGOT),
                    ItemStack.EMPTY, new ItemStack(Items.STICK)));
            var mirrored = CraftingInput.of(2, 2, List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.STICK),
                    new ItemStack(Items.STICK), ItemStack.EMPTY));
            check(restored.matches(normal, null) && restored.matches(mirrored, null)
                    && restored.getResultItem(registries).getCount() == 3 && !restored.showNotification(),
                    "native matching and synchronized output facts survive symbol recovery");
            var customPattern = new ShapedRecipePattern(synced.getWidth(), synced.getHeight(), synced.getIngredients(), Optional.empty());
            var custom = new ShapedRecipe("custom", CraftingBookCategory.MISC, customPattern, new ItemStack(Items.BRICK)) {};
            check("unknown".equals(NativeRecipeDefinition.read(custom, registries).get("definition_status").getAsString()),
                    "a mod subclass cannot lose its unknown conditions through vanilla fallback");
        } finally { buffer.release(); }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
