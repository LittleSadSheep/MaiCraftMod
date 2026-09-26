// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticSourceKnowledge;
import org.maiwithu.maicraft.core.tools.RecipeProbe;
import org.maiwithu.maicraft.core.tools.ToolParse;
import org.maiwithu.maicraft.core.inventory.StockEvidence;

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

    void observeNearbyBlocks() { nearbyBlockDistances = snapshotNearbyBlocks(); }

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
                var recipe = holder.value();
                boolean grid = recipe instanceof CraftingRecipe && request.allowedSources.contains(SemanticAcquireTaskRecord.Source.CRAFT);
                boolean cooking = recipe instanceof AbstractCookingRecipe && request.allowedSources.contains(SemanticAcquireTaskRecord.Source.COOK);
                // 这里只展开获准的备料路线；真实两段烧炼仍逐段交给炉子任务，不能把烧炼伪装成格子合成。
                if ((!grid && !cooking) || recipe.isSpecial() || !RecipeProbe.usableIngredients(recipe)) {
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
                boolean workstation = grid && (recipe instanceof ShapedRecipe shaped
                        ? shaped.getWidth() > 2 || shaped.getHeight() > 2
                        : ingredientUses > 4);
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
    private static List<IngredientGroup> ingredientGroups(Recipe<?> recipe) {
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
    // 一条方案共用一份临时库存；试别的配方时复制它，选定后才保留该方案的消耗，不写玩家背包。
    private long acquisitionCost(
            Item item, int required, Set<Item> lineage, int depth, Map<Item, Long> stock) {
        if (required <= 0) return 0L;
        if (item == null || item == Items.AIR) return UNAVAILABLE_COST;
        int missing = required - takeStock(stock, item, required);
        if (missing == 0) return 0L;

        long best = directSourceCost(item, missing);
        if (request.allowedSources.contains(SemanticAcquireTaskRecord.Source.STORAGE)) {
            // 普通箱子也能供料；装没装 AE2 不能决定整个仓库来源是否存在。
            best = Math.min(best, addCost(
                    STORAGE_FALLBACK_COST, (long) missing * DIRECT_SOURCE_UNIT_COST));
        }
        if (depth >= MAX_ACQUISITION_DEPTH || !request.productionLineage.mayDescend()
                || (!request.allowedSources.contains(SemanticAcquireTaskRecord.Source.CRAFT)
                    && !request.allowedSources.contains(SemanticAcquireTaskRecord.Source.COOK))
                || lineage.contains(item) || request.productionLineage.blocks(BuiltInRegistries.ITEM.getKey(item))
                || request.itemId.equals(BuiltInRegistries.ITEM.getKey(item))) {
            return best;
        }

        List<CraftRoute> routes = routesFor(item);
        if (routes.isEmpty()) return best;
        Set<Item> nextLineage = new LinkedHashSet<>(lineage);
        nextLineage.add(item);
        Map<Item, Long> bestStock = new HashMap<>(stock);
        for (CraftRoute route : routes) {
            Map<Item, Long> trial = new HashMap<>(stock);
            int batches = Math.ceilDiv(missing, route.outputCount());
            long routeCost = addCost(
                    (long) batches * CRAFT_ROUTE_COST,
                    route.requiresWorkstation() ? CRAFTING_SURFACE_COST : 0L);
            for (IngredientGroup group : route.ingredients().stream()
                    .sorted(Comparator.comparingInt(group -> group.alternatives().size())).toList()) {
                int groupRequired = Math.max(1, batches * group.uses());
                long alternativeCost = groupCost(group.alternatives(), groupRequired, nextLineage, depth + 1, trial);
                routeCost = addCost(routeCost, alternativeCost);
                if (routeCost >= UNAVAILABLE_COST) break;
            }
            if (routeCost < best) {
                best = routeCost;
                trial.merge(item, (long) batches * route.outputCount() - missing, Long::sum);
                bestStock = trial;
            }
        }
        stock.clear();
        stock.putAll(bestStock);
        return best;
    }

    private long groupCost(List<Item> alternatives, int required, Set<Item> lineage, int depth, Map<Item, Long> stock) {
        // 一组可替代材料先合计现货，例如两种木板可以共同填满同一组配方格。
        int missing = required;
        for (Item item : alternatives) missing -= takeStock(stock, item, missing);
        if (missing == 0) return 0;
        long best = UNAVAILABLE_COST;
        Map<Item, Long> bestStock = null;
        for (Item item : alternatives) {
            Map<Item, Long> trial = new HashMap<>(stock);
            long cost = acquisitionCost(item, missing, lineage, depth, trial);
            if (cost < best) { best = cost; bestStock = trial; }
        }
        if (bestStock != null) { stock.clear(); stock.putAll(bestStock); }
        return best;
    }

    private Map<Item, Long> inventoryStock() {
        Map<Item, Long> stock = new HashMap<>();
        // 已授权且新近观察过的AE现货参与整棵材料树的共享预算；实际缺口仍由取材任务确认入包，不凭估价发物品。
        if (request.allowedSources.contains(SemanticAcquireTaskRecord.Source.WIRELESS)
                || request.allowedSources.contains(SemanticAcquireTaskRecord.Source.STORAGE))
            StockEvidence.latestNetwork(player).ifPresent(snapshot -> snapshot.stored().forEach((id, count) -> {
                if (BuiltInRegistries.ITEM.containsKey(id)) stock.put(BuiltInRegistries.ITEM.get(id), count);
            }));
        for (int slot = 0; slot < Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size()); slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (!stack.isEmpty()) stock.merge(stack.getItem(), (long) stack.getCount(),
                    (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
        }
        return stock;
    }

    private static int takeStock(Map<Item, Long> stock, Item item, int required) {
        long carried = stock.getOrDefault(item, 0L);
        int used = (int) Math.min(required, carried);
        stock.put(item, carried - used);
        return used;
    }

    // 来源线索帮助估价；附近掉落、仓库和交易仍要由真实取物子任务确认，不能仅因没有内置线索就跳过已允许的来源。
    private long directSourceCost(Item item, int missing) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId == null) return UNAVAILABLE_COST;
        SemanticSourceKnowledge.SourcePlan plan =
                SemanticSourceKnowledge.inferPlan(List.of(itemId));
        long best = UNAVAILABLE_COST;
        if (request.allowedSources.contains(SemanticAcquireTaskRecord.Source.NEARBY))
            best = 100_000L + (long) missing * DIRECT_SOURCE_UNIT_COST;

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
        if (request.allowedSources.contains(SemanticAcquireTaskRecord.Source.TRADE)) {
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

    record FuelChoice(Item item, int burnTicks, int count, long waste, long acquisitionCost,
                      long inputCost, long stationCost) {
        long preparationCost() { return addCost(acquisitionCost, inputCost, stationCost); }
    }

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
                .comparingLong(FuelChoice::preparationCost)
                .thenComparingLong(FuelChoice::waste)
                .thenComparingInt(FuelChoice::count)
                .thenComparingInt(choice -> fuelPriority(choice.item()))
                .thenComparing(choice -> BuiltInRegistries.ITEM.getKey(
                        choice.item()).toString());
        if (request.preference == SemanticCookTaskRecord.Preference.PRESERVE_RARE) {
            economical = Comparator
                    .comparingLong(FuelChoice::preparationCost)
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
        // 同一份原木不能既留作烧木炭的原料，又算成可免费烧掉的燃料；设备备料也参与同一本账。
        Map<Item, Long> stock = inventoryStock();
        long stationCost = nearbyBlockDistances.containsKey(cooking.device().block) ? 0L
                : acquisitionCost(cooking.device().block.asItem(), 1, Set.of(), 0, stock);
        long inputCost = acquisitionCost(cooking.input(), raw, Set.of(), 0, stock);
        long cost = acquisitionCost(item, needed, Set.of(), 0, stock);
        long waste = (long) needed * burn - neededTicks;
        return new FuelChoice(item, burn, needed, waste, cost, inputCost, stationCost);
    }

    record ResolvedCandidate(
            CookingRecipe candidate,
            FuelChoice fuel,
            long inputCost,
            long stationCost,
            long preparationCost) {}

    // 从客户端已收到的配方表读加工结果，展开第一种原料的物品种类；读出异常的配方略过。
    // 这里保存 Item 而非完整 ItemStack，组件敏感的特殊配方需要另查是否能完整表达。
    List<CookingRecipe> candidates() {
        List<CookingRecipe> result = new ArrayList<>();
        var manager = ClientRuntime.requireContext(player).connection().getRecipeManager();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            try {
                if (!(holder.value() instanceof AbstractCookingRecipe cooking)
                        || !RecipeProbe.usableIngredients(cooking)) continue;
                ItemStack output = RecipeProbe.resultOf(
                        cooking, player.level().registryAccess());
                if (output.isEmpty() || !output.is(BuiltInRegistries.ITEM.get(request.itemId))) {
                    continue;
                }
                CookingDevice device = CookingDevice.forRecipe(cooking.getType());
                if (device == null || cooking.getIngredients().isEmpty()) continue;
                LinkedHashSet<Item> inputs = new LinkedHashSet<>();
                for (ItemStack stack : cooking.getIngredients().getFirst().getItems()) {
                    if (stack != null && !stack.isEmpty()) inputs.add(stack.getItem());
                }
                for (Item input : inputs) result.add(new CookingRecipe(
                        holder.id(), cooking, device, input, Math.max(1, output.getCount())));
            } catch (RuntimeException brokenRecipe) {
                Constants.LOG.debug(
                        "[maicraft-cook] skipped unusable cooking recipe {}: {}",
                        holder.id(), brokenRecipe.toString());
            }
        }
        return result;
    }

    // FASTEST 优先比较单次烧制时间，再看准备成本；其他偏好先看准备成本。
    // 这不是把走路、备料和全部批次耗时相加后的总完成时间。
    Comparator<ResolvedCandidate> candidateComparator() {
        Comparator<ResolvedCandidate> preparation = Comparator
                .comparingLong(ResolvedCandidate::preparationCost)
                .thenComparingLong(ResolvedCandidate::inputCost)
                .thenComparingLong(ResolvedCandidate::stationCost);
        Comparator<ResolvedCandidate> speed = Comparator.comparingInt(
                plan -> plan.candidate().recipe().getCookingTime());
        Comparator<ResolvedCandidate> stable = Comparator
                .comparingInt((ResolvedCandidate plan) -> plan.candidate().device().ordinal())
                .thenComparing(plan -> BuiltInRegistries.ITEM.getKey(
                        plan.candidate().input()).toString())
                .thenComparing(plan -> plan.candidate().recipeId().toString());
        return request.preference == SemanticCookTaskRecord.Preference.FASTEST
                ? speed.thenComparing(preparation).thenComparing(stable)
                : preparation.thenComparing(speed).thenComparing(stable);
    }

    boolean preferred(CookingDevice device) {
        return switch (request.preference) {
            case SMELTING -> device == CookingDevice.FURNACE;
            case BLASTING -> device == CookingDevice.BLAST_FURNACE;
            case SMOKING -> device == CookingDevice.SMOKER;
            default -> true;
        };
    }

    // 一次读取周围已加载的小范围方块，按种类记最近距离，供估价和判断有没有设备；不读取未加载地形。
    private Map<Block, Long> snapshotNearbyBlocks() {
        Map<Block, Long> distances = new HashMap<>();
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!player.level().isLoaded(cursor)) continue;
                    Block block = player.level().getBlockState(cursor).getBlock();
                    if (block != Blocks.AIR) {
                        long dxDistance = cursor.getX() - origin.getX();
                        long dyDistance = cursor.getY() - origin.getY();
                        long dzDistance = cursor.getZ() - origin.getZ();
                        long distanceSquared = dxDistance * dxDistance
                                + dyDistance * dyDistance + dzDistance * dzDistance;
                        distances.merge(block, distanceSquared, Math::min);
                    }
                }
            }
        }
        return Map.copyOf(distances);
    }

}
