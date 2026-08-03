package org.maiwithu.maicraft.core.tools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.agent.tool.ToolArgs;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;
import org.maiwithu.maicraft.task.TaskResult;

/** Read-only recipe planning for the receipt-owned crafting task. */
public final class CraftOps {

    private static final int MAX_COUNT = 36 * 64;
    private static final int MAX_RECOVERY_CANDIDATES = 8;
    private static final int MAX_REPORTED_ACCEPTABLE_ITEMS = 64;
    private static final long TIMEOUT_TICKS = 60L * 20L;
    private static final double STATION_REACH = 4.5;

    /** Exactly one of task and immediate is non-null. */
    public record Plan(CraftTaskRecord task, TaskResult immediate) {
        public boolean executable() {
            return task != null;
        }
    }

    private record IndexedIngredient(int recipeSlot, Ingredient ingredient) {}

    private record IngredientFact(
            int recipeSlot,
            String description,
            List<String> acceptableItemIds,
            int required,
            int satisfied) {
        int missing() {
            return required - satisfied;
        }
    }

    private record Allocation(List<IngredientFact> ingredients, int satisfied, int missing) {}

    private record Candidate(
            CraftingRecipe recipe,
            ResourceLocation id,
            int outputCount,
            int ingredientCount,
            int batches,
            Allocation allocation,
            boolean surfaceSupported,
            boolean surfaceReady,
            String surface,
            BlockPos station) {
        boolean materialsReady() {
            return allocation.missing() == 0;
        }

        boolean executableNow() {
            return materialsReady() && surfaceSupported && surfaceReady;
        }

        int produced() {
            return batches * outputCount;
        }
    }

    public Plan plan(String itemId, Integer count, LocalPlayer self, ToolContext toolContext) {
        Item target = ToolArgs.parseItem(itemId);
        int wantedInventoryCount = count == null ? 1 : Math.clamp(count, 1, MAX_COUNT);
        String targetName = BuiltInRegistries.ITEM.getKey(target).toString();
        int currentTargetCount = PlayerInv.buildableCount(self.getInventory(), target);

        Map<String, Object> targetFacts = new LinkedHashMap<>();
        targetFacts.put("target_item_id", targetName);
        targetFacts.put("target_inventory_required", wantedInventoryCount);
        targetFacts.put("target_inventory_current", currentTargetCount);
        targetFacts.put("target_inventory_deficit",
                Math.max(0, wantedInventoryCount - currentTargetCount));

        // This is deliberately before recipe discovery. Recursive recovery must stop as soon as the
        // semantic inventory condition has become true; it must not explore a more elaborate recipe.
        if (currentTargetCount >= wantedInventoryCount) {
            targetFacts.put("goal_satisfied", true);
            targetFacts.put("task_started", false);
            return new Plan(null, TaskResult.ok(
                    "already carrying the requested " + wantedInventoryCount + " " + targetName,
                    targetFacts));
        }

        int deficit = wantedInventoryCount - currentTargetCount;
        var context = ClientRuntime.requireContext(self);
        List<Candidate> candidates = new ArrayList<>();
        for (RecipeHolder<?> holder : context.connection().getRecipeManager().getRecipes()) {
            try {
                if (!(holder.value() instanceof CraftingRecipe recipe)
                        || recipe.isSpecial()
                        || !RecipeProbe.usableIngredients(recipe)) {
                    continue;
                }
                ItemStack result = RecipeProbe.resultOf(recipe, context.level().registryAccess());
                if (result.isEmpty() || result.getItem() != target) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(holder.id().toString());
                if (id == null) {
                    continue;
                }
                int outputCount = Math.max(1, result.getCount());
                int batches = Math.max(1, (deficit + outputCount - 1) / outputCount);
                List<IndexedIngredient> ingredients = indexedIngredients(recipe);
                if (ingredients.isEmpty()) {
                    continue;
                }
                Allocation allocation = allocate(ingredients, self, batches);
                candidates.add(candidateFor(
                        recipe, id, outputCount, batches, allocation, self));
            } catch (RuntimeException brokenRecipe) {
                org.maiwithu.maicraft.core.Constants.LOG.debug(
                        "[maicraft-craft] skipped unusable recipe {}: {}",
                        holder.id(), brokenRecipe.toString());
            }
        }

        candidates.sort(Comparator
                .comparing(Candidate::executableNow).reversed()
                .thenComparingInt(candidate -> candidate.allocation().missing())
                .thenComparing(Comparator.comparing(Candidate::surfaceReady).reversed())
                .thenComparingInt(candidate -> candidate.produced() - deficit)
                .thenComparingInt(candidate -> candidate.ingredientCount() * candidate.batches())
                .thenComparing(candidate -> candidate.id().toString()));

        if (candidates.isEmpty()) {
            targetFacts.put("goal_satisfied", false);
            targetFacts.put("candidate_recipes", List.of());
            return new Plan(null, TaskResult.fail(
                    "no client-known ordinary crafting recipe makes " + targetName
                            + "; inspect another acquisition method",
                    targetFacts));
        }

        Candidate chosen = candidates.get(0);
        List<Map<String, Object>> reported = candidates.stream()
                .limit(MAX_RECOVERY_CANDIDATES)
                .map(candidate -> candidateData(candidate, deficit))
                .toList();
        targetFacts.put("goal_satisfied", false);
        targetFacts.put("candidate_recipes", reported);
        targetFacts.put("candidate_recipe_count", candidates.size());
        targetFacts.put("candidate_recipes_truncated",
                candidates.size() > MAX_RECOVERY_CANDIDATES);
        targetFacts.put("recommended_recipe_id", chosen.id().toString());

        if (!chosen.executableNow()) {
            String reason;
            if (!chosen.materialsReady()) {
                reason = "missing materials for the closest recipe: "
                        + missingSummary(chosen.allocation());
            } else if (!chosen.surfaceSupported()) {
                reason = "the closest recipe needs a crafting surface larger than 3x3";
            } else {
                reason = "materials are ready, but " + chosen.surface();
            }
            return new Plan(null, TaskResult.fail(
                    "cannot yet reach inventory target " + wantedInventoryCount + " "
                            + targetName + ": " + reason,
                    targetFacts));
        }

        targetFacts.put("selected_recipe_id", chosen.id().toString());
        targetFacts.put("craft_additional_count", deficit);
        long timeout = Math.min(30L * 60L * 20L,
                TIMEOUT_TICKS + (long) deficit * 10L);
        return new Plan(new CraftTaskRecord(
                toolContext.toolCallId(),
                toolContext.deadline(timeout),
                chosen.id(),
                deficit,
                chosen.station()), null);
    }

    private static Candidate candidateFor(
            CraftingRecipe recipe,
            ResourceLocation id,
            int outputCount,
            int batches,
            Allocation allocation,
            LocalPlayer player) {
        int ingredientCount = indexedIngredients(recipe).size();
        if (!fits(recipe, 3, 3)) {
            return new Candidate(recipe, id, outputCount, ingredientCount, batches, allocation,
                    false, false, "recipe exceeds a 3x3 crafting surface", null);
        }
        if (currentGridFits(player, recipe)) {
            return new Candidate(recipe, id, outputCount, ingredientCount, batches, allocation,
                    true, true, "the currently open crafting surface is compatible", null);
        }
        if (fits(recipe, 2, 2)) {
            return new Candidate(recipe, id, outputCount, ingredientCount, batches, allocation,
                    true, true, "the player inventory 2x2 grid is compatible", null);
        }
        BlockPos station = nearestLoadedCraftingTable(player);
        return new Candidate(recipe, id, outputCount, ingredientCount, batches, allocation,
                true, station != null,
                station == null
                        ? "no loaded crafting table is within first-person reach"
                        : "a loaded crafting table is within first-person reach",
                station);
    }

    private static Map<String, Object> candidateData(Candidate candidate, int deficit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recipe_id", candidate.id().toString());
        data.put("executable_now", candidate.executableNow());
        data.put("output_per_batch", candidate.outputCount());
        data.put("batches_required", candidate.batches());
        data.put("output_for_required_batches", candidate.produced());
        data.put("additional_output_needed", deficit);
        data.put("materials_ready", candidate.materialsReady());
        data.put("ingredients_satisfied", candidate.allocation().satisfied());
        data.put("ingredients_missing", candidate.allocation().missing());
        data.put("crafting_surface_supported", candidate.surfaceSupported());
        data.put("crafting_surface_ready", candidate.surfaceReady());
        data.put("crafting_surface", candidate.surface());
        if (candidate.station() != null) {
            data.put("station", Map.of(
                    "x", candidate.station().getX(),
                    "y", candidate.station().getY(),
                    "z", candidate.station().getZ()));
        }
        List<Map<String, Object>> ingredients = new ArrayList<>();
        for (IngredientFact ingredient : candidate.allocation().ingredients()) {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("recipe_slot", ingredient.recipeSlot());
            fact.put("description", ingredient.description());
            fact.put("required", ingredient.required());
            fact.put("satisfied", ingredient.satisfied());
            fact.put("missing", ingredient.missing());
            fact.put("acceptable_item_ids", ingredient.acceptableItemIds().stream()
                    .limit(MAX_REPORTED_ACCEPTABLE_ITEMS).toList());
            fact.put("acceptable_item_id_count", ingredient.acceptableItemIds().size());
            fact.put("acceptable_item_ids_truncated",
                    ingredient.acceptableItemIds().size() > MAX_REPORTED_ACCEPTABLE_ITEMS);
            ingredients.add(fact);
        }
        data.put("ingredients", ingredients);
        return data;
    }

    /**
     * Allocate live inventory stack capacities to recipe ingredients with a small max-flow graph.
     * This avoids greedy false negatives when a broad tag ingredient overlaps a narrow ingredient.
     */
    private static Allocation allocate(
            List<IndexedIngredient> ingredients, LocalPlayer player, int batches) {
        int usableSlots = Math.min(PlayerInv.BUILDABLE_SLOTS,
                player.getInventory().getContainerSize());
        int source = 0;
        int ingredientBase = 1;
        int slotBase = ingredientBase + ingredients.size();
        int sink = slotBase + usableSlots;
        Flow flow = new Flow(sink + 1);
        Flow.Edge[] needEdges = new Flow.Edge[ingredients.size()];

        for (int ingredientIndex = 0; ingredientIndex < ingredients.size(); ingredientIndex++) {
            Ingredient ingredient = ingredients.get(ingredientIndex).ingredient();
            int ingredientNode = ingredientBase + ingredientIndex;
            needEdges[ingredientIndex] = flow.add(source, ingredientNode, batches);
            for (int slot = 0; slot < usableSlots; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!stack.isEmpty() && ingredient.test(stack)) {
                    flow.add(ingredientNode, slotBase + slot, batches);
                }
            }
        }
        for (int slot = 0; slot < usableSlots; slot++) {
            flow.add(slotBase + slot, sink, player.getInventory().getItem(slot).getCount());
        }
        int satisfiedTotal = flow.max(source, sink);

        List<IngredientFact> facts = new ArrayList<>();
        for (int index = 0; index < ingredients.size(); index++) {
