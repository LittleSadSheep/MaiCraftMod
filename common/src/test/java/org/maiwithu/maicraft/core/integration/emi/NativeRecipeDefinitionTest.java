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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;

/** 原生定义只经序列化读取；深度、节点、UTF-8超预算或序列化失败都明确未知，不执行配方补猜字段。 */
public final class NativeRecipeDefinitionTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        synchronizedShapedRecipe(registries);
        repeatedWideIngredientIsEncodedOnce(registries);
        assemblyOutcomeUnitsUseNativeApi(registries);
        var invalid = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.BRICK),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STICK))) {
            @Override public RecipeSerializer<?> getSerializer() { throw new IllegalStateException("fixture unreadable serializer"); }
        };
        JsonObject unknown = NativeRecipeDefinition.read(invalid, registries);
        check(!unknown.has("native_outcome_distribution"), "ordinary recipes cannot inherit Create weighted-choice semantics");
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
            check(observed.getAsJsonObject("definition").getAsJsonObject("key").size() == 2,
                    "网络中重复出现的相同原料共用符号，完整原生条件只编码一次");
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
    private static void repeatedWideIngredientIsEncodedOnce(RegistryAccess registries) {
        // 大标签经过真实网络编解码后仍保留全部可接受物品，不能因八个相同格子膨胀而丢掉箱子配方。
        var stacks = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).limit(180).map(ItemStack::new).toArray(ItemStack[]::new);
        var original = new ShapedRecipe("wide-grid", CraftingBookCategory.MISC,
                ShapedRecipePattern.of(Map.of('A', Ingredient.of(stacks)), "AAA", "A A", "AAA"), new ItemStack(Items.CHEST));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            ShapedRecipe.Serializer.STREAM_CODEC.encode(buffer, original);
            var observed = NativeRecipeDefinition.read(ShapedRecipe.Serializer.STREAM_CODEC.decode(buffer), registries);
            check(observed.get("definition_status").getAsString().equals("available"), "宽原料配方仍能在原生预算内完整读取");
            var definition = observed.getAsJsonObject("definition");
            var restored = (ShapedRecipe) Recipe.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), definition).getOrThrow();
            check(definition.getAsJsonObject("key").size() == 1 && restored.getIngredients().getFirst().getItems().length == stacks.length,
                    "只共享等价符号，不裁掉完整原料候选");
        } finally { buffer.release(); }
    }
    private static void assemblyOutcomeUnitsUseNativeApi(RegistryAccess registries) {
        // 原生权重可能大于一；只在主产物概率与原生 getter 一致时解释单位，不能把展示 1.0 当作保底产出。
        ItemStack named = new ItemStack(Items.BRICK, 2);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("原生产物"));
        var outcomes = List.of(new CreateAssemblyOutcomeFacts.Outcome(named, 120),
                new CreateAssemblyOutcomeFacts.Outcome(new ItemStack(Items.GOLD_NUGGET), 3),
                new CreateAssemblyOutcomeFacts.Outcome(new ItemStack(Items.STICK), 27));
        JsonObject facts = CreateAssemblyOutcomeFacts.describe(2, 4, outcomes, .8f, registries);
        check(facts.get("total_steps").getAsInt() == 8 && facts.get("result_selection").getAsString().equals("one_weighted_result_after_all_loops"),
                "loop and step counts describe one final weighted choice");
        var rows = facts.getAsJsonArray("outcomes");
        check(rows.get(0).getAsJsonObject().get("probability").getAsDouble() == .8
                && rows.get(1).getAsJsonObject().get("probability").getAsDouble() == .02,
                "native weights expose primary and byproduct probabilities without rolling any result");
        named.setCount(8);
        check(rows.get(0).getAsJsonObject().get("count").getAsInt() == 2
                && rows.get(0).getAsJsonObject().getAsJsonObject("identity").getAsJsonObject("components").has("minecraft:custom_name")
                && !facts.get("production_verified").getAsBoolean(), "knowledge preserves result components without claiming an actual product");
        rejects(() -> CreateAssemblyOutcomeFacts.describe(2, 4, outcomes, 1, registries), "API mismatch remains unknown");
        rejects(() -> CreateAssemblyOutcomeFacts.describe(2, 4, List.of(new CreateAssemblyOutcomeFacts.Outcome(ItemStack.EMPTY, 0)), 0, registries), "zero total weight is invalid");
        rejects(() -> CreateAssemblyOutcomeFacts.describe(2, 4, List.of(new CreateAssemblyOutcomeFacts.Outcome(ItemStack.EMPTY, Float.NaN)), 1, registries), "nonfinite weight cannot become a probability");
        rejects(() -> CreateAssemblyOutcomeFacts.describe(0, 4, outcomes, .8f, registries), "empty loop count cannot invent a completion boundary");
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (IllegalArgumentException expected) { /* 原生资料未通过核对时保留未知，不输出假概率。 */ }
    }
}
