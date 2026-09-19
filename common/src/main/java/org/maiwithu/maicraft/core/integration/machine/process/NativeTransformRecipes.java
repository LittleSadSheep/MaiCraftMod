// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.FluidState;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 按原生配方类型读取世界转化；保留 Ingredient 条件和完整产物组件，不按某一材料链重新编造配方。 */
public final class NativeTransformRecipes {
    private static final String TRANSFORM = "appeng.recipes.transform.TransformRecipe";
    private static final String CIRCUMSTANCE = "appeng.recipes.transform.TransformCircumstance";
    private NativeTransformRecipes() {}

    public static boolean available() { return NativeApi.present(TRANSFORM) && NativeApi.present(CIRCUMSTANCE); }

    /** 同步配方包含流体和爆炸两种环境；展示可列出全族，执行准入仍由 find 明确限制为流体。 */
    public static List<Recipe> recipes(LocalPlayer player) {
        if (!available()) return List.of();
        requireConnection(player);
        var recipes = new ArrayList<Recipe>();
        for (RecipeHolder<?> holder : player.connection.getRecipeManager().getRecipes())
            if (NativeApi.is(holder.value(), TRANSFORM)) recipes.add(read(holder, player.registryAccess()));
        recipes.sort(Comparator.comparing(recipe -> recipe.id().toString()));
        return List.copyOf(recipes);
    }

    public static Recipe find(LocalPlayer player, ResourceLocation id) {
        if (!available()) throw new IllegalStateException("world_transform_adapter_unavailable");
        requireConnection(player);
        RecipeHolder<?> holder = player.connection.getRecipeManager().byKey(Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("world_transform_recipe_not_synchronized: " + id));
        return read(holder, player.registryAccess()).requireFluid();
    }

    /** 客户端同步配方和可选服务端读取共用同一 DTO；只读 getResultItem，绝不为预览调用可能生成频率的 assemble。 */
    public static Recipe read(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        if (holder == null || !NativeApi.is(holder.value(), TRANSFORM))
            throw new IllegalArgumentException("unsupported_world_recipe_type");
        Object circumstance = NativeApi.call(holder.value(), TRANSFORM, "getCircumstance");
        // 流体实现是 AE2 私有内部类，通过公有基类方法做动态分派，避免反射私有类造成访问失败。
        JsonObject environment = ((JsonObject) NativeApi.call(circumstance, CIRCUMSTANCE, "toJson")).deepCopy();
        return new Recipe(holder.id(), holder.value().getIngredients(), holder.value().getResultItem(registries), environment,
                state -> NativeApi.truth(NativeApi.call(circumstance, CIRCUMSTANCE, "isFluid", state)), registries);
    }

    private static void requireConnection(LocalPlayer player) {
        if (player == null || player.connection == null) throw new IllegalStateException("world_transform_requires_synchronized_recipes");
    }

    public static final class Recipe implements WorldProcessRecipe {
        private final ResourceLocation id;
        private final List<Ingredient> inputs;
        private final ItemStack result;
        private final JsonObject description, environment;
        private final Predicate<FluidState> fluidMatch;

        Recipe(ResourceLocation id, List<Ingredient> inputs, ItemStack result, JsonObject environment,
               Predicate<FluidState> fluidMatch, HolderLookup.Provider registries) {
            this.id = Objects.requireNonNull(id); this.inputs = List.copyOf(inputs); this.result = result.copy();
            this.environment = environment.deepCopy(); this.fluidMatch = Objects.requireNonNull(fluidMatch);
            if (this.inputs.isEmpty() || this.inputs.size() > 64 || this.result.isEmpty())
                throw new IllegalArgumentException("world_transform_recipe_empty_or_exceeds_input_budget: " + id);
            description = describeDefinition(registries);
        }

        public ResourceLocation id() { return id; }
        public List<Ingredient> inputs() { return inputs; }
        public ItemStack result() { return result.copy(); }
        public JsonObject describe() { return description.deepCopy(); }
        // AE2 只让匹配第一项原料的掉落物启动流体转化，由通用流程据此安排最后一项投放。
        public int triggerInputIndex() { return 0; }
        // AE2 的 TransformLogic 按触发物底部坐标各扩一格查询实体；最后投料须与已入池原料的实际碰撞体相交。
        @Override public java.util.OptionalDouble inputSearchRadius() { return java.util.OptionalDouble.of(1); }
        public boolean isFluid() { return environment.has("type") && "fluid".equals(environment.get("type").getAsString()); }
        public boolean supports(FluidState state) { return isFluid() && state != null && !state.isEmpty() && fluidMatch.test(state); }

        /** 爆炸转化仍可作为知识展示，不能被水池使用任务悄悄改造成另一种危险的执行方式。 */
        Recipe requireFluid() {
            if (!isFluid()) throw new IllegalArgumentException("world_transform_requires_fluid_circumstance: " + id);
            return this;
        }

        private JsonObject describeDefinition(HolderLookup.Provider registries) {
            var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            JsonObject out = new JsonObject(); out.addProperty("recipe_id", id.toString()); out.addProperty("recipe_type", "ae2:transform");
            out.add("environment", environment.deepCopy()); out.addProperty("trigger_input_index", 0);
            // 场地观察同时说明原料须在触发物附近，不能让调用者把同一大片水域误当成一个无限收料槽。
            out.addProperty("input_search_radius", inputSearchRadius().orElseThrow());
            JsonArray requirements = new JsonArray();
            for (int index = 0; index < inputs.size(); index++) {
                Ingredient ingredient = inputs.get(index); JsonObject requirement = new JsonObject();
                // 每个原生原料条件各消耗一件，重复条件必须保留，不能按物品名去重后少投或少计成本。
                requirement.addProperty("index", index); requirement.addProperty("amount", 1);
                requirement.add("predicate", Ingredient.CODEC_NONEMPTY.encodeStart(ops, ingredient).getOrThrow());
                ItemStack[] samples = ingredient.getItems(); JsonArray alternatives = new JsonArray();
                for (int i = 0; i < Math.min(samples.length, 128); i++) {
                    ItemStack sample = samples[i]; if (sample.isEmpty() || !ingredient.test(sample)) continue;
                    JsonObject identity = ResourceIdentity.item(sample, registries), alternative = new JsonObject();
                    alternative.addProperty("resource_id", ResourceIdentity.key(identity)); alternative.add("identity", identity);
                    alternatives.add(alternative);
                }
                requirement.add("alternatives", alternatives);
                // getItems 是展示候选而非自定义条件的权威枚举；实际选料永远还要调用保留的 Ingredient.test。
                requirement.addProperty("alternatives_truncated", samples.length > 128);
                requirement.addProperty("native_predicate_required", true); requirements.add(requirement);
            }
            out.add("inputs", requirements);
            JsonObject encoded = ItemStack.CODEC.encodeStart(ops, result).getOrThrow().getAsJsonObject();
            encoded.addProperty("count", result.getCount()); out.add("result", encoded);
            out.add("result_identity", ResourceIdentity.item(result, registries));
            out.addProperty("result_is_preview", true); out.addProperty("native_recipe_verified", false);
            if (out.toString().length() > 128 * 1024) throw new IllegalArgumentException("world_transform_recipe_description_too_large: " + id);
            return out;
        }
    }
}
