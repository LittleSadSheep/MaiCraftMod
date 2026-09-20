// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.Set;
import java.util.LinkedHashSet;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticSourceKnowledge;
import org.maiwithu.maicraft.core.tools.ToolParse;
import java.util.ArrayList;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import java.util.Objects;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.tools.RecipeProbe;

/** 只读比较加工配方、燃料和准备路线；选定后由烹饪执行器操作同一台炉子并确认实际结果。 */
final class CookingRecipePlanner {
    static final long UNAVAILABLE_COST = 1_000_000_000_000L;
    private static final int MAX_ACQUISITION_DEPTH = 4;
    private static final long DIRECT_SOURCE_UNIT_COST = 1_000L;
    private static final long OBSERVED_BLOCK_UNIT_COST = 500L;
    private static final long CRAFT_ROUTE_COST = 250L;
    private static final long CRAFTING_SURFACE_COST = 500L;
    private static final long TOOL_PREREQUISITE_COST = 1_500L;
    private static final long STORAGE_FALLBACK_COST = 50_000L;
    private final LocalPlayer player;
    private final SemanticCookTaskRecord request;
    private Map<Block, Long> nearbyBlockDistances = Map.of();
    private Map<Item, List<CraftRoute>> craftRoutes;
    private final Set<Item> rejectedFuelItems = new LinkedHashSet<>();

    CookingRecipePlanner(LocalPlayer player, SemanticCookTaskRecord request) {
        this.player = player;
        this.request = request;
    }

    void observeNearbyBlocks(Map<Block, Long> blocks) { nearbyBlockDistances = Map.copyOf(blocks); }

    long acquisitionCost(Item item, int count) { return acquisitionCost(item, count, Set.of(), 0); }

    List<CraftRoute> routesFor(Item item) {
        if (craftRoutes == null) craftRoutes = indexCraftRoutes();
        return craftRoutes.getOrDefault(item, List.of());
    }

    record IngredientGroup(List<Item> alternatives, int uses) {}
    record CraftRoute(
            ResourceLocation recipeId,
            int outputCount,
            List<IngredientGroup> ingredients,
            boolean requiresWorkstation) {}
    // 为备料估价整理普通配方的产量与材料组；这里只读配方，实际缺料仍交给取物任务处理。
    private Map<Item, List<CraftRoute>> indexCraftRoutes() {
        Map<Item, List<CraftRoute>> indexed = new HashMap<>();
        var manager = ClientRuntime.requireContext(player).connection().getRecipeManager();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            try {
                if (!(holder.value() instanceof CraftingRecipe recipe)
                        || recipe.isSpecial()
                        || !RecipeProbe.usableIngredients(recipe)) {
                    continue;
                }
                ItemStack output = RecipeProbe.resultOf(
                        recipe, player.level().registryAccess());
                if (output.isEmpty()) continue;
                List<IngredientGroup> groups = ingredientGroups(recipe);
                if (groups.isEmpty()) continue;
                int ingredientUses = recipe.getIngredients().stream()
                        .mapToInt(ingredient -> ingredient == null || ingredient.isEmpty() ? 0 : 1)
                        .sum();
                boolean workstation = recipe instanceof ShapedRecipe shaped
                        ? shaped.getWidth() > 2 || shaped.getHeight() > 2
                        : ingredientUses > 4;
                indexed.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>())
                        .add(new CraftRoute(
                                holder.id(), Math.max(1, output.getCount()),
                                groups, workstation));
            } catch (RuntimeException brokenRecipe) {
                Constants.LOG.debug(
                        "[maicraft-cook] skipped unusable acquisition-cost recipe {}: {}",
                        holder.id(), brokenRecipe.toString());
            }
        }
        indexed.replaceAll((item, routes) -> routes.stream()
                .sorted(Comparator.comparing(route -> route.recipeId().toString()))
                .toList());
        return Map.copyOf(indexed);
    }

    // 候选物品列表完全相同的材料格合成一组，并数这种材料用了几格，避免重复遍历相同要求。
    private static List<IngredientGroup> ingredientGroups(CraftingRecipe recipe) {
        Map<List<Item>, Integer> uses = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            List<Item> alternatives = Arrays.stream(ingredient.getItems())
                    .filter(stack -> stack != null && !stack.isEmpty())
                    .map(ItemStack::getItem)
                    .distinct()
                    .sorted(Comparator.comparing(item ->
                            BuiltInRegistries.ITEM.getKey(item).toString()))
                    .toList();
            if (alternatives.isEmpty()) return List.of();
            uses.merge(alternatives, 1, Integer::sum);
        }
        return uses.entrySet().stream()
                .map(entry -> new IngredientGroup(entry.getKey(), entry.getValue()))
                .toList();
    }


    // 先扣除当前背包已有量，再估直接来源或递归合成的成本；递归最多四层，并阻止沿同一路径绕回同一物品。
    // 这里的估计没有共用一份消耗后的库存，不同需求可能重复计算同一批存货。
    private long acquisitionCost(
            Item item, int required, Set<Item> lineage, int depth) {
        if (required <= 0) return 0L;
        if (item == null || item == Items.AIR) return UNAVAILABLE_COST;
        int carried = PlayerInv.buildableCount(player.getInventory(), item);
        int missing = Math.max(0, required - carried);
        if (missing == 0) return 0L;

        long best = directSourceCost(item, missing);
        if (request.allowedSources.contains(SemanticAcquireTaskRecord.Source.STORAGE)
                && Ae2ResourceSupply.available()) {
            best = Math.min(best, addCost(
                    STORAGE_FALLBACK_COST, (long) missing * DIRECT_SOURCE_UNIT_COST));
        }
        if (depth >= MAX_ACQUISITION_DEPTH
                || !request.allowedSources.contains(SemanticAcquireTaskRecord.Source.CRAFT)
                || lineage.contains(item)) {
            return best;
        }

        List<CraftRoute> routes = routesFor(item);
        if (routes.isEmpty()) return best;
        Set<Item> nextLineage = new LinkedHashSet<>(lineage);
        nextLineage.add(item);
        for (CraftRoute route : routes) {
            int batches = Math.ceilDiv(missing, route.outputCount());
            long routeCost = addCost(
                    (long) batches * CRAFT_ROUTE_COST,
                    route.requiresWorkstation() ? CRAFTING_SURFACE_COST : 0L);
            for (IngredientGroup group : route.ingredients()) {
                int groupRequired = Math.max(1, batches * group.uses());
                long alternativeCost = UNAVAILABLE_COST;
                for (Item alternative : group.alternatives()) {
                    alternativeCost = Math.min(alternativeCost,
                            acquisitionCost(
                                    alternative, groupRequired, nextLineage, depth + 1));
                }
                routeCost = addCost(routeCost, alternativeCost);
                if (routeCost >= UNAVAILABLE_COST) break;
            }
            best = Math.min(best, routeCost);
        }
        return best;
    }

    // 当前只估采矿、允许伤害时的狩猎和交易；储存来源在外层另算。
    // 允许列表里的 NEARBY 没有在这份估计里实现，可能在委托真实获取任务前就被判不可行（A58）。
    private long directSourceCost(Item item, int missing) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId == null) return UNAVAILABLE_COST;
        SemanticSourceKnowledge.SourcePlan plan =
                SemanticSourceKnowledge.inferPlan(List.of(itemId));
        long best = UNAVAILABLE_COST;

        if (request.allowedSources.contains(SemanticAcquireTaskRecord.Source.MINE)
                && dimensionAllowed(plan, SemanticAcquireTaskRecord.Source.MINE)) {
            Set<Block> sourceBlocks = new LinkedHashSet<>(
                    ToolParse.parseBlocks(plan.hint().blockRefs()));
            boolean originalSource = !sourceBlocks.isEmpty();
            Long observedDistance = item instanceof BlockItem blockItem
                    && !blockItem.getBlock().defaultBlockState().requiresCorrectToolForDrops()
                    ? nearbyBlockDistances.get(blockItem.getBlock()) : null;
            if (item instanceof BlockItem blockItem && observedDistance != null) {
                sourceBlocks.add(blockItem.getBlock());
            }
            if (!sourceBlocks.isEmpty()) {
                long unit = originalSource
                        ? DIRECT_SOURCE_UNIT_COST : OBSERVED_BLOCK_UNIT_COST;
                long tool = SemanticSourceKnowledge.missingTool(player, sourceBlocks) == null
                        ? 0L : TOOL_PREREQUISITE_COST;
                long distance = observedDistance == null ? 0L : observedDistance;
                best = Math.min(best, addCost((long) missing * unit, tool, distance));
            }
        }
        if (request.allowHarm
                && request.allowedSources.contains(SemanticAcquireTaskRecord.Source.HUNT)
                && dimensionAllowed(plan, SemanticAcquireTaskRecord.Source.HUNT)
                && !plan.hint().entityTypeIds().isEmpty()) {
            best = Math.min(best, 10_000L + (long) missing * DIRECT_SOURCE_UNIT_COST);
        }
        if (request.allowedSources.contains(SemanticAcquireTaskRecord.Source.TRADE)
                && !plan.hint().tradeProfessionIds().isEmpty()) {
            best = Math.min(best, 20_000L + (long) missing * DIRECT_SOURCE_UNIT_COST);
        }
        return best;
    }

    private boolean dimensionAllowed(
            SemanticSourceKnowledge.SourcePlan plan,
            SemanticAcquireTaskRecord.Source source) {
        List<ResourceLocation> dimensions = plan.allowedDimensions(source);
        return dimensions.isEmpty()
                || dimensions.contains(player.level().dimension().location());
    }

    static long addCost(long... values) {
        long total = 0L;
        for (long value : values) {
            if (value >= UNAVAILABLE_COST || value < 0L) return UNAVAILABLE_COST;
            if (total > UNAVAILABLE_COST - value) return UNAVAILABLE_COST;
            total += value;
        }
        return total;
    }


    record FuelChoice(
            Item item, int burnTicks, int count, long waste, long acquisitionCost) {}

    void rejectFuel(Item item) { rejectedFuelItems.add(item); }

    // 明确列出的燃料优先按许可范围选；没列时只考虑煤、木炭、木材、竹子等这里列出的普通燃料。
    // 主要比较获取成本和烧剩的时间；PRESERVE_RARE 改用固定燃料优先表，不是真正读取物品稀有度。
    FuelChoice chooseFuel(CookingRecipe cooking, int raw) {
        List<Item> choices = new ArrayList<>();
        if (!request.allowedFuelIds.isEmpty()) {
            request.allowedFuelIds.forEach(id -> choices.add(BuiltInRegistries.ITEM.get(id)));
        } else {
            for (Item item : AbstractFurnaceBlockEntity.getFuel().keySet()) {
                if (safeDefaultFuel(item)) choices.add(item);
            }
        }
        List<FuelChoice> fuels = choices.stream().distinct()
                .filter(item -> !rejectedFuelItems.contains(item))
                .map(item -> fuelChoice(cooking, item, raw))
                .filter(Objects::nonNull)
                .toList();
        Comparator<FuelChoice> economical = Comparator
                .comparingLong(FuelChoice::acquisitionCost)
                .thenComparingLong(FuelChoice::waste)
                .thenComparingInt(FuelChoice::count)
                .thenComparingInt(choice -> fuelPriority(choice.item()))
                .thenComparing(choice -> BuiltInRegistries.ITEM.getKey(
                        choice.item()).toString());
        if (request.preference == SemanticCookTaskRecord.Preference.PRESERVE_RARE) {
            economical = Comparator
                    .comparingLong(FuelChoice::acquisitionCost)
                    .thenComparingInt(choice -> fuelPriority(choice.item()))
                    .thenComparingLong(FuelChoice::waste)
                    .thenComparingInt(FuelChoice::count)
                    .thenComparing(choice -> BuiltInRegistries.ITEM.getKey(
                            choice.item()).toString());
        }
        return fuels.stream().min(economical).orElse(null);
    }

    private static boolean safeDefaultFuel(Item item) {
        return item == Items.COAL || item == Items.CHARCOAL || item == Items.STICK
                || item == Items.BAMBOO || item == Blocks.DRIED_KELP_BLOCK.asItem()
                || item.builtInRegistryHolder().is(ItemTags.PLANKS)
                || item.builtInRegistryHolder().is(ItemTags.LOGS);
    }

    private int fuelPriority(Item item) {
        if (item == Items.COAL) return 0;
        if (item == Items.CHARCOAL) return 1;
        if (item.builtInRegistryHolder().is(ItemTags.PLANKS)) return 2;
        if (item.builtInRegistryHolder().is(ItemTags.LOGS)) return 3;
        if (item == Blocks.DRIED_KELP_BLOCK.asItem()) return 4;
        return item == Items.STICK ? 5 : 6;
    }

    // 按“这一批所需烧制时间 ÷ 单份燃料时间”向上取整。
    // 时间取自选定原生设备，不能把普通熔炉的一份煤时长套在高炉或烟熏炉上。
    FuelChoice fuelChoice(CookingRecipe cooking, Item item, int raw) {
        CookingBatch batch = CookingBatch.plan(cooking, item, raw, player.level().registryAccess());
        if (batch == null) return null;
        int burn = batch.burnTicks();
        long neededTicks = (long) batch.inputCount() * cooking.recipe().getCookingTime();
        int needed = batch.fuelCount();
        long cost = acquisitionCost(item, needed);
        long waste = (long) needed * burn - neededTicks;
        return new FuelChoice(item, burn, needed, waste, cost);
    }

}
