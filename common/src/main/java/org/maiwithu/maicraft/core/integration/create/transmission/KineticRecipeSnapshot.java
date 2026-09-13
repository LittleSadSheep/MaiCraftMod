// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.tools.RecipeProbe;
import org.maiwithu.maicraft.server.machine.NativeApi;
import static org.maiwithu.maicraft.core.integration.create.transmission.KineticMaterialCosts.*;

/** One current synchronized recipe/inventory snapshot for all competing BOMs. Never caches across plans or datapack reloads. */
public final class KineticRecipeSnapshot {
    public static final int MAX_NATIVE_RECIPES = 32768, MAX_REACHABLE_ITEMS = 512, MAX_INGREDIENT_ENTRIES = 8192;
    private static final Set<String> VANILLA = Set.of("ShapedRecipe", "ShapelessRecipe", "StonecutterRecipe",
            "SmeltingRecipe", "BlastingRecipe", "SmokingRecipe", "CampfireCookingRecipe");
    private static final Set<String> CREATE = Set.of("com.simibubi.create.content.kinetics.saw.CuttingRecipe",
            "com.simibubi.create.content.kinetics.mixer.MixingRecipe", "com.simibubi.create.content.kinetics.deployer.ItemApplicationRecipe",
            "com.simibubi.create.content.kinetics.deployer.DeployerApplicationRecipe");
    private static final String PROCESSING = "com.simibubi.create.content.processing.recipe.ProcessingRecipe";
    private KineticRecipeSnapshot() {}

    public static Snapshot capture(LocalPlayer player, Set<String> candidateMaterialIds) {
        if (candidateMaterialIds == null || candidateMaterialIds.size() > 64) throw new IllegalArgumentException("kinetic_material_roots_budget");
        candidateMaterialIds.forEach(KineticMaterialCosts::identifier);
        Map<String, List<KineticMaterialCosts.Recipe>> recipes = new LinkedHashMap<>(); Map<String, Integer> carried = new LinkedHashMap<>();
        Map<String, Double> raw = new LinkedHashMap<>(); Set<String> unknown = new LinkedHashSet<>(), issues = new LinkedHashSet<>();
        JsonObject evidence = new JsonObject(); evidence.addProperty("source", "current_synchronized_client_recipe_manager");
        evidence.addProperty("snapshot_scope", "one_planning_stage_all_candidate_BOMs"); evidence.addProperty("cached_across_plans", false);
        evidence.addProperty("raw_unit_model", "one log, common metal ingot or andesite is one relative resource unit; a comparison model, not currency");
        int examined = 0, emptyResults = 0, ingredientEntries = 0; boolean complete = false;
        try {
            var context = ClientRuntime.requireContext(player); var registry = context.level().registryAccess();
            evidence.addProperty("captured_game_tick", context.level().getGameTime());
            evidence.addProperty("dimension", context.level().dimension().location().toString());
            for (int slot = 0; slot < Math.min(36, player.getInventory().items.size()); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!stack.isEmpty() && stack.getComponentsPatch().isEmpty()) carried.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
            for (String id : List.of("minecraft:andesite", "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:copper_ingot"))
                if (BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id))) raw.put(id, 1.0);
            for (String tag : List.of("minecraft:logs", "c:stripped_logs", "c:stripped_woods", "c:ingots/iron", "c:ingots/gold", "c:ingots/copper", "c:ingots/zinc"))
                BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, ResourceLocation.parse(tag))).ifPresent(values -> {
                    for (var holder : values) if (raw.size() < 4096) raw.put(BuiltInRegistries.ITEM.getKey(holder.value()).toString(), 1.0);
                });
            Map<String, List<RecipeHolder<?>>> index = new LinkedHashMap<>(); Set<String> overfull = new LinkedHashSet<>();
            var iterator = context.connection().getRecipeManager().getRecipes().iterator();
            while (examined < MAX_NATIVE_RECIPES && iterator.hasNext()) {
                var holder = iterator.next(); examined++;
                ItemStack output;
                try { output = RecipeProbe.resultOf(holder.value(), registry); }
                catch (RuntimeException | LinkageError unreadable) { emptyResults++; continue; }
                if (output.isEmpty()) { emptyResults++; continue; }
                String id = BuiltInRegistries.ITEM.getKey(output.getItem()).toString();
                var bucket = index.computeIfAbsent(id, ignored -> new ArrayList<>()); bucket.add(holder);
                bucket.sort(Comparator.comparing(value -> value.id().toString()));
                if (bucket.size() > MAX_RECIPES_PER_ITEM) { bucket.removeLast(); overfull.add(id); }
            }
            complete = !iterator.hasNext(); if (!complete) issues.add("native_recipe_scan_truncated");
            record Pending(String id, int depth) {}
            var pending = new ArrayDeque<Pending>(); candidateMaterialIds.stream().sorted().forEach(id -> pending.add(new Pending(id, 0)));
            Set<String> seen = new LinkedHashSet<>();
            while (!pending.isEmpty() && seen.size() < MAX_REACHABLE_ITEMS && ingredientEntries < MAX_INGREDIENT_ENTRIES) {
                var request = pending.removeFirst(); if (!seen.add(request.id())) continue;
                if (request.depth() >= MAX_DEPTH) { unknown.add(request.id()); issues.add("recipe_closure_depth_limit"); continue; }
                if (overfull.contains(request.id())) { unknown.add(request.id()); issues.add("recipe_alternatives_truncated:" + request.id()); }
                List<KineticMaterialCosts.Recipe> accepted = new ArrayList<>();
                for (var holder : index.getOrDefault(request.id(), List.of())) {
                    try {
                        var recipe = interpret(holder, registry);
                        int entries = recipe.ingredients().stream().mapToInt(value -> value.alternatives().size()).sum();
                        if (ingredientEntries + entries > MAX_INGREDIENT_ENTRIES) { unknown.add(request.id()); issues.add("ingredient_snapshot_budget"); break; }
                        ingredientEntries += entries; accepted.add(recipe);
                        for (var ingredient : recipe.ingredients()) for (String id : ingredient.alternatives()) pending.addLast(new Pending(id, request.depth() + 1));
                    } catch (RuntimeException | LinkageError unsupported) { unknown.add(request.id()); }
                }
                if (!accepted.isEmpty()) recipes.put(request.id(), List.copyOf(accepted));
            }
            if (!pending.isEmpty()) issues.add("reachable_recipe_snapshot_truncated");
            evidence.addProperty("reachable_item_count", seen.size());
        } catch (RuntimeException | LinkageError unavailable) { issues.add("native_recipe_snapshot_unavailable_or_partial"); }
        evidence.addProperty("examined_native_recipes", examined); evidence.addProperty("native_scan_complete", complete);
        evidence.addProperty("without_readable_static_result", emptyResults); evidence.addProperty("ingredient_entries", ingredientEntries);
        var units = new JsonObject(); raw.forEach(units::addProperty); evidence.add("raw_unit_anchors", units);
        evidence.addProperty("plain_carried_stacks_only", true);
        return new Snapshot(recipes, raw, carried, unknown, issues.stream().limit(64).toList(), evidence);
    }

    private static KineticMaterialCosts.Recipe interpret(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        var nativeRecipe = holder.value(); String implementation = nativeRecipe.getClass().getName(); boolean conditional = false;
        String shortName = implementation.substring(implementation.lastIndexOf('.') + 1);
        if (implementation.equals("net.minecraft.world.item.crafting." + shortName) && VANILLA.contains(shortName)) {
            conditional = !Set.of("ShapedRecipe", "ShapelessRecipe", "StonecutterRecipe").contains(shortName);
        } else if (CREATE.contains(implementation)) {
            if (!((List<?>) NativeApi.call(nativeRecipe, PROCESSING, "getFluidIngredients")).isEmpty()
                    || !((List<?>) NativeApi.call(nativeRecipe, PROCESSING, "getFluidResults")).isEmpty()) throw unknown();
            var outputs = (List<?>) NativeApi.call(nativeRecipe, PROCESSING, "getRollableResults");
            if (outputs.size() != 1 || ((Number) NativeApi.call(outputs.getFirst(), "com.simibubi.create.content.processing.recipe.ProcessingOutput", "getChance")).doubleValue() != 1) throw unknown();
            String heat = String.valueOf(NativeApi.call(nativeRecipe, PROCESSING, "getRequiredHeat"));
            if (!heat.equals("NONE")) throw unknown();
            if (implementation.contains("ApplicationRecipe") && NativeApi.truth(NativeApi.call(nativeRecipe,
                    "com.simibubi.create.content.kinetics.deployer.ItemApplicationRecipe", "shouldKeepHeldItem"))) throw unknown();
            conditional = !implementation.endsWith(".ItemApplicationRecipe");
        } else throw unknown();
        ItemStack output = RecipeProbe.resultOf(nativeRecipe, registries);
        if (output.isEmpty() || !output.getComponentsPatch().isEmpty()) throw unknown();
        List<KineticMaterialCosts.Ingredient> inputs = new ArrayList<>();
        if (nativeRecipe.getIngredients().size() > 16) throw unknown();
        for (Ingredient ingredient : nativeRecipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            JsonElement encoded = Ingredient.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), ingredient).result().orElseThrow(KineticRecipeSnapshot::unknown);
            if (!simpleIngredient(encoded)) throw unknown();
            ItemStack[] samples = ingredient.getItems(); Set<String> alternatives = new LinkedHashSet<>();
            if (samples.length == 0 || samples.length > MAX_ALTERNATIVES) throw unknown();
            for (ItemStack sample : samples) {
                if (sample.isEmpty() || !sample.getComponentsPatch().isEmpty() || sample.getItem().hasCraftingRemainingItem()) throw unknown();
                alternatives.add(BuiltInRegistries.ITEM.getKey(sample.getItem()).toString());
            }
            inputs.add(new KineticMaterialCosts.Ingredient(List.copyOf(alternatives), 1));
        }
        String type = BuiltInRegistries.RECIPE_TYPE.getKey(nativeRecipe.getType()).toString();
        String serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(nativeRecipe.getSerializer()).toString();
        return new KineticMaterialCosts.Recipe(holder.id().toString(), BuiltInRegistries.ITEM.getKey(output.getItem()).toString(), output.getCount(), inputs,
                "synchronized_recipe:" + type + ";serializer:" + serializer, conditional);
    }
    private static boolean simpleIngredient(JsonElement json) {
        if (json.isJsonArray()) return json.getAsJsonArray().size() <= MAX_ALTERNATIVES
                && !json.getAsJsonArray().isEmpty() && java.util.stream.StreamSupport.stream(json.getAsJsonArray().spliterator(), false)
                .allMatch(value -> value.isJsonObject() && simpleIngredient(value));
        if (!json.isJsonObject() || json.getAsJsonObject().size() != 1) return false;
        var object = json.getAsJsonObject(); var value = object.has("item") ? object.get("item") : object.get("tag");
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }
    private static IllegalArgumentException unknown() { return new IllegalArgumentException("recipe_semantics_not_interpretable"); }
}
