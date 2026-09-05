package org.maiwithu.maicraft.core.tools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.maiwithu.maicraft.agent.tool.ToolArgs;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.craft.CraftPlanCost;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;
import org.maiwithu.maicraft.core.task.craft.CraftingWorkstationCoordinator;
import org.maiwithu.maicraft.task.TaskResult;

/** Read-only recipe planning for the receipt-owned crafting task. */
public final class CraftOps {

    private static final int MAX_COUNT = 36 * 64;
    private static final int MAX_RECOVERY_CANDIDATES = 8;
    private static final int MAX_REPORTED_ACCEPTABLE_ITEMS = 64;
    private static final long TIMEOUT_TICKS = 60L * 20L;

    /** Exactly one of task and immediate is non-null. */
    public record Plan(
            CraftTaskRecord task,
            TaskResult immediate,
            CraftPlanCost cost,
            List<Map<String, Object>> recoveryCandidates) {
        public Plan {
            recoveryCandidates = recoveryCandidates == null
                    ? List.of() : List.copyOf(recoveryCandidates);
        }

        public Plan(CraftTaskRecord task, TaskResult immediate, CraftPlanCost cost) {
            this(task, immediate, cost, List.of());
        }

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
            CraftPlanCost cost,
            String surface,
            BlockPos station,
            List<ResourceLocation> surfacePrerequisiteItems) {
        boolean materialsReady() {
            return allocation.missing() == 0;
        }

        boolean surfaceSupported() {
            return cost.surface() != CraftPlanCost.Surface.UNSUPPORTED;
        }

        boolean surfaceReady() {
            return cost.surface() == CraftPlanCost.Surface.READY;
        }

        boolean surfacePreparable() {
            return cost.surface() == CraftPlanCost.Surface.PREPARABLE
                    || cost.surface() == CraftPlanCost.Surface.SEARCHING;
        }

        boolean executableNow() {
            return materialsReady() && surfaceSupported() && surfaceReady();
        }

        boolean dispatchable() {
            return cost.dispatchable();
        }

        int produced() {
            return batches * outputCount;
        }
    }

    public Plan plan(String itemId, Integer count, LocalPlayer self, ToolContext toolContext) {
        return plan(itemId, count, self, toolContext, null, Set.of());
    }

    /** Cheap recipe-only guard so 2x2 recursion never scans the loaded world for a table. */
    public static boolean requiresWorkstationForAny(
            Collection<ResourceLocation> outputIds, LocalPlayer player) {
        if (outputIds == null || outputIds.isEmpty()) return false;
        Set<Item> targets = outputIds.stream()
                .filter(BuiltInRegistries.ITEM::containsKey)
                .map(BuiltInRegistries.ITEM::get)
                .collect(java.util.stream.Collectors.toSet());
        if (targets.isEmpty()) return false;
        var context = ClientRuntime.requireContext(player);
        for (RecipeHolder<?> holder : context.connection().getRecipeManager().getRecipes()) {
            try {
                if (!(holder.value() instanceof CraftingRecipe recipe)
                        || recipe.isSpecial()
                        || !RecipeProbe.usableIngredients(recipe)
                        || !fits(recipe, 3, 3)
                        || fits(recipe, 2, 2)) {
                    continue;
                }
                ItemStack result = RecipeProbe.resultOf(recipe, context.level().registryAccess());
                if (!result.isEmpty() && targets.contains(result.getItem())) return true;
            } catch (RuntimeException brokenRecipe) {
                org.maiwithu.maicraft.core.Constants.LOG.debug(
                        "[maicraft-craft] skipped unusable workstation probe recipe {}: {}",
                        holder.id(), brokenRecipe.toString());
            }
        }
        return false;
    }

    /** Reuse one world snapshot while comparing several semantic output alternatives. */
    public Plan plan(
            String itemId,
            Integer count,
            LocalPlayer self,
            ToolContext toolContext,
            CraftingWorkstationCoordinator.PlanningSnapshot workstation) {
        return plan(itemId, count, self, toolContext, workstation, Set.of());
    }

    /** Re-plan after concrete recipe failures without blindly selecting the same recipe again. */
    public Plan plan(
            String itemId,
            Integer count,
            LocalPlayer self,
            ToolContext toolContext,
            CraftingWorkstationCoordinator.PlanningSnapshot workstation,
            Set<String> excludedRecipeIds) {
        Item target = ToolArgs.parseItem(itemId);
        Set<String> excluded = excludedRecipeIds == null ? Set.of() : excludedRecipeIds;
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
                    targetFacts), null);
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
                if (id == null || excluded.contains(id.toString())) {
                    continue;
                }
                int outputCount = Math.max(1, result.getCount());
                int batches = Math.max(1, (deficit + outputCount - 1) / outputCount);
                List<IndexedIngredient> ingredients = indexedIngredients(recipe);
                if (ingredients.isEmpty()) {
                    continue;
                }
                if (workstation == null && fits(recipe, 3, 3)
                        && !currentGridFits(self, recipe) && !fits(recipe, 2, 2)) {
                    workstation = CraftingWorkstationCoordinator.inspect(self);
                }
                Allocation allocation = allocate(ingredients, self, batches);
                candidates.add(candidateFor(
                        recipe, id, outputCount, batches, allocation, self, workstation, deficit));
            } catch (RuntimeException brokenRecipe) {
                org.maiwithu.maicraft.core.Constants.LOG.debug(
                        "[maicraft-craft] skipped unusable recipe {}: {}",
                        holder.id(), brokenRecipe.toString());
            }
        }

        candidates.sort(Comparator.comparing(Candidate::cost, CraftPlanCost.ORDER));

        if (candidates.isEmpty()) {
            targetFacts.put("goal_satisfied", false);
            targetFacts.put("candidate_recipes", List.of());
            return new Plan(null, TaskResult.fail(
                    "no client-known ordinary crafting recipe makes " + targetName
                            + "; inspect another acquisition method",
                    targetFacts), null);
        }

        Candidate chosen = candidates.get(0);
        List<Map<String, Object>> allCandidateData = candidates.stream()
                .map(candidate -> candidateData(candidate, deficit, Integer.MAX_VALUE))
                .toList();
        List<Map<String, Object>> reported = candidates.stream()
                .limit(MAX_RECOVERY_CANDIDATES)
                .map(candidate -> candidateData(
                        candidate, deficit, MAX_REPORTED_ACCEPTABLE_ITEMS))
                .toList();
        targetFacts.put("goal_satisfied", false);
        targetFacts.put("candidate_recipes", reported);
        targetFacts.put("candidate_recipe_count", candidates.size());
        targetFacts.put("candidate_recipes_truncated",
                candidates.size() > MAX_RECOVERY_CANDIDATES);
        targetFacts.put("recommended_recipe_id", chosen.id().toString());

        if (!chosen.dispatchable()) {
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
                    targetFacts), chosen.cost(), allCandidateData);
        }

        targetFacts.put("selected_recipe_id", chosen.id().toString());
        targetFacts.put("craft_additional_count", deficit);
        long timeout = Math.min(30L * 60L * 20L,
                TIMEOUT_TICKS + (long) deficit * 10L
                        + (chosen.surfacePreparable() ? 3L * 60L * 20L : 0L));
        return new Plan(new CraftTaskRecord(
                toolContext.toolCallId(),
                toolContext.deadline(timeout),
                chosen.id(),
                deficit,
                chosen.batches(),
                chosen.outputCount(),
                chosen.station()), null, chosen.cost());
    }

    private static Candidate candidateFor(
            CraftingRecipe recipe,
            ResourceLocation id,
            int outputCount,
            int batches,
            Allocation allocation,
            LocalPlayer player,
            CraftingWorkstationCoordinator.PlanningSnapshot workstation,
            int deficit) {
        int ingredientCount = indexedIngredients(recipe).size();
        int outputWaste = Math.max(0, batches * outputCount - deficit);
        int ingredientUses = ingredientCount * batches;
        if (!fits(recipe, 3, 3)) {
            return new Candidate(recipe, id, outputCount, ingredientCount, batches, allocation,
                    new CraftPlanCost(allocation.missing(), CraftPlanCost.Surface.UNSUPPORTED,
                            outputWaste, ingredientUses, id.toString()),
                    "recipe exceeds a 3x3 crafting surface", null, List.of());
        }
        if (currentGridFits(player, recipe)) {
            return new Candidate(recipe, id, outputCount, ingredientCount, batches, allocation,
                    new CraftPlanCost(allocation.missing(), CraftPlanCost.Surface.READY,
                            outputWaste, ingredientUses, id.toString()),
                    "the currently open crafting surface is compatible", null, List.of());
        }
        if (fits(recipe, 2, 2)) {
            return new Candidate(recipe, id, outputCount, ingredientCount, batches, allocation,
                    new CraftPlanCost(allocation.missing(), CraftPlanCost.Surface.READY,
                            outputWaste, ingredientUses, id.toString()),
                    "the player inventory 2x2 grid is compatible", null, List.of());
        }
        if (workstation == null) {
            workstation = CraftingWorkstationCoordinator.inspect(player);
        }
        return new Candidate(recipe, id, outputCount, ingredientCount, batches, allocation,
                new CraftPlanCost(allocation.missing(), workstation.surface(),
                        outputWaste, ingredientUses, id.toString()),
                workstation.detail(), workstation.station(), workstation.prerequisiteItemIds());
    }

    private static Map<String, Object> candidateData(
            Candidate candidate, int deficit, int acceptableItemLimit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recipe_id", candidate.id().toString());
        data.put("executable_now", candidate.executableNow());
        data.put("dispatchable", candidate.dispatchable());
        data.put("output_per_batch", candidate.outputCount());
        data.put("batches_required", candidate.batches());
        data.put("output_for_required_batches", candidate.produced());
        data.put("additional_output_needed", deficit);
        data.put("output_waste", candidate.cost().outputWaste());
        data.put("ingredient_uses", candidate.cost().ingredientUses());
        data.put("materials_ready", candidate.materialsReady());
        data.put("ingredients_satisfied", candidate.allocation().satisfied());
        data.put("ingredients_missing", candidate.allocation().missing());
        data.put("crafting_surface_supported", candidate.surfaceSupported());
        data.put("crafting_surface_ready", candidate.surfaceReady());
        data.put("crafting_surface_preparable", candidate.surfacePreparable());
        data.put("crafting_surface_state", candidate.cost().surface().name().toLowerCase());
        data.put("crafting_surface", candidate.surface());
        if (candidate.station() != null) {
            data.put("station", Map.of(
                    "x", candidate.station().getX(),
                    "y", candidate.station().getY(),
                    "z", candidate.station().getZ()));
        }
        if (!candidate.surfacePrerequisiteItems().isEmpty()) {
            data.put("crafting_surface_prerequisite_item_id",
                    candidate.surfacePrerequisiteItems().getFirst().toString());
            data.put("crafting_surface_prerequisite_item_ids",
                    candidate.surfacePrerequisiteItems().stream()
                            .map(ResourceLocation::toString).toList());
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
                    .limit(acceptableItemLimit).toList());
            fact.put("acceptable_item_id_count", ingredient.acceptableItemIds().size());
            fact.put("acceptable_item_ids_truncated",
                    ingredient.acceptableItemIds().size() > acceptableItemLimit);
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
            IndexedIngredient indexed = ingredients.get(index);
            int satisfied = batches - needEdges[index].capacity;
            facts.add(new IngredientFact(
                    indexed.recipeSlot(),
                    QueryExtraOps.describeIngredient(indexed.ingredient()),
                    acceptableItemIds(indexed.ingredient()),
                    batches,
                    satisfied));
        }
        int requiredTotal = ingredients.size() * batches;
        return new Allocation(List.copyOf(facts), satisfiedTotal,
                requiredTotal - satisfiedTotal);
    }

    private static List<String> acceptableItemIds(Ingredient ingredient) {
        return java.util.Arrays.stream(ingredient.getItems())
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .distinct()
                .sorted()
                .toList();
    }

    private static String missingSummary(Allocation allocation) {
        return allocation.ingredients().stream()
                .filter(ingredient -> ingredient.missing() > 0)
                .map(ingredient -> ingredient.missing() + "x " + ingredient.description())
                .reduce((left, right) -> left + ", " + right)
                .orElse("unknown ingredient");
    }

    private static List<IndexedIngredient> indexedIngredients(CraftingRecipe recipe) {
        List<IndexedIngredient> result = new ArrayList<>();
        for (int index = 0; index < recipe.getIngredients().size(); index++) {
            Ingredient ingredient = recipe.getIngredients().get(index);
            if (ingredient != null && !ingredient.isEmpty()) {
                result.add(new IndexedIngredient(index, ingredient));
            }
        }
        return result;
    }

    private static boolean currentGridFits(LocalPlayer player, CraftingRecipe recipe) {
        int width = -1;
        int height = -1;
        boolean result = false;
        for (Slot slot : player.containerMenu.slots) {
            if (slot instanceof ResultSlot) {
                result = true;
            } else if (slot.container instanceof CraftingContainer crafting) {
                width = Math.max(width, crafting.getWidth());
                height = Math.max(height, crafting.getHeight());
            }
        }
        return result && width > 0 && height > 0 && fits(recipe, width, height);
    }

    private static boolean fits(CraftingRecipe recipe, int width, int height) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() <= width && shaped.getHeight() <= height;
        }
        return indexedIngredients(recipe).size() <= width * height;
    }

    /** Small integral max-flow used only for at most nine ingredient nodes and 36 inventory slots. */
    private static final class Flow {
        private final List<List<Edge>> graph;
        private int[] level;
        private int[] cursor;

        private Flow(int nodeCount) {
            graph = new ArrayList<>(nodeCount);
            for (int node = 0; node < nodeCount; node++) {
                graph.add(new ArrayList<>());
            }
        }

        private Edge add(int from, int to, int capacity) {
            Edge forward = new Edge(to, graph.get(to).size(), Math.max(0, capacity));
            Edge reverse = new Edge(from, graph.get(from).size(), 0);
            graph.get(from).add(forward);
            graph.get(to).add(reverse);
            return forward;
        }

        private int max(int source, int sink) {
            int total = 0;
            while (levels(source, sink)) {
                cursor = new int[graph.size()];
                int pushed;
                while ((pushed = push(source, sink, Integer.MAX_VALUE)) > 0) {
                    total += pushed;
                }
            }
            return total;
        }

        private boolean levels(int source, int sink) {
            level = new int[graph.size()];
            java.util.Arrays.fill(level, -1);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            level[source] = 0;
            queue.add(source);
            while (!queue.isEmpty()) {
                int node = queue.removeFirst();
                for (Edge edge : graph.get(node)) {
                    if (edge.capacity > 0 && level[edge.to] < 0) {
                        level[edge.to] = level[node] + 1;
                        queue.addLast(edge.to);
                    }
                }
            }
            return level[sink] >= 0;
        }

        private int push(int node, int sink, int amount) {
            if (node == sink) {
                return amount;
            }
            List<Edge> edges = graph.get(node);
            while (cursor[node] < edges.size()) {
                Edge edge = edges.get(cursor[node]);
                if (edge.capacity > 0 && level[edge.to] == level[node] + 1) {
                    int pushed = push(edge.to, sink, Math.min(amount, edge.capacity));
                    if (pushed > 0) {
                        edge.capacity -= pushed;
                        graph.get(edge.to).get(edge.reverse).capacity += pushed;
                        return pushed;
                    }
                }
                cursor[node]++;
            }
            return 0;
        }

        private static final class Edge {
            private final int to;
            private final int reverse;
            private int capacity;

            private Edge(int to, int reverse, int capacity) {
                this.to = to;
                this.reverse = reverse;
                this.capacity = capacity;
            }
        }
    }
}
