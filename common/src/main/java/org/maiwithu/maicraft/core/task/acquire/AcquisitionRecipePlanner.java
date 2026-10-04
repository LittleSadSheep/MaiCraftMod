// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySources;
import org.maiwithu.maicraft.core.task.craft.CraftRecoveryCandidate;
import org.maiwithu.maicraft.core.task.craft.CraftIngredientReservations;
import org.maiwithu.maicraft.core.tools.RecipeProbe;
import org.maiwithu.maicraft.core.tools.ToolParse;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.mine.NearbyMaterialSources;
import net.minecraft.world.level.block.Block;

/** 只推演缺料配方，不开菜单、不消耗材料；角色实际取材和合成仍由取物任务调度。 */
final class AcquisitionRecipePlanner {
    private static final int STRUCTURAL_RECIPE_DEPTH = 6;
    static final int UNREACHABLE_STRUCTURE_COST = 1_000_000;
    private final LocalPlayer player;
    private final boolean allowHarm;
    private final int storageSearchRadius;
    private final List<String> protectedLabels;
    private final Function<LocalPlayer, Optional<StockEvidence.Snapshot>> networkStock;
    private final AcquisitionProcessRecipes processes;
    private AcquisitionNeed stockHintNeed;
    private long stockHintTick = Long.MIN_VALUE;
    private boolean stockHintWireless;
    private Map<ResourceLocation, Long> recipeObservedStock = Map.of(), recipeCarriedStock = Map.of();
    private record MaterialPlanKey(String recipeId, Set<ResourceLocation> unavailable) {}
    private final Map<MaterialPlanKey, RecipeMaterialPlan.Result> materialPlans = new HashMap<>();

    private Map<ResourceLocation, List<CraftingRecipe>> recipeIndex;
    private final Map<ResourceLocation, List<ObservedRecipeStockCost.Recipe>> stockRecipes = new HashMap<>();
    private NearbyMaterialSources nearbySources;
    private long nearbySourcesCompletedAt;
    private ResourceLocation nearbyDimension;
    private int nearbyRadius;
    private Set<ResourceLocation> preferredMaterials = Set.of();

    void preferMaterials(List<ResourceLocation> materials) {
        // 父取物任务的一份软偏好供所有递归需求复用，重新设置时同步废弃旧材料树排序。
        preferredMaterials = Set.copyOf(materials);
        materialPlans.clear();
    }

    /** 缺料时先扫描本次允许的附近范围；纯背包合成保持原权限，不因为偏好或观察自动开放采集。 */
    boolean observeNearbySources(AcquisitionNeed parent, int radius) {
        if (!parent.allowedSources.contains(SemanticAcquireTaskRecord.Source.MINE)) return true;
        long tick = player.level().getGameTime();
        ResourceLocation dimension = player.level().dimension().location();
        if (nearbySources == null || !dimension.equals(nearbyDimension) || nearbyRadius != radius || nearbySources.complete()
                && (nearbySources.center().distSqr(player.blockPosition()) > 16
                    || tick - nearbySourcesCompletedAt >= 100 || nearbySources.changed())) {
            nearbySources = new NearbyMaterialSources(player.level(), player.level()::isLoaded,
                    NavigationSafetyContext::protectsMutation, player.blockPosition(), radius,
                    SemanticSourceKnowledge.nearbyMiningTargets(dimension));
            nearbyDimension = dimension; nearbyRadius = radius;
        }
        if (nearbySources.complete()) return true;
        if (!nearbySources.advance()) return false;
        // 一轮现场观察完整结束后重新比较材料树，不能沿用观察前选定的模组木种。
        nearbySourcesCompletedAt = tick;
        materialPlans.clear();
        return true;
    }

    Map<String, Object> nearbySourceEvidence() {
        return nearbySources == null ? Map.of("checked", false) : nearbySources.describe();
    }

    AcquisitionRecipePlanner(LocalPlayer player, boolean allowHarm, int storageSearchRadius, List<String> protectedLabels) {
        this(player, allowHarm, storageSearchRadius, protectedLabels, StockEvidence::latestNetwork);
    }

    /** 库存读取与配方推演分开；测试可提供已确认网络快照，实际运行使用同一份终端观察缓存。 */
    AcquisitionRecipePlanner(LocalPlayer player, boolean allowHarm, int storageSearchRadius, List<String> protectedLabels,
                             Function<LocalPlayer, Optional<StockEvidence.Snapshot>> networkStock) {
        this.player = player;
        this.allowHarm = allowHarm;
        this.storageSearchRadius = storageSearchRadius;
        this.protectedLabels = List.copyOf(protectedLabels);
        this.networkStock = networkStock;
        this.processes = new AcquisitionProcessRecipes(player);
    }

    private List<ObservedRecipeStockCost.Recipe> stockRecipes(ResourceLocation output) {
        // 仓库估价只展开普通九格内配方；未知配方不会凭空变成可用材料，也不会阻断其他候选。
        return stockRecipes.computeIfAbsent(output, ignored -> {
            List<ObservedRecipeStockCost.Recipe> result = new ArrayList<>();
            // 完整候选先进入材料账，再由带偏好的搜索预算决定展开顺序；不能先截掉后面的现货或指定材料路线。
            for (CraftingRecipe recipe : recipes().getOrDefault(output, List.of())) {
                try {
                    if (recipe.isSpecial() || recipe instanceof ShapedRecipe shaped
                            && (shaped.getWidth() > 3 || shaped.getHeight() > 3)) continue;
                    ItemStack stack = RecipeProbe.resultOf(recipe, player.level().registryAccess());
                    if (stack.isEmpty()) continue;
                    List<ObservedRecipeStockCost.Need> ingredients = new ArrayList<>();
                    for (Ingredient ingredient : recipe.getIngredients()) {
                        if (ingredient == null || ingredient.isEmpty()) continue;
                        var ids = Arrays.stream(ingredient.getItems())
                                .filter(value -> value != null && !value.isEmpty())
                                .map(value -> BuiltInRegistries.ITEM.getKey(value.getItem())).distinct().toList();
                        ingredients.add(new ObservedRecipeStockCost.Need(ids, 1));
                    }
                    if (!ingredients.isEmpty() && ingredients.size() <= 9)
                        result.add(new ObservedRecipeStockCost.Recipe(stack.getCount(), ingredients));
                } catch (RuntimeException unavailable) {
                    // 这只是排序提示；读取不了的配方不能用来承诺库存已经足够。
                }
            }
            return List.copyOf(result);
        });
    }

    private Map<ResourceLocation, List<CraftingRecipe>> recipes() {
        // 一次任务共用按成品整理的配方索引；递归找原料时复用，避免每一层重新扫描整张配方表。
        if (recipeIndex != null) return recipeIndex;
        Map<ResourceLocation, List<CraftingRecipe>> indexed = new LinkedHashMap<>();
        for (var holder : ClientRuntime.requireContext(player).connection().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
            if (!RecipeProbe.usableIngredients(recipe)) continue;
            ItemStack output = RecipeProbe.resultOf(recipe, player.level().registryAccess());
            if (output.isEmpty()) continue;
            ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(output.getItem());
            indexed.computeIfAbsent(outputId, ignored -> new ArrayList<>()).add(recipe);
        }
        Map<ResourceLocation, List<CraftingRecipe>> frozen = new LinkedHashMap<>();
        indexed.forEach((id, recipes) -> frozen.put(id, List.copyOf(recipes)));
        recipeIndex = Map.copyOf(frozen);
        return recipeIndex;
    }

    private record StructureKey(
            ResourceLocation itemId,
            int remainingDepth,
            Set<ResourceLocation> blocked) {
        private StructureKey {
            blocked = Set.copyOf(blocked);
        }
    }

    /** 比较缺料路线要再过几层合成；深度只影响排序，不证明世界里有这些材料。 */
    int structureCost(CraftRecoveryCandidate candidate, AcquisitionNeed parent) {
        // 只估计还需经过几层普通合成，用来给候选排序；没有说世界里一定有那些原材料。
        Map<StructureKey, Integer> memo = new HashMap<>();
        int total = 0;
        for (var ingredient : candidate.ingredients()) {
            int missing = ingredient.missing();
            if (missing <= 0) continue;
            int best = UNREACHABLE_STRUCTURE_COST;
            for (ResourceLocation id : ingredient.itemIds()) {
                if (parent.lineageItems.contains(id)) continue;
                best = Math.min(best, recursiveCraftDepth(
                        id, parent.lineageItems, new LinkedHashSet<>(), memo,
                        STRUCTURAL_RECIPE_DEPTH));
            }
            if (best >= UNREACHABLE_STRUCTURE_COST) return UNREACHABLE_STRUCTURE_COST;
            total = Math.min(UNREACHABLE_STRUCTURE_COST,
                    total + missing * Math.max(1, best + 1));
        }
        return total;
    }

    private int recursiveCraftDepth(
            ResourceLocation itemId,
            Set<ResourceLocation> forbidden,
            Set<ResourceLocation> visiting,
            Map<StructureKey, Integer> memo,
            int remainingDepth) {
        // 已经带着或没有更上游配方的物品作为边界；遇到循环或超过前瞻深度就认为这条估计不可靠。
        if (forbidden.contains(itemId) || visiting.contains(itemId)) {
            return UNREACHABLE_STRUCTURE_COST;
        }
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (CraftIngredientReservations.available(itemId,PlayerInv.buildableCount(player.getInventory(), item)) > 0) return 0;
        List<CraftingRecipe> recipes = recipes().getOrDefault(
                itemId, List.of());
        if (recipes.isEmpty()) return 0;
        if (remainingDepth <= 0) return UNREACHABLE_STRUCTURE_COST;
        Set<ResourceLocation> blocked = new LinkedHashSet<>(forbidden);
        blocked.addAll(visiting);
        StructureKey key = new StructureKey(itemId, remainingDepth, blocked);
        Integer remembered = memo.get(key);
        if (remembered != null) return remembered;

        visiting.add(itemId);
        int bestRecipe = UNREACHABLE_STRUCTURE_COST;
        for (CraftingRecipe recipe : recipes) {
            int criticalDepth = 0;
            boolean valid = true;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) continue;
                int ingredientDepth = UNREACHABLE_STRUCTURE_COST;
                for (ItemStack accepted : ingredient.getItems()) {
                    if (accepted == null || accepted.isEmpty()) continue;
                    ResourceLocation acceptedId = BuiltInRegistries.ITEM.getKey(
                            accepted.getItem());
                    ingredientDepth = Math.min(ingredientDepth, recursiveCraftDepth(
                            acceptedId, forbidden, visiting, memo, remainingDepth - 1));
                }
                if (ingredientDepth >= UNREACHABLE_STRUCTURE_COST) {
                    valid = false;
                    break;
                }
                criticalDepth = Math.max(criticalDepth, ingredientDepth);
            }
            if (valid) bestRecipe = Math.min(bestRecipe, 1 + criticalDepth);
        }
        visiting.remove(itemId);
        // 没有配方才是真正的叶子；所有配方都绕回祖先时仍是循环，不能把它算成免费原料。
        memo.put(key, bestRecipe);
        return bestRecipe;
    }

    int ingredientStructureCost(
            List<ResourceLocation> itemIds, AcquisitionNeed parent) {
        Map<StructureKey, Integer> memo = new HashMap<>();
        int best = UNREACHABLE_STRUCTURE_COST;
        for (ResourceLocation itemId : itemIds) {
            if (parent.lineageItems.contains(itemId)) continue;
            best = Math.min(best, recursiveCraftDepth(
                    itemId, parent.lineageItems, new LinkedHashSet<>(), memo,
                    STRUCTURAL_RECIPE_DEPTH));
        }
        return best;
    }

    record IngredientNeed(
            List<ResourceLocation> itemIds, int missing) {}

    private record RankedIngredient(
            IngredientNeed ingredient, boolean decisionRequired, int structureCost) {}

    /** 同一替代材料组先合并数量，再挑下一项前置需求；任何必需组绕回祖先都会否决整条路线。 */
    IngredientNeed chooseIngredient(CraftRecoveryCandidate candidate, AcquisitionNeed parent) {
        // 同样的可替代原料组先合并数量；优先验证最难或需要授权的那组，避免先做一堆配件最后才发现关键原料拿不到。
        Map<List<ResourceLocation>, Integer> missingByItems = new LinkedHashMap<>();
        for (var ingredient : candidate.ingredients()) {
            int missing = ingredient.missing();
            if (missing <= 0) continue;
            List<ResourceLocation> ids = ingredient.itemIds().stream()
                    .filter(id -> !parent.lineageItems.contains(id))
                    .toList();
            // 只要一组必需材料绕回祖先，整个配方就不能继续；不能先去做染料，最后才发现染床还得先有床。
            if (ids.isEmpty()) return null;
            missingByItems.merge(ids, missing, Integer::sum);
        }
        List<IngredientNeed> choices = new ArrayList<>();
        for (Map.Entry<List<ResourceLocation>, Integer> entry : missingByItems.entrySet()) {
            choices.add(new IngredientNeed(entry.getKey(), entry.getValue()));
        }
        List<RankedIngredient> ranked = new ArrayList<>();
        for (IngredientNeed choice : choices) {
            ranked.add(new RankedIngredient(
                    choice,
                    ingredientRequiresDecision(choice.itemIds(), parent),
                    ingredientStructureCost(choice.itemIds(), parent)));
        }
        return ranked.stream()
                // 先验证权限受限、转换层数更深或数量更多的原料，避免先做一堆容易的配件。
                .sorted(Comparator
                        .comparing(RankedIngredient::decisionRequired).reversed()
                        .thenComparing(Comparator.comparingInt(
                                RankedIngredient::structureCost).reversed())
                        .thenComparing(Comparator.comparingInt(
                                (RankedIngredient rankedIngredient) ->
                                        rankedIngredient.ingredient().missing())
                                .reversed())
                        .thenComparing(rankedIngredient -> String.join(",",
                                rankedIngredient.ingredient().itemIds().stream().map(ResourceLocation::toString).toList())))
                .map(RankedIngredient::ingredient)
                .findFirst().orElse(null);
    }

    private boolean ingredientRequiresDecision(
            List<ResourceLocation> itemIds, AcquisitionNeed parent) {
        if (allowHarm
                || !parent.allowedSources.contains(SemanticAcquireTaskRecord.Source.HUNT)) {
            return false;
        }
        SemanticAcquireTaskRecord.SourceHint hint = SemanticSourceKnowledge.infer(itemIds);
        return !hint.entityTypeIds().isEmpty() && hint.blockRefs().isEmpty();
    }

    record Frontier(
            IngredientNeed ingredient,
            Set<String> recipeIds,
            List<ResourceLocation> outputItemIds, boolean unknownSource) {
        Frontier(IngredientNeed ingredient, Set<String> recipeIds, List<ResourceLocation> outputItemIds) {
            this(ingredient, recipeIds, outputItemIds, false);
        }
    }

    record UnitConversion(int outputCount, List<ResourceLocation> inputs) {}

    /** 一件原料换一批同类成品时可按实际拿到的木种分批做；多原料配方不能把各自的半套原料混算。 */
    UnitConversion unitConversion(ResourceLocation recipeId, List<ResourceLocation> outputs) {
        if(outputs.size()<2)return null;
        var holder=ClientRuntime.requireContext(player).connection().getRecipeManager().byKey(recipeId).orElse(null);
        if(holder==null || !(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial()
                || !recipe.canCraftInDimensions(2,2))return null;
        var ingredients=recipe.getIngredients().stream().filter(value->value!=null && !value.isEmpty()).toList();
        if(ingredients.size()!=1)return null;
        ItemStack result=RecipeProbe.resultOf(recipe,player.level().registryAccess());
        if(result.isEmpty() || !outputs.contains(BuiltInRegistries.ITEM.getKey(result.getItem())))return null;
        var items=Arrays.stream(ingredients.getFirst().getItems()).filter(value->!value.isEmpty()).toList();
        if(items.isEmpty() || items.stream().anyMatch(value->!value.getComponentsPatch().isEmpty()))return null;
        var ids=items.stream().map(value->BuiltInRegistries.ITEM.getKey(value.getItem())).distinct().toList();
        if(ids.stream().anyMatch(outputs::contains))return null;
        return new UnitConversion(result.getCount(),ids);
    }

    private UnitConversion directUnitConversion(CraftRecoveryCandidate candidate, RecipeMaterialPlan.Result plan, AcquisitionNeed parent) {
        if(candidate.ingredients().size()!=1 || plan.supplies().size()!=1 || !plan.crafts().isEmpty())return null;
        var input=candidate.ingredients().getFirst();
        if(input.required()!=input.missing() || input.missing()!=plan.supplies().getFirst().count())return null;
        var conversion=unitConversion(ResourceLocation.parse(candidate.recipeId()),parent.itemIds);
        return conversion!=null && conversion.inputs().containsAll(plan.supplies().getFirst().alternatives()) ? conversion : null;
    }

    /** 每层都使用同一本现货账；普通补料也必须考虑背包里能继续合成的原料。 */
    private void refreshStock(AcquisitionNeed parent) {
        long tick = player.level().getGameTime();
        if (stockHintNeed != parent || stockHintTick != tick || stockHintWireless != parent.wirelessInventory) {
            stockHintNeed = parent;
            stockHintTick = tick;
            stockHintWireless = parent.wirelessInventory;
            materialPlans.clear();
            Map<ResourceLocation, Long> observed = new LinkedHashMap<>();
            // 普通容器继续要求原许可与材料线索；随身无线终端只把自己的网络库存加到账本。
            if (parent.allowedSources.contains(SemanticAcquireTaskRecord.Source.STORAGE))
                observed.putAll(ContainerSupplySources.observedCounts(player, player.blockPosition(), storageSearchRadius, protectedLabels));
            if (parent.wirelessInventory && parent.allowedSources.contains(SemanticAcquireTaskRecord.Source.WIRELESS)
                    || parent.allowedSources.contains(SemanticAcquireTaskRecord.Source.STORAGE))
                networkStock.apply(player).filter(stock -> stock.source() == StockEvidence.Source.AE2)
                        .ifPresent(stock -> stock.stored().forEach((id, amount) -> observed.merge(id, amount,
                                (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b)));
            recipeObservedStock = Map.copyOf(observed);
            Map<ResourceLocation, Long> carried = new LinkedHashMap<>();
            for (int slot = 0; slot < Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size()); slot++) {
                ItemStack stack = player.getInventory().items.get(slot);
                if (!stack.isEmpty()) {
                    carried.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), (long) stack.getCount(), Long::sum);
                }
            }
            // 递归材料树也只使用余量；例如尾翼需要六张帆时，会在已预留的主翼帆面之外另做六张再转换。
            carried.replaceAll(CraftIngredientReservations::available);
            recipeCarriedStock = Map.copyOf(carried);
        }
    }

    RecipeMaterialPlan.Result materialPlan(CraftRecoveryCandidate candidate, AcquisitionNeed parent) {
        refreshStock(parent);
        // 同刻失败的原料分支也改变规划条件；缓存不能把已排除的木种再次选回来。
        var unavailable = Set.copyOf(parent.rejectedRecipeInputs.getOrDefault(candidate.recipeId(), Set.of()));
        return materialPlans.computeIfAbsent(new MaterialPlanKey(candidate.recipeId(), unavailable), ignored -> {
            List<ObservedRecipeStockCost.Need> ingredients = new ArrayList<>();
            for (var ingredient : candidate.ingredients()) {
                if (ingredient.required() > 32768)
                    return new RecipeMaterialPlan.Result(false, false, RecipeMaterialPlan.UNREACHABLE, List.of(), List.of(), Map.of());
                ingredients.add(new ObservedRecipeStockCost.Need(
                        ingredient.itemIds(), ingredient.required()));
            }
            Map<ResourceLocation, Long> pool = new HashMap<>(recipeCarriedStock);
            recipeObservedStock.forEach((id, amount) -> pool.merge(id, amount,
                    (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b));
            // 最终数量已包含的成品不是免费前置材料：缺第三个链路时不能把已有两个改成发信器再换回来。
            // 这里只预留当前需求的成品；采木前制作工具仍可使用祖先需求中已有的原木。
            pool.keySet().removeAll(parent.itemIds);
            return RecipeMaterialPlan.estimate(ingredients, pool, item -> processRecipes(item, parent),
                    item -> sourceCost(item, parent), parent.lineageItems, unavailable, preferredMaterials);
        });
    }

    private List<ObservedRecipeStockCost.Recipe> processRecipes(ResourceLocation output, AcquisitionNeed parent) {
        List<ObservedRecipeStockCost.Recipe> recipes = new ArrayList<>();
        if (parent.allowedSources.contains(SemanticAcquireTaskRecord.Source.CRAFT)) recipes.addAll(stockRecipes(output));
        if (parent.allowedSources.contains(SemanticAcquireTaskRecord.Source.COOK)) recipes.addAll(processes.cooking(output));
        return recipes;
    }

    /** 比较直接烧炼与绕远的拆包合成；不能为了一个铁锭先追九件铁装备来熔成铁粒。 */
    long cookingCost(AcquisitionNeed parent) {
        return cookingPlan(parent).cost();
    }

    boolean preferCooking(CraftRecoveryCandidate craft, AcquisitionNeed parent) {
        var cooking = cookingPlan(parent);
        return cooking.feasible() && (craft == null || RecipeMaterialPlan.ORDER.compare(cooking, materialPlan(craft, parent)) < 0);
    }

    private RecipeMaterialPlan.Result cookingPlan(AcquisitionNeed parent) {
        var best = new RecipeMaterialPlan.Result(false, true, RecipeMaterialPlan.UNREACHABLE, List.of(), List.of(), Map.of());
        if (!parent.canTry(SemanticAcquireTaskRecord.Source.COOK)) return best;
        refreshStock(parent);
        int missing = parent.requiredFinalCount - parent.itemIds.stream().mapToInt(id -> recipeCarriedStock.getOrDefault(id, 0L).intValue()).sum();
        Map<ResourceLocation, Long> pool = new HashMap<>(recipeCarriedStock);
        recipeObservedStock.forEach((id, amount) -> pool.merge(id, amount, (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b));
        for (var output : parent.itemIds) for (var recipe : processes.cooking(output)) {
            int batches = (int) (((long) Math.max(1, missing) + recipe.outputCount() - 1) / recipe.outputCount());
            if (recipe.ingredients().stream().anyMatch(input -> (long) input.count() * batches > 32768)) continue;
            var inputs = recipe.ingredients().stream().map(input -> new ObservedRecipeStockCost.Need(input.alternatives(), input.count() * batches)).toList();
            var estimate = RecipeMaterialPlan.estimate(inputs, pool, item -> processRecipes(item, parent),
                    item -> sourceCost(item, parent), parent.lineageItems, Set.of(), preferredMaterials);
            // 烧炼和格子合成使用同一软偏好次序，再比较实际原料账和加工批次成本。
            var candidate = new RecipeMaterialPlan.Result(estimate.feasible(), estimate.searchComplete(),
                    estimate.cost() + (long) batches * recipe.batchCost(), estimate.supplies(), estimate.crafts(),
                    estimate.remaining(), estimate.preferredMaterialsUsed());
            if (RecipeMaterialPlan.ORDER.compare(candidate, best) < 0) best = candidate;
        }
        return best;
    }

    /** 来源分值表示取得方式的相对难度，不声称未观察的野外一定有目标。 */
    private RecipeMaterialPlan.Source sourceCost(ResourceLocation id, AcquisitionNeed parent) {
        var source = SemanticSourceKnowledge.inferPlan(List.of(id));
        var hint = source.hint();
        Item item = BuiltInRegistries.ITEM.get(id);
        boolean mine = parent.allowedSources.contains(SemanticAcquireTaskRecord.Source.MINE)
                && (source.allowedDimensions(SemanticAcquireTaskRecord.Source.MINE).isEmpty()
                    || source.allowedDimensions(SemanticAcquireTaskRecord.Source.MINE).contains(player.level().dimension().location()));
        if (mine && (!hint.blockRefs().isEmpty() || item instanceof BlockItem block && block.getBlock().defaultBlockState().is(BlockTags.LOGS))) {
            Set<Block> blocks = miningBlocks(id);
            // 已确认的附近来源按距离获得较低估价；仍只记获取成本，原木和矿物不会凭观察进入库存账。
            int cost = nearbySources == null ? 40 : nearbySources.nearest(blocks)
                    .map(seen -> Math.clamp((int) Math.ceil(Math.sqrt(seen.distanceSquared())), 1, 20)).orElse(40);
            return new RecipeMaterialPlan.Source(true, cost);
        }
        if (allowHarm && parent.allowedSources.contains(SemanticAcquireTaskRecord.Source.HUNT) && !hint.entityTypeIds().isEmpty())
            return new RecipeMaterialPlan.Source(true, 100);
        return new RecipeMaterialPlan.Source(false, 10000);
    }

    private Set<Block> miningBlocks(ResourceLocation itemId) {
        var hint = SemanticSourceKnowledge.inferPlan(List.of(itemId)).hint();
        Set<Block> blocks = new LinkedHashSet<>(ToolParse.parseBlocks(hint.blockRefs()));
        if (BuiltInRegistries.ITEM.get(itemId) instanceof BlockItem block
                && block.getBlock().defaultBlockState().is(BlockTags.LOGS)) blocks.add(block.getBlock());
        return blocks;
    }

    private List<Map<String, Object>> nearbySupplyEvidence(RecipeMaterialPlan.Result plan) {
        if (nearbySources == null) return List.of();
        List<Map<String, Object>> evidence = new ArrayList<>();
        // 在采集开始前冻结本次选料依据；树被砍掉后，回执仍能解释当时为什么选择这条配方。
        for (var supply : plan.supplies()) for (var item : supply.alternatives())
            nearbySources.nearest(miningBlocks(item)).ifPresent(seen -> evidence.add(Map.of(
                    "item_id", item.toString(), "source_block_id", BuiltInRegistries.BLOCK.getKey(
                            player.level().getBlockState(seen.position()).getBlock()).toString(),
                    "position", List.of(seen.position().getX(), seen.position().getY(), seen.position().getZ()),
                    "distance_squared", seen.distanceSquared(), "natural_tree", seen.naturalTree(),
                    "observed_game_time", player.level().getGameTime())));
        return List.copyOf(evidence);
    }

    int stockPriority(CraftRecoveryCandidate candidate, AcquisitionNeed parent) {
        var plan = materialPlan(candidate, parent);
        return plan.feasible() && plan.supplies().isEmpty() ? 0 : 1;
    }

    Map<String, Object> preparation(CraftRecoveryCandidate candidate, AcquisitionNeed parent) {
        var plan = materialPlan(candidate, parent);
        // 只报告本次所需的备料项与合成顺序，完整 AE 网络清单留在 Mod，避免再次灌满模型上下文。
        return Map.of("feasible", plan.feasible(), "search_complete", plan.searchComplete(), "estimated_cost", plan.cost(),
                "preferred_materials", preferredMaterials.stream().map(ResourceLocation::toString).sorted().toList(),
                "preferred_materials_used", plan.preferredMaterialsUsed().stream().map(ResourceLocation::toString).sorted().toList(),
                "nearby_supply_evidence", nearbySupplyEvidence(plan),
                "supply_list", plan.supplies().stream().map(need -> Map.of("item_ids", need.alternatives().stream().map(ResourceLocation::toString).toList(), "count", need.count())).toList(),
                "craft_chain", plan.crafts().stream().map(need -> Map.of("item_ids", need.alternatives().stream().map(ResourceLocation::toString).toList(), "required_output", need.count())).toList());
    }

    /** 各条路线都只差一件时可合并替代原料；否则保留一条完整配方，不能从两条路线各凑一半。 */
    Frontier chooseFrontier(
            CraftRecoveryCandidate chosen,
            List<CraftRecoveryCandidate> viableCandidates,
            AcquisitionNeed parent) {
        var preparation = materialPlan(chosen, parent);
        if (preparation.feasible() && !preparation.supplies().isEmpty()) {
            // 先把完整树选出的补料项凑齐，再回来做中间件；已经预留给其他分支的现货不能重复花掉。
            var supply = preparation.supplies().stream().sorted(Comparator
                    .comparing((ObservedRecipeStockCost.Need row) -> ingredientRequiresDecision(row.alternatives(), parent)).reversed())
                    .findFirst().orElseThrow();
            long externalStock = supply.alternatives().stream().mapToLong(id -> recipeObservedStock.getOrDefault(id, 0L)).sum();
            long deficit = supply.count() + externalStock;
            var conversion=directUnitConversion(chosen,preparation,parent);
            if ((deficit == 1 || conversion!=null) && preparation.supplies().size() == 1 && externalStock==0) {
                // 任一整条树只需再补一件时可共同寻找原料；拿到的那件会选定可完成的完整配方。
                // 原木到木板这类等产量单料转换合并所有可用木种，让采矿一次搜索最近来源，实际合成再按木种结算。
                // 已命中材料倾向时只保留该路线选中的输入，不能在合并木种时重新放宽到未偏好的原木。
                Set<ResourceLocation> items = new LinkedHashSet<>(conversion == null || !preparation.preferredMaterialsUsed().isEmpty()
                        ? supply.alternatives() : conversion.inputs());
                Set<String> alternatives = new LinkedHashSet<>(Set.of(chosen.recipeId()));
                Set<ResourceLocation> outputs = new LinkedHashSet<>(List.of(chosen.outputItem()));
                for (var candidate : viableCandidates) {
                    if (candidate.cost().surface() != chosen.cost().surface()) continue;
                    var plan = materialPlan(candidate, parent);
                    if (!plan.feasible() || RecipeMaterialPlan.ORDER.compare(plan, preparation) != 0
                            || plan.supplies().size() != 1 || plan.supplies().getFirst().count() != deficit) continue;
                    var next = plan.supplies().getFirst();
                    if (next.alternatives().stream().anyMatch(id -> recipeObservedStock.getOrDefault(id, 0L) > 0)) continue;
                    var other=directUnitConversion(candidate,plan,parent);
                    if(deficit>1 && (other==null || other.outputCount()!=conversion.outputCount()))continue;
                    items.addAll(deficit > 1 && plan.preferredMaterialsUsed().isEmpty() ? other.inputs() : next.alternatives());
                    alternatives.add(candidate.recipeId()); outputs.add(candidate.outputItem());
                }
                return new Frontier(new IngredientNeed(List.copyOf(items), (int)deficit), Set.copyOf(alternatives), List.copyOf(outputs),
                        items.stream().noneMatch(id -> sourceCost(id, parent).known()));
            }
            if (deficit <= 32768) return new Frontier(new IngredientNeed(supply.alternatives(), (int) deficit), Set.of(chosen.recipeId()), List.of(chosen.outputItem()),
                    supply.alternatives().stream().noneMatch(id -> sourceCost(id, parent).known()));
        }
        if (preparation.feasible() && preparation.supplies().isEmpty() && !preparation.crafts().isEmpty()) {
            // 备料齐后按依赖顺序从最下层制造，避免每次再向下压一长串需求，或先花掉别支预留的料。
            var next = preparation.crafts().getFirst();
            long needed = next.count() + next.alternatives().stream().mapToLong(id -> recipeObservedStock.getOrDefault(id, 0L)).sum();
            if (needed <= 32768) return new Frontier(new IngredientNeed(next.alternatives(), (int) needed), Set.of(chosen.recipeId()), List.of(chosen.outputItem()));
        }
        // 多个配方若各只差一件，任意拿到其中一种原料就能完成一个配方，可以合成一组替代需求一起寻找。
        // 各差多件时不能这样混，因为从两个配方各凑一半，并不保证任何一个能做。
        IngredientNeed primary = chooseIngredient(chosen, parent);
        if (primary == null) return null;

        LinkedHashSet<ResourceLocation> itemIds = new LinkedHashSet<>(primary.itemIds());
        LinkedHashSet<String> recipeIds = new LinkedHashSet<>();
        LinkedHashSet<ResourceLocation> outputItemIds = new LinkedHashSet<>();
        recipeIds.add(chosen.recipeId());
        outputItemIds.add(chosen.outputItem());

        if (chosen.cost().missingMaterials() == 1 && primary.missing() == 1) {
            for (CraftRecoveryCandidate candidate : viableCandidates) {
                if (candidate == chosen
                        || candidate.cost().missingMaterials() != 1
                        || candidate.cost().surface() != chosen.cost().surface()) {
                    continue;
                }
                IngredientNeed alternative = chooseIngredient(candidate, parent);
                if (alternative == null || alternative.missing() != 1) continue;
                itemIds.addAll(alternative.itemIds());
                recipeIds.add(candidate.recipeId());
                outputItemIds.add(candidate.outputItem());
            }
        }

        IngredientNeed frontier = primary;
        if (recipeIds.size() > 1) {
            frontier = new IngredientNeed(List.copyOf(itemIds), 1);
        }
        return new Frontier(
                frontier,
                Collections.unmodifiableSet(recipeIds),
                List.copyOf(outputItemIds));
    }

}
