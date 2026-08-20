// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.mixin.MenuDataSlotsAccessor;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticSourceKnowledge;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.menu.CloseMenuTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.tools.RecipeProbe;
import org.maiwithu.maicraft.core.tools.ToolParse;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Receipt-driven furnace-family executor; all concrete operations stay internal. */
public final class SemanticCookCompanionTask
        extends AbstractCompanionTask<SemanticCookTaskRecord> {
    private static final long UNAVAILABLE_COST = 1_000_000_000_000L;
    private static final int MAX_ACQUISITION_DEPTH = 4;
    private static final long DIRECT_SOURCE_UNIT_COST = 1_000L;
    private static final long OBSERVED_BLOCK_UNIT_COST = 500L;
    private static final long CRAFT_ROUTE_COST = 250L;
    private static final long CRAFTING_SURFACE_COST = 500L;
    private static final long TOOL_PREREQUISITE_COST = 1_500L;
    private static final long STORAGE_FALLBACK_COST = 50_000L;
    /**
     * Furnace progress is live server evidence, so each observed change renews a
     * no-progress lease instead of spending one fixed wall-clock budget for the
     * whole (possibly multi-batch, Mod-recipe) cooking goal.
     */
    private static final long COOK_PROGRESS_LEASE_TICKS = 30L * 20L;
    private enum Phase { RESOLVE, PREPARE, OPEN, WAIT_MENU, VALIDATE, LOAD_INPUT,
        LOAD_FUEL, CONFIRM_START, CLOSE_WAIT, WAIT_CLOSED, RECONCILE,
        VERIFY_OUTPUT, VERIFY_CLEAN_INPUT, CLEANUP, COMPLETE }
    private enum OpenMode { NEW_BATCH, RESUME_BATCH }
    private enum Purpose { ACQUIRE_INPUT, ACQUIRE_FUEL, ACQUIRE_STATION, PLACE_STATION,
        MOVE_STATION, OPEN_STATION, LOAD_INPUT, LOAD_FUEL, TAKE_OUTPUT,
        CLOSE_WAIT, CLEAN_INPUT, CLOSE, ABANDON_CLOSE }
    private enum Device {
        FURNACE(Blocks.FURNACE), BLAST_FURNACE(Blocks.BLAST_FURNACE),
        SMOKER(Blocks.SMOKER), CAMPFIRE(Blocks.CAMPFIRE);
        final Block block;
        Device(Block block) { this.block = block; }
    }
    private record Candidate(
            ResourceLocation recipeId, AbstractCookingRecipe recipe, Device device,
            Item input, int outputCount) {}
    private record IngredientGroup(List<Item> alternatives, int uses) {}
    private record CraftRoute(
            ResourceLocation recipeId,
            int outputCount,
            List<IngredientGroup> ingredients,
            boolean requiresWorkstation) {}
    private record FuelChoice(
            Item item, int burnTicks, int count, long waste, long acquisitionCost) {}
    private record ResolvedCandidate(
            Candidate candidate,
            FuelChoice fuel,
            long inputCost,
            long stationCost,
            long preparationCost) {}

    private Phase phase = Phase.RESOLVE;
    private Candidate candidate;
    private Item fuel;
    private int fuelBurnTicks;
    private int batchRaw;
    private int batchFuel;
    private BlockPos stationPos;
    private boolean stationClaimed;
    private boolean stationPlaced;
    private boolean openedMenu;
    private boolean effectsStarted;
    private boolean finishRequested;
    private boolean replenishAfterClose;
    private OpenMode openMode = OpenMode.NEW_BATCH;
    private BlockPos stationReturnStance;
    private long nextCookCheckTick;
    private long closedWaitStartedTick;
    private int ownedInputLoaded;
    private int ownedOutputTaken;
    private int takeInventoryBefore;
    private int takeSlotCount;
    private int cleanupInventoryBefore;
    private int cleanupTransferCount;
    private boolean cleanupSnapshotReady;
    private ItemStack cleanupInputExpected = ItemStack.EMPTY;
    private long waitMenuSince;
    private long lastCookEvidenceTick;
    private String lastCookEvidence = "";
    private BlockPos openAttemptStation;
    private int openAttempts;
    private Task activeChild;
    private TaskRecord activeRecord;
    private Purpose activePurpose;
    private int childSerial;
    private int initialOutputCount;
    private String failureCode;
    private String failureMessage;
    private FailureType failureType = FailureType.UNKNOWN;
    private String failedChildStage;
    private String failedChildMessage;
    private boolean outcomeUncertain;
    private Map<String, Object> prerequisiteFailure = Map.of();
    private Map<Item, List<CraftRoute>> craftRoutes = Map.of();
    private boolean craftRoutesIndexed;
    /** Minimum squared loaded-world distance for each observed block type. */
    private Map<Block, Long> nearbyBlockDistances = Map.of();
    private final Set<String> rejectedInputCandidates = new LinkedHashSet<>();
    private final Set<Item> rejectedFuelItems = new LinkedHashSet<>();
    private final Set<Device> rejectedDevices = new LinkedHashSet<>();
    private final List<Map<String, Object>> planningAttempts = new ArrayList<>();

    public SemanticCookCompanionTask(LocalPlayer player, SemanticCookTaskRecord record) {
        super(player, record);
    }

    @Override protected void onStart() { initialOutputCount = outputCount(); }

    @Override
    protected TaskState onTick() {
        boolean outputTransferSettling = phase == Phase.VERIFY_OUTPUT
                || phase == Phase.VERIFY_CLEAN_INPUT
                || activePurpose == Purpose.TAKE_OUTPUT
                || activePurpose == Purpose.CLEAN_INPUT;
        if (outputCount() >= r.count && !finishRequested && !outputTransferSettling) {
            finishRequested = true;
        }
        if (activeChild != null) return tickChild();
        if (phase == Phase.COMPLETE) {
            if (failureMessage != null) {
                fail(failureMessage, failureType);
                return TaskState.FAILED;
            }
            return TaskState.SUCCESS;
        }
        routeFinishRequest();
        return switch (phase) {
            case RESOLVE -> resolve();
            case PREPARE -> prepare();
            case OPEN -> openStation();
            case WAIT_MENU -> waitMenu();
            case VALIDATE -> validateMenu();
            case LOAD_INPUT -> loadInput();
            case LOAD_FUEL -> loadFuel();
            case CONFIRM_START -> confirmStart();
            case CLOSE_WAIT -> closeForCookWait();
            case WAIT_CLOSED -> waitClosed();
            case RECONCILE -> reconcileBatch();
            case VERIFY_OUTPUT -> verifyOutputTake();
            case VERIFY_CLEAN_INPUT -> verifyCleanupInput();
            case CLEANUP -> cleanupMachine();
            case COMPLETE -> TaskState.SUCCESS;
        };
    }

    /**
     * A satisfied inventory fact does not erase a batch already committed to a closed machine.
     * Reopen and reconcile that exact batch before normal cleanup; only an uncommitted cook may
     * jump directly to the terminal cleanup phase.
     */
    private void routeFinishRequest() {
        if (!finishRequested || phase == Phase.CLEANUP || phase == Phase.COMPLETE
                || phase == Phase.VERIFY_OUTPUT || phase == Phase.VERIFY_CLEAN_INPUT) {
            return;
        }
        if (!stationClaimed || ownedInputLoaded <= 0) {
            phase = Phase.CLEANUP;
            return;
        }
        if (player.containerMenu instanceof AbstractFurnaceMenu && menuMatches()) {
            openedMenu = true;
            phase = Phase.RECONCILE;
            return;
        }
        if (phase == Phase.WAIT_CLOSED) {
            nextCookCheckTick = player.level().getGameTime();
            return;
        }
        if (phase == Phase.OPEN || phase == Phase.WAIT_MENU) {
            return;
        }
        openMode = OpenMode.RESUME_BATCH;
        nextCookCheckTick = player.level().getGameTime();
        phase = Phase.WAIT_CLOSED;
    }

    @Override
    public boolean canRun(LocalPlayer companion) {
        return phase != Phase.WAIT_CLOSED || closedWaitDue(companion);
    }

    private boolean closedWaitDue(LocalPlayer companion) {
        long now = companion.level().getGameTime();
        if (now >= nextCookCheckTick) return true;
        if (stationPos == null || !companion.level().isLoaded(stationPos)) return false;
        var state = companion.level().getBlockState(stationPos);
        if (candidate == null || !state.is(candidate.device.block)) return true;
        // Once the close has had a couple of server ticks to settle, an extinguished loaded
        // furnace is useful early evidence: either the batch completed or it needs attention.
        return now > closedWaitStartedTick + 2L
                && state.hasProperty(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                && !state.getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT);
    }

    private TaskState resolve() {
        List<Candidate> candidates = candidates();
        if (candidates.isEmpty()) {
            return failOrClean("no_cooking_recipe",
                    "No smelting, blasting, smoking or campfire recipe produces " + r.itemId + ".",
                    FailureType.NO_MATERIAL);
        }
        if (r.preference == SemanticCookTaskRecord.Preference.CAMPFIRE) {
            boolean available = candidates.stream().anyMatch(c -> c.device == Device.CAMPFIRE);
            return failOrClean(
                    available ? "campfire_execution_not_supported" : "no_preferred_recipe",
                    available
                            ? "A campfire recipe exists, but this version only executes synchronized "
                                    + "furnace, blast-furnace and smoker menus."
                            : "No campfire recipe produces " + r.itemId + ".",
                    FailureType.UNKNOWN);
        }
        candidates.removeIf(c -> c.device == Device.CAMPFIRE || !preferred(c.device)
                || rejectedDevices.contains(c.device)
                || rejectedInputCandidates.contains(candidateKey(c)));
        if (candidates.isEmpty()) {
            return failOrClean("no_preferred_recipe",
                    "No untried recipe matching recipe_preference can produce " + r.itemId + ".",
                    FailureType.NO_MATERIAL);
        }
        if (!craftRoutesIndexed) craftRoutes = indexCraftRoutes();
        nearbyBlockDistances = snapshotNearbyBlocks();

        List<ResolvedCandidate> plans = new ArrayList<>();
        for (Candidate option : candidates) {
            int raw = rawRemaining(option);
            long inputCost = acquisitionCost(
                    option.input, raw, Set.of(), 0);
            boolean ready = stationReady(option.device);
            long stationCost = ready ? 0L : acquisitionCost(
                    option.device.block.asItem(), 1, Set.of(), 0);
            FuelChoice fuelChoice = chooseFuel(option);
            if (fuelChoice == null) continue;
            long preparationCost = addCost(
                    inputCost, stationCost, fuelChoice.acquisitionCost());
            plans.add(new ResolvedCandidate(
                    option, fuelChoice, inputCost, stationCost,
                    preparationCost));
        }
        if (plans.isEmpty()) {
            return failOrClean("no_allowed_fuel",
                    "No allowed ordinary furnace fuel can be selected.", FailureType.NO_MATERIAL);
        }
        ResolvedCandidate selected = plans.stream()
                .filter(plan -> plan.preparationCost() < UNAVAILABLE_COST)
                .min(candidateComparator())
                .orElse(null);
        if (selected == null) {
            return failOrClean("no_reachable_cooking_plan",
                    "Cooking recipes exist, but none has a supported path to its input, fuel and workstation.",
                    FailureType.NO_MATERIAL);
        }
        candidate = selected.candidate();
        FuelChoice selectedFuel = selected.fuel();
        fuel = selectedFuel.item();
        fuelBurnTicks = selectedFuel.burnTicks();
        prerequisiteFailure = Map.of();
        phase = Phase.PREPARE;
        return TaskState.RUNNING;
    }

    private List<Candidate> candidates() {
        List<Candidate> result = new ArrayList<>();
        var manager = ClientRuntime.requireContext(player).connection().getRecipeManager();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            try {
                if (!(holder.value() instanceof AbstractCookingRecipe cooking)
                        || !RecipeProbe.usableIngredients(cooking)) continue;
                ItemStack output = RecipeProbe.resultOf(
                        cooking, player.level().registryAccess());
                if (output.isEmpty() || !output.is(BuiltInRegistries.ITEM.get(r.itemId))) {
                    continue;
                }
                Device device = device(cooking.getType());
                if (device == null || cooking.getIngredients().isEmpty()) continue;
                LinkedHashSet<Item> inputs = new LinkedHashSet<>();
                for (ItemStack stack : cooking.getIngredients().getFirst().getItems()) {
                    if (stack != null && !stack.isEmpty()) inputs.add(stack.getItem());
                }
                for (Item input : inputs) result.add(new Candidate(
                        holder.id(), cooking, device, input, Math.max(1, output.getCount())));
            } catch (RuntimeException brokenRecipe) {
                org.maiwithu.maicraft.core.Constants.LOG.debug(
                        "[maicraft-cook] skipped unusable cooking recipe {}: {}",
                        holder.id(), brokenRecipe.toString());
            }
        }
        return result;
    }

    private Comparator<ResolvedCandidate> candidateComparator() {
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
        return r.preference == SemanticCookTaskRecord.Preference.FASTEST
                ? speed.thenComparing(preparation).thenComparing(stable)
                : preparation.thenComparing(speed).thenComparing(stable);
    }

    private boolean preferred(Device device) {
        return switch (r.preference) {
            case SMELTING -> device == Device.FURNACE;
            case BLASTING -> device == Device.BLAST_FURNACE;
            case SMOKING -> device == Device.SMOKER;
            default -> true;
        };
    }

    private static Device device(RecipeType<?> type) {
        if (type == RecipeType.SMELTING) return Device.FURNACE;
        if (type == RecipeType.BLASTING) return Device.BLAST_FURNACE;
        if (type == RecipeType.SMOKING) return Device.SMOKER;
        if (type == RecipeType.CAMPFIRE_COOKING) return Device.CAMPFIRE;
        return null;
    }

    private FuelChoice chooseFuel(Candidate cooking) {
        List<Item> choices = new ArrayList<>();
        if (!r.allowedFuelIds.isEmpty()) {
            r.allowedFuelIds.forEach(id -> choices.add(BuiltInRegistries.ITEM.get(id)));
        } else {
            for (Item item : AbstractFurnaceBlockEntity.getFuel().keySet()) {
                if (safeDefaultFuel(item)) choices.add(item);
            }
        }
        int raw = Math.min(rawRemaining(cooking), Math.max(1, 64 / cooking.outputCount));
        List<FuelChoice> fuels = choices.stream().distinct()
                .filter(item -> !rejectedFuelItems.contains(item))
                .map(item -> fuelChoice(cooking, item, raw))
                .filter(java.util.Objects::nonNull)
                .toList();
        Comparator<FuelChoice> economical = Comparator
                .comparingLong(FuelChoice::acquisitionCost)
                .thenComparingLong(FuelChoice::waste)
                .thenComparingInt(FuelChoice::count)
                .thenComparingInt(choice -> fuelPriority(choice.item()))
                .thenComparing(choice -> BuiltInRegistries.ITEM.getKey(
                        choice.item()).toString());
        if (r.preference == SemanticCookTaskRecord.Preference.PRESERVE_RARE) {
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

    private FuelChoice fuelChoice(Candidate cooking, Item item, int raw) {
        int burn = AbstractFurnaceBlockEntity.getFuel().getOrDefault(item, 0);
        if (burn <= 0) return null;
        long neededTicks = (long) raw * cooking.recipe.getCookingTime();
        int needed = ceilDiv(neededTicks, burn);
        long cost = acquisitionCost(item, needed, Set.of(), 0);
        long waste = (long) needed * burn - neededTicks;
        return new FuelChoice(item, burn, needed, waste, cost);
    }

    /** Build a compact ordinary-crafting graph once; it is evidence, never an executor. */
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
                org.maiwithu.maicraft.core.Constants.LOG.debug(
                        "[maicraft-cook] skipped unusable acquisition-cost recipe {}: {}",
                        holder.id(), brokenRecipe.toString());
            }
        }
        indexed.replaceAll((item, routes) -> routes.stream()
                .sorted(Comparator.comparing(route -> route.recipeId().toString()))
                .toList());
        craftRoutesIndexed = true;
        return Map.copyOf(indexed);
    }

    private static List<IngredientGroup> ingredientGroups(CraftingRecipe recipe) {
        Map<List<Item>, Integer> uses = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            List<Item> alternatives = java.util.Arrays.stream(ingredient.getItems())
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

    /**
     * Estimate only routes the semantic acquisition child can actually execute. The result is a
     * relative preparation cost, not a promise: live child receipts remain authoritative and can
     * reject a candidate so RESOLVE tries the next finite plan.
     */
    private long acquisitionCost(
            Item item, int required, Set<Item> lineage, int depth) {
        if (required <= 0) return 0L;
        if (item == null || item == Items.AIR) return UNAVAILABLE_COST;
        int carried = PlayerInv.buildableCount(player.getInventory(), item);
        int missing = Math.max(0, required - carried);
        if (missing == 0) return 0L;

        long best = directSourceCost(item, missing);
        if (r.allowedSources.contains(SemanticAcquireTaskRecord.Source.STORAGE)
                && Ae2ResourceSupply.available()) {
            best = Math.min(best, addCost(
                    STORAGE_FALLBACK_COST, (long) missing * DIRECT_SOURCE_UNIT_COST));
        }
        if (depth >= MAX_ACQUISITION_DEPTH
                || !r.allowedSources.contains(SemanticAcquireTaskRecord.Source.CRAFT)
                || lineage.contains(item)) {
            return best;
        }

        List<CraftRoute> routes = craftRoutes.getOrDefault(item, List.of());
        if (routes.isEmpty()) return best;
        Set<Item> nextLineage = new LinkedHashSet<>(lineage);
        nextLineage.add(item);
        for (CraftRoute route : routes) {
            int batches = ceilDiv(missing, route.outputCount());
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

    private long directSourceCost(Item item, int missing) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId == null) return UNAVAILABLE_COST;
        SemanticSourceKnowledge.SourcePlan plan =
                SemanticSourceKnowledge.inferPlan(List.of(itemId));
        long best = UNAVAILABLE_COST;

        if (r.allowedSources.contains(SemanticAcquireTaskRecord.Source.MINE)
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
        if (r.allowHarm
                && r.allowedSources.contains(SemanticAcquireTaskRecord.Source.HUNT)
                && dimensionAllowed(plan, SemanticAcquireTaskRecord.Source.HUNT)
                && !plan.hint().entityTypeIds().isEmpty()) {
            best = Math.min(best, 10_000L + (long) missing * DIRECT_SOURCE_UNIT_COST);
        }
        if (r.allowedSources.contains(SemanticAcquireTaskRecord.Source.TRADE)
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

    private static long addCost(long... values) {
        long total = 0L;
        for (long value : values) {
            if (value >= UNAVAILABLE_COST || value < 0L) return UNAVAILABLE_COST;
            if (total > UNAVAILABLE_COST - value) return UNAVAILABLE_COST;
            total += value;
        }
        return total;
    }

    private TaskState prepare() {
        if (stationClaimed && ownedInputLoaded > 0) {
            if (stationPos == null) {
                return closeWithoutClaimingContents("station_target_lost",
                        "The exact claimed workstation position was lost while its batch was outstanding.",
                        FailureType.TARGET_LOST);
            }
            if (player.level().isLoaded(stationPos)
                    && !player.level().getBlockState(stationPos).is(candidate.device.block)) {
                return closeWithoutClaimingContents("station_replaced_while_cooking",
                        "The exact claimed workstation disappeared while its batch was outstanding.",
                        FailureType.TARGET_LOST);
            }
            openMode = OpenMode.RESUME_BATCH;
            nextCookCheckTick = player.level().getGameTime();
            phase = Phase.WAIT_CLOSED;
            return TaskState.RUNNING;
        }
        if (finishRequested) {
            phase = Phase.CLEANUP;
            return TaskState.RUNNING;
        }
        int raw = rawRemaining();
        int maxByOutput = Math.max(1, 64 / candidate.outputCount);
        int maxByFuel = Math.max(1,
                (int) Math.min(64L, 64L * fuelBurnTicks / candidate.recipe.getCookingTime()));
        batchRaw = Math.min(raw, Math.min(maxByOutput, maxByFuel));
        batchFuel = ceilDiv((long) batchRaw * candidate.recipe.getCookingTime(), fuelBurnTicks);
        if (candidate.input == fuel) {
            int sharedNeed = batchRaw + batchFuel;
            if (PlayerInv.buildableCount(player.getInventory(), candidate.input) < sharedNeed) {
                return acquire(candidate.input, sharedNeed, Purpose.ACQUIRE_INPUT);
            }
        } else {
            if (PlayerInv.buildableCount(player.getInventory(), candidate.input) < batchRaw) {
                return acquire(candidate.input, batchRaw, Purpose.ACQUIRE_INPUT);
            }
            if (PlayerInv.buildableCount(player.getInventory(), fuel) < batchFuel) {
                return acquire(fuel, batchFuel, Purpose.ACQUIRE_FUEL);
            }
        }
        if (openedMenu) {
            if (!(player.containerMenu instanceof AbstractFurnaceMenu) || !menuMatches()) {
                return menuLost();
            }
            phase = Phase.VALIDATE;
            return TaskState.RUNNING;
        }
        if (stationPos == null
                || !player.level().getBlockState(stationPos).is(candidate.device.block)) {
            stationPos = nearest(candidate.device.block, 32, 16);
        }
        if (stationPos == null) {
            if (PlayerInv.buildableCount(
                    player.getInventory(), candidate.device.block.asItem()) < 1) {
                return acquire(
                        candidate.device.block.asItem(), 1, Purpose.ACQUIRE_STATION);
            }
            BlockPos site = placementSite();
            if (site == null) {
                return failOrClean("no_safe_station_site",
                        "No safe loaded nearby cell can receive the required cooking workstation.",
                        FailureType.TERRAIN_BLOCKED);
            }
            stationPos = site;
            BuildTaskRecord.Target target = new BuildTaskRecord.Target(
                    candidate.device.block, candidate.device.block.asItem(), site,
                    BuiltInRegistries.BLOCK.getKey(candidate.device.block).toString(),
                    null, null, null).asItemPlace();
            return start(new BuildTaskRecord(
                    childId("place"), childDeadline(3L * 60L * 20L),
                    List.of(target), false, true, false), Purpose.PLACE_STATION);
        }
        if (!withinReach(stationPos)) {
            return start(new MoveToTaskRecord(
                    childId("move"), childDeadline(3L * 60L * 20L),
                    null, null, null,
                    BuiltInRegistries.BLOCK.getKey(candidate.device.block).toString(), false),
                    Purpose.MOVE_STATION);
        }
        phase = Phase.OPEN;
        return TaskState.RUNNING;
    }

    private TaskState acquire(Item item, int finalCount, Purpose purpose) {
        if (openedMenu) {
            replenishAfterClose = true;
            phase = Phase.CLEANUP;
            return TaskState.RUNNING;
        }
        SemanticAcquireTaskRecord child = new SemanticAcquireTaskRecord(
                childId("acquire"), childDeadline(8L * 60L * 20L),
                List.of(BuiltInRegistries.ITEM.getKey(item)), finalCount,
                r.allowedSources.stream()
                        .filter(source -> source != SemanticAcquireTaskRecord.Source.COOK)
                        .toList(),
                r.allowHarm, SemanticAcquireTaskRecord.SourceHint.empty(),
                r.protectedLabels, 16);
        return start(child, purpose);
    }

    private TaskState openStation() {
        if (stationPos == null || (player.level().isLoaded(stationPos)
                && !player.level().getBlockState(stationPos).is(candidate.device.block))) {
            if (stationClaimed && ownedInputLoaded > 0) {
                return closeWithoutClaimingContents("station_replaced_while_cooking",
                        "The exact claimed workstation was removed or replaced before it could be reopened.",
                        FailureType.TARGET_LOST);
            }
            stationPos = null;
            openAttemptStation = null;
            openAttempts = 0;
            phase = Phase.PREPARE;
            return TaskState.RUNNING;
        }
        if (!withinReach(stationPos)) {
            if (stationClaimed && ownedInputLoaded > 0) {
                nextCookCheckTick = player.level().getGameTime();
                openMode = OpenMode.RESUME_BATCH;
                phase = Phase.WAIT_CLOSED;
            } else {
                phase = Phase.PREPARE;
            }
            return TaskState.RUNNING;
        }
        if (!stationPos.equals(openAttemptStation)) {
            openAttemptStation = stationPos;
            openAttempts = 0;
        }
        openAttempts++;
        return start(new InteractAtTaskRecord(
                childId("open"), childDeadline(30L * 20L),
                MouseButton.RIGHT, stationPos, 0, null), Purpose.OPEN_STATION);
    }

    private TaskState waitMenu() {
        if (player.containerMenu instanceof AbstractFurnaceMenu) {
            if (!menuMatches()) {
                rememberFailure("wrong_station_menu",
                        "The opened workstation does not match the selected cooking recipe.",
                        FailureType.TARGET_LOST);
                outcomeUncertain |= effectsStarted;
                openedMenu = true;
                return start(new CloseMenuTaskRecord(
                        childId("wrong-menu-close"), childDeadline(30L * 20L)),
                        Purpose.ABANDON_CLOSE);
            }
            // This counter is a consecutive confirmation retry budget for one
            // open operation, not a lifetime cap across later cooking batches.
            openAttempts = 0;
            openedMenu = true;
            phase = openMode == OpenMode.RESUME_BATCH
                    ? Phase.RECONCILE : Phase.VALIDATE;
            return TaskState.RUNNING;
        }
        if (player.level().getGameTime() - waitMenuSince > 60L) {
            if (openAttempts < 2) {
                phase = Phase.OPEN;
                return TaskState.RUNNING;
            }
            if (player.containerMenu != player.inventoryMenu) {
                rememberFailure("station_open_unconfirmed",
                        "The selected workstation opened an unexpected synchronized menu.",
                        FailureType.TARGET_LOST);
                openedMenu = true;
                return start(new CloseMenuTaskRecord(
                        childId("unexpected-menu-close"), childDeadline(30L * 20L)),
                        Purpose.ABANDON_CLOSE);
            }
            return failOrClean("station_open_unconfirmed",
                    "The selected workstation did not open a synchronized furnace-family menu.",
                    FailureType.TARGET_LOST);
        }
        return TaskState.RUNNING;
    }

    private TaskState validateMenu() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null || !menuMatches()) return menuLost();
        ItemStack input = menu.getSlot(0).getItem();
        ItemStack fuelSlot = menu.getSlot(1).getItem();
        ItemStack result = menu.getSlot(2).getItem();
        if (!stationClaimed && (!input.isEmpty() || !fuelSlot.isEmpty()
                || !result.isEmpty() || data(menu, 0) > 0 || data(menu, 2) > 0)) {
            return failOrClean("station_in_use",
                    "The resolved workstation already contains items or active progress; "
                            + "MaiCraft will not claim or disturb it automatically.",
                    FailureType.UNKNOWN);
        }
        if (stationClaimed && (!input.isEmpty() || !result.isEmpty())) {
            return failOrClean("station_state_diverged",
                    "The claimed workstation contains unexpected input or output before a new batch.",
                    FailureType.UNKNOWN);
        }
        if (!fuelSlot.isEmpty() && !AbstractFurnaceBlockEntity.isFuel(fuelSlot)) {
            return failOrClean("station_fuel_diverged",
                    "The claimed workstation fuel slot contains a non-fuel item.",
                    FailureType.UNKNOWN);
        }
        stationClaimed = true;
        // Ownership begins only after LOAD_INPUT's native receipt confirms the deposit.
        ownedInputLoaded = 0;
        ownedOutputTaken = 0;
        takeInventoryBefore = 0;
        takeSlotCount = 0;
        phase = Phase.LOAD_INPUT;
        return TaskState.RUNNING;
    }

    private TaskState loadInput() {
        return transferTo(candidate.input, batchRaw, 0, Purpose.LOAD_INPUT);
    }

    private TaskState loadFuel() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null) return menuLost();
        int availableBurn = Math.max(0, data(menu, 0));
        ItemStack fuelSlot = menu.getSlot(1).getItem();
        if (!fuelSlot.isEmpty()) {
            availableBurn += fuelSlot.getCount()
                    * AbstractFurnaceBlockEntity.getFuel()
                            .getOrDefault(fuelSlot.getItem(), 0);
        }
        int neededTicks = batchRaw * candidate.recipe.getCookingTime();
        int toLoad = ceilDiv(
                Math.max(0L, (long) neededTicks - availableBurn), fuelBurnTicks);
        if (toLoad <= 0) {
            phase = Phase.CONFIRM_START;
            markCookEvidence();
            return TaskState.RUNNING;
        }
        return transferTo(fuel, toLoad, 1, Purpose.LOAD_FUEL);
    }

    /** Loading a slot is not proof that the server accepted and started the recipe. */
    private TaskState confirmStart() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null) return menuLost();
        ItemStack result = menu.getSlot(2).getItem();
        if (!result.isEmpty() && !result.is(BuiltInRegistries.ITEM.get(r.itemId))) {
            return closeWithoutClaimingContents("cooking_output_diverged",
                    "The synchronized workstation output no longer matches the selected recipe.",
                    FailureType.UNKNOWN);
        }
        ItemStack input = menu.getSlot(0).getItem();
        if (!input.isEmpty() && !input.is(candidate.input)) {
            return closeWithoutClaimingContents("cooking_input_diverged",
                    "The synchronized workstation input changed after MaiCraft loaded the batch.",
                    FailureType.UNKNOWN);
        }
        // Very short or already-hot recipes can finish before the first progress sample.
        if (!result.isEmpty() || input.getCount() < ownedInputLoaded) {
            phase = Phase.RECONCILE;
            return TaskState.RUNNING;
        }
        String evidence = data(menu, 0) + ":" + data(menu, 2) + ":" + data(menu, 3)
                + ":" + input.getCount() + ":" + result.getCount();
        if (!evidence.equals(lastCookEvidence)) {
            lastCookEvidence = evidence;
            lastCookEvidenceTick = player.level().getGameTime();
            renewCookProgressLease();
        }
        if (!input.isEmpty() && data(menu, 0) > 0
                && data(menu, 2) > 0 && data(menu, 3) > 0) {
            stationReturnStance = player.blockPosition();
            scheduleClosedCheck(menu);
            phase = Phase.CLOSE_WAIT;
            return TaskState.RUNNING;
        }
        long quietLimit = Math.max(200L, candidate.recipe.getCookingTime() + 100L);
        if (player.level().getGameTime() - lastCookEvidenceTick <= quietLimit) {
            return TaskState.RUNNING;
        }
        if (!input.isEmpty() && data(menu, 0) <= 0
                && menu.getSlot(1).getItem().isEmpty()) {
            return failOrClean("fuel_exhausted",
                    "The workstation never confirmed ignition and has no fuel remaining.",
                    FailureType.NO_MATERIAL);
        }
        return failOrClean("cooking_start_unconfirmed",
                "The loaded workstation did not produce synchronized ignition or progress evidence.",
                FailureType.UNKNOWN);
    }

    private TaskState closeForCookWait() {
        if (!(player.containerMenu instanceof AbstractFurnaceMenu) || !menuMatches()) {
            return menuLost();
        }
        return start(new CloseMenuTaskRecord(
                childId("wait-close"), childDeadline(30L * 20L)), Purpose.CLOSE_WAIT);
    }

    private void scheduleClosedCheck(AbstractFurnaceMenu menu) {
        int total = Math.max(1, data(menu, 3) > 0
                ? data(menu, 3) : candidate.recipe.getCookingTime());
        int progress = Math.max(0, Math.min(total - 1, data(menu, 2)));
        int remainingInputs = Math.max(1, menu.getSlot(0).getItem().getCount());
        long remaining = (long) total - progress
                + (long) (remainingInputs - 1) * total;
        nextCookCheckTick = player.level().getGameTime() + Math.max(2L, remaining + 2L);
        r.extendDeadlineTo(nextCookCheckTick + COOK_PROGRESS_LEASE_TICKS);
    }

    private TaskState waitClosed() {
        openedMenu = false;
        if (!closedWaitDue(player)) return TaskState.RUNNING;
        if (player.containerMenu != player.inventoryMenu) {
            // A human or another higher-level action currently owns a GUI. Never close it here.
            return TaskState.RUNNING;
        }
        if (stationPos == null) {
            return closeWithoutClaimingContents("station_target_lost",
                    "The exact workstation position was lost while its batch was cooking.",
                    FailureType.TARGET_LOST);
        }
        if (player.level().isLoaded(stationPos)
                && !player.level().getBlockState(stationPos).is(candidate.device.block)) {
            return closeWithoutClaimingContents("station_replaced_while_cooking",
                    "The claimed workstation was removed or replaced while its batch was cooking.",
                    FailureType.TARGET_LOST);
        }
        openMode = OpenMode.RESUME_BATCH;
        if (!withinReach(stationPos)) {
            BlockPos stance = stationReturnStance;
            if (stance == null) {
                return closeWithoutClaimingContents("station_return_stance_lost",
                        "No verified first-person stance was retained for the claimed workstation.",
                        FailureType.TARGET_LOST);
            }
            return start(new MoveToTaskRecord(
                    childId("return"), childDeadline(3L * 60L * 20L),
                    (double) stance.getX(), (double) stance.getY(), (double) stance.getZ(),
                    null, false), Purpose.MOVE_STATION);
        }
        phase = Phase.OPEN;
        return TaskState.RUNNING;
    }

    /** Reconcile only quantities this task can prove it put into an initially empty machine. */
    private TaskState reconcileBatch() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null || !menuMatches()) return menuLost();
        ItemStack input = menu.getSlot(0).getItem();
        ItemStack result = menu.getSlot(2).getItem();
        if (!input.isEmpty() && !input.is(candidate.input)) {
            return closeWithoutClaimingContents("workstation_input_interference",
                    "The claimed workstation now contains another input; MaiCraft left it untouched.",
                    FailureType.UNKNOWN);
        }
        if (!result.isEmpty() && !result.is(BuiltInRegistries.ITEM.get(r.itemId))) {
            return closeWithoutClaimingContents("workstation_output_interference",
                    "The claimed workstation now contains another output; MaiCraft left it untouched.",
                    FailureType.UNKNOWN);
        }
        int currentInput = input.isEmpty() ? 0 : input.getCount();
        if (currentInput > ownedInputLoaded) {
            return closeWithoutClaimingContents("workstation_input_inserted",
                    "More recipe input appeared than MaiCraft loaded; external automation or a player changed the batch.",
                    FailureType.UNKNOWN);
        }
        int consumed = ownedInputLoaded - currentInput;
        long expectedProduced = (long) consumed * candidate.outputCount;
        long observedOwned = (long) ownedOutputTaken + result.getCount();
        if (observedOwned != expectedProduced) {
            String relation = observedOwned < expectedProduced
                    ? "Some cooked output was removed, likely by a hopper or player"
                    : "Additional cooked output appeared from outside this batch";
            return closeWithoutClaimingContents("workstation_output_externally_changed",
                    relation + " (expected " + expectedProduced + " batch output, observed "
                            + observedOwned + "); MaiCraft did not take or replace anything.",
                    FailureType.UNKNOWN);
        }
        if (!result.isEmpty()) {
            takeInventoryBefore = outputCount();
            takeSlotCount = result.getCount();
            return startTransfer(List.of(
                    new ContainerTransferTaskRecord.Move(2, -1, 0)), Purpose.TAKE_OUTPUT);
        }
        if (failureMessage != null || finishRequested) {
            beginCleanupSnapshot(input);
            return TaskState.RUNNING;
        }
        if (currentInput == 0) {
            beginCleanupSnapshot(input);
            return TaskState.RUNNING;
        }
        if (data(menu, 0) > 0 && data(menu, 3) > 0) {
            stationReturnStance = player.blockPosition();
            scheduleClosedCheck(menu);
            phase = Phase.CLOSE_WAIT;
            return TaskState.RUNNING;
        }
        if (menu.getSlot(1).getItem().isEmpty()) {
            rememberFailure("fuel_exhausted",
                    "The verified batch still has input but no burn time or fuel remaining.",
                    FailureType.NO_MATERIAL);
            beginCleanupSnapshot(input);
            return TaskState.RUNNING;
        }
        rememberFailure("cooking_stalled",
                "The verified batch has input and fuel but no synchronized cooking progress.",
                FailureType.UNKNOWN);
        beginCleanupSnapshot(input);
        return TaskState.RUNNING;
    }

    private TaskState verifyOutputTake() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null || !menuMatches()) return menuLost();
        int inventoryGain = outputCount() - takeInventoryBefore;
        if (takeSlotCount <= 0 || inventoryGain != takeSlotCount) {
            outcomeUncertain = true;
            return closeWithoutClaimingContents("cooking_output_take_unverified",
                    "The result-slot click settled without the exact expected main-inventory gain; "
                            + "MaiCraft stopped before touching the remaining workstation contents.",
                    FailureType.UNKNOWN);
        }
        ownedOutputTaken += takeSlotCount;
        takeInventoryBefore = 0;
        takeSlotCount = 0;
        phase = Phase.RECONCILE;
        return TaskState.RUNNING;
    }

    private TaskState transferTo(
            Item item, int count, int destination, Purpose purpose) {
        List<ContainerTransferTaskRecord.Move> moves = new ArrayList<>();
        int remaining = count;
        for (int slot = 3;
                slot < player.containerMenu.slots.size() && remaining > 0; slot++) {
            ItemStack stack = player.containerMenu.getSlot(slot).getItem();
            if (!stack.is(item)) continue;
            int moved = Math.min(remaining, stack.getCount());
            ContainerTransferTaskRecord.DestinationMode destinationMode =
                    purpose == Purpose.LOAD_INPUT || purpose == Purpose.LOAD_FUEL
                            ? ContainerTransferTaskRecord.DestinationMode.MAY_MUTATE_AFTER_DEPOSIT
                            : ContainerTransferTaskRecord.DestinationMode.EXACT;
            moves.add(new ContainerTransferTaskRecord.Move(
                    slot, destination, moved, destinationMode));
            remaining -= moved;
        }
        if (remaining > 0) {
            return failOrClean(
                    purpose == Purpose.LOAD_INPUT
                            ? "input_inventory_changed" : "fuel_inventory_changed",
                    "Prepared cooking resources are no longer present in the synchronized inventory.",
                    FailureType.NO_MATERIAL);
        }
        return startTransfer(moves, purpose);
    }

    private TaskState startTransfer(
            List<ContainerTransferTaskRecord.Move> moves, Purpose purpose) {
        if (moves.isEmpty()) {
            return failOrClean("empty_menu_transaction",
                    "No synchronized menu transfer could be planned.", FailureType.INTERNAL);
        }
        return start(new ContainerTransferTaskRecord(
                childId("menu"), childDeadline(2L * 60L * 20L),
                player.containerMenu.containerId, moves), purpose);
    }

    private TaskState cleanupMachine() {
        if (!openedMenu || !(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            openedMenu = false;
            return finishCleanup();
        }
        if (!menuMatches()) return menuLost();
        if (!cleanupSnapshotReady) {
            // A failure may have routed here while the furnace was still open. Re-enter the
            // ownership ledger before touching any slot instead of blindly "cleaning" it.
            phase = Phase.RECONCILE;
            return TaskState.RUNNING;
        }
        ItemStack result = menu.getSlot(2).getItem();
        ItemStack input = menu.getSlot(0).getItem();
        if (!result.isEmpty() || !sameStack(input, cleanupInputExpected)) {
            // A smelt may complete between reconciliation and cleanup. Reconcile again so a
            // legitimate new result is taken through TAKE_OUTPUT -> VERIFY_OUTPUT and any
            // external mutation is rejected by the conservation equation.
            cleanupSnapshotReady = false;
            cleanupInputExpected = ItemStack.EMPTY;
            phase = Phase.RECONCILE;
            return TaskState.RUNNING;
        }
        if (!cleanupInputExpected.isEmpty()) {
            cleanupInventoryBefore = PlayerInv.buildableCount(
                    player.getInventory(), candidate.input);
            cleanupTransferCount = cleanupInputExpected.getCount();
            return startTransfer(List.of(
                    new ContainerTransferTaskRecord.Move(
                            0, -1, cleanupTransferCount)), Purpose.CLEAN_INPUT);
        }
        // Remaining fuel stays in the machine. After closing the menu its identity cannot be
        // distinguished from same-kind fuel inserted by a player or hopper, so reclaiming it
        // would be an ownership guess.
        return start(new CloseMenuTaskRecord(
                childId("close"), childDeadline(30L * 20L)), Purpose.CLOSE);
    }

    private void beginCleanupSnapshot(ItemStack input) {
        cleanupInputExpected = input.copy();
        cleanupSnapshotReady = true;
        phase = Phase.CLEANUP;
    }

    private TaskState verifyCleanupInput() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null || !menuMatches()) return menuLost();
        int inventoryGain = PlayerInv.buildableCount(
                player.getInventory(), candidate.input) - cleanupInventoryBefore;
        if (cleanupTransferCount <= 0 || inventoryGain != cleanupTransferCount
                || !menu.getSlot(0).getItem().isEmpty()) {
            outcomeUncertain = true;
            return closeWithoutClaimingContents("cooking_input_return_unverified",
                    "The input-slot return settled without the exact expected main-inventory "
                            + "gain; MaiCraft stopped before touching any remaining contents.",
                    FailureType.UNKNOWN);
        }
        cleanupInventoryBefore = 0;
        cleanupTransferCount = 0;
        cleanupInputExpected = ItemStack.EMPTY;
        phase = Phase.CLEANUP;
        return TaskState.RUNNING;
    }

    private TaskState finishCleanup() {
        if (failureMessage != null) {
            phase = Phase.COMPLETE;
            return TaskState.RUNNING;
        }
        if (finishRequested || outputCount() >= r.count) {
            finishRequested = true;
            phase = Phase.COMPLETE;
            return TaskState.RUNNING;
        }
        replenishAfterClose = false;
        openMode = OpenMode.NEW_BATCH;
        ownedInputLoaded = 0;
        ownedOutputTaken = 0;
        takeInventoryBefore = 0;
        takeSlotCount = 0;
        cleanupInventoryBefore = 0;
        cleanupTransferCount = 0;
        cleanupSnapshotReady = false;
        cleanupInputExpected = ItemStack.EMPTY;
        phase = Phase.PREPARE;
        return TaskState.RUNNING;
    }

    private TaskState tickChild() {
        TaskState terminal;
        if (activeRecord != null
                && player.level().getGameTime() >= activeRecord.getDeadlineGameTime()) {
            // Nested tasks are not driven by TaskSlot, so enforce the child's
            // own no-progress lease here and let its timeout receipt flow back
            // through the semantic cooking failure instead of timing out the
            // parent first with no prerequisite context.
            activeChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(activeChild);
        }
        if (terminal == null) {
            // Long prerequisite/navigation children own their liveness evidence.
            // Carry their renewed no-progress lease into this semantic parent so
            // the parent cannot time out while the child is still advancing.
            if (activeRecord != null) {
                extendParentPast(activeRecord.getDeadlineGameTime());
            }
            return TaskState.RUNNING;
        }
        TaskResult result = activeChild.result(terminal);
        Purpose purpose = activePurpose;
        activeChild = null;
        activeRecord = null;
        activePurpose = null;
        if (terminal != TaskState.SUCCESS || result == null || !result.success()) {
            if (purpose == Purpose.ABANDON_CLOSE) {
                outcomeUncertain = true;
                openedMenu = player.containerMenu != player.inventoryMenu;
                phase = Phase.COMPLETE;
                return TaskState.RUNNING;
            }
            if (purpose == Purpose.ACQUIRE_INPUT || purpose == Purpose.ACQUIRE_FUEL
                    || purpose == Purpose.ACQUIRE_STATION) {
                prerequisiteFailure = semanticPrerequisiteFailure(result);
                if (retryAnotherPreparationPlan(purpose, result)) {
                    return TaskState.RUNNING;
                }
            }
            failedChildStage = purpose == null
                    ? "unknown" : purpose.name().toLowerCase();
            failedChildMessage = result == null ? "child returned no result" : result.message();
            if (purpose == Purpose.LOAD_INPUT || purpose == Purpose.LOAD_FUEL
                    || purpose == Purpose.TAKE_OUTPUT || purpose == Purpose.CLEAN_INPUT) {
                outcomeUncertain = true;
                return closeWithoutClaimingContents(
                        childFailureCode(purpose), childFailureMessage(purpose), lastFailure());
            }
            if (purpose == Purpose.CLOSE_WAIT || purpose == Purpose.CLOSE) {
                rememberFailure(
                        childFailureCode(purpose), childFailureMessage(purpose), lastFailure());
                outcomeUncertain |= effectsStarted;
                openedMenu = player.containerMenu != player.inventoryMenu;
                phase = Phase.COMPLETE;
                return TaskState.RUNNING;
            }
            return failOrClean(
                    childFailureCode(purpose), childFailureMessage(purpose), lastFailure());
        }
        switch (purpose) {
            case ACQUIRE_INPUT, ACQUIRE_FUEL, ACQUIRE_STATION -> phase = Phase.PREPARE;
            case PLACE_STATION -> {
                stationPlaced = true;
                if (stationPos == null
                        || !player.level().getBlockState(stationPos)
                                .is(candidate.device.block)) {
                    return failOrClean("station_placement_unconfirmed",
                            "The first-person build task did not leave the required workstation.",
                            FailureType.UNKNOWN);
                }
                phase = Phase.PREPARE;
            }
            case MOVE_STATION -> {
                if (openMode == OpenMode.RESUME_BATCH) {
                    if (stationPos == null || !player.level().isLoaded(stationPos)
                            || !player.level().getBlockState(stationPos)
                                    .is(candidate.device.block)) {
                        return closeWithoutClaimingContents("station_target_lost",
                                "The exact claimed workstation was not present after returning to it.",
                                FailureType.TARGET_LOST);
                    }
                } else {
                    stationPos = nearest(candidate.device.block, 8, 8);
                }
                if (stationPos == null) {
                    return failOrClean("station_target_lost",
                            "The workstation was not visible after approach completed.",
                            FailureType.TARGET_LOST);
                }
                phase = Phase.OPEN;
            }
            case OPEN_STATION -> {
                waitMenuSince = player.level().getGameTime();
                phase = Phase.WAIT_MENU;
            }
            case LOAD_INPUT -> {
                effectsStarted = true;
                ownedInputLoaded = batchRaw;
                phase = Phase.LOAD_FUEL;
            }
            case LOAD_FUEL -> {
                effectsStarted = true;
                phase = Phase.CONFIRM_START;
                markCookEvidence();
            }
            case TAKE_OUTPUT -> phase = Phase.VERIFY_OUTPUT;
            case CLOSE_WAIT -> {
                openedMenu = false;
                closedWaitStartedTick = player.level().getGameTime();
                openMode = OpenMode.RESUME_BATCH;
                phase = Phase.WAIT_CLOSED;
            }
            case CLEAN_INPUT -> phase = Phase.VERIFY_CLEAN_INPUT;
            case CLOSE -> {
                openedMenu = false;
                return finishCleanup();
            }
            case ABANDON_CLOSE -> {
                openedMenu = false;
                phase = Phase.COMPLETE;
            }
        }
        routeFinishRequest();
        return TaskState.RUNNING;
    }

    private static Map<String, Object> semanticPrerequisiteFailure(TaskResult result) {
        if (result == null || result.data() == null) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of(
                "failure_type", "failure_code", "requires_decision",
                "requires_narration", "outcome_uncertain", "status",
                "recovery_options", "issues")) {
            Object value = result.data().get(key);
            if (value != null) safe.put(key, value);
        }
        return Map.copyOf(safe);
    }

    /** A pre-effect prerequisite failure rejects only that finite plan and re-runs cost selection. */
    private boolean retryAnotherPreparationPlan(Purpose purpose, TaskResult result) {
        if (effectsStarted || openedMenu || uncertain(result)) return false;
        switch (purpose) {
            case ACQUIRE_INPUT -> {
                if (candidate == null) return false;
                rejectedInputCandidates.add(candidateKey(candidate));
            }
            case ACQUIRE_FUEL -> {
                if (fuel == null) return false;
                rejectedFuelItems.add(fuel);
            }
            case ACQUIRE_STATION -> {
                if (candidate == null) return false;
                rejectedDevices.add(candidate.device());
            }
            default -> {
                return false;
            }
        }
        if (planningAttempts.size() < 32) {
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("failed_prerequisite", purpose.name().toLowerCase());
            if (candidate != null) {
                attempt.put("recipe_id", candidate.recipeId().toString());
                attempt.put("input_item_id", BuiltInRegistries.ITEM.getKey(
                        candidate.input()).toString());
                attempt.put("device", BuiltInRegistries.BLOCK.getKey(
                        candidate.device().block).toString());
            }
            if (fuel != null) {
                attempt.put("fuel_item_id", BuiltInRegistries.ITEM.getKey(fuel).toString());
            }
            if (result != null && result.message() != null) {
                attempt.put("child_message", result.message());
            }
            planningAttempts.add(Map.copyOf(attempt));
        }
        candidate = null;
        fuel = null;
        fuelBurnTicks = 0;
        batchRaw = 0;
        batchFuel = 0;
        stationPos = null;
        phase = Phase.RESOLVE;
        return true;
    }

    private static boolean uncertain(TaskResult result) {
        if (result == null || result.data() == null) return false;
        Object uncertain = result.data().get("outcome_uncertain");
        return Boolean.TRUE.equals(uncertain)
                || "uncertain".equals(String.valueOf(result.data().get("status")));
    }

    private static String candidateKey(Candidate candidate) {
        return candidate.recipeId() + "|" + candidate.device().name()
                + "|" + BuiltInRegistries.ITEM.getKey(candidate.input());
    }

    private TaskState start(TaskRecord record, Purpose purpose) {
        activeRecord = record;
        activeChild = TaskFactory.create(player, record);
        activePurpose = purpose;
        extendParentPast(record.getDeadlineGameTime());
        return TaskState.RUNNING;
    }

    private TaskState failOrClean(String code, String message, FailureType type) {
        rememberFailure(code, message, type);
        if (openedMenu && player.containerMenu instanceof AbstractFurnaceMenu) {
            if (stationClaimed && ownedInputLoaded > 0) {
                cleanupSnapshotReady = false;
                cleanupInputExpected = ItemStack.EMPTY;
                phase = Phase.RECONCILE;
                return TaskState.RUNNING;
            }
            return closeWithoutClaimingContents(code, message, type);
        }
        phase = Phase.COMPLETE;
        return TaskState.RUNNING;
    }

    private void rememberFailure(String code, String message, FailureType type) {
        if (failureMessage == null) {
            failureCode = code;
            failureMessage = message;
            failureType = type == null ? FailureType.UNKNOWN : type;
        }
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        if (left.isEmpty() || right.isEmpty()) return left.isEmpty() && right.isEmpty();
        return left.getCount() == right.getCount()
                && ItemStack.isSameItemSameComponents(left, right);
    }

    /** Close a menu after ownership evidence diverged, without moving any machine slot. */
    private TaskState closeWithoutClaimingContents(
            String code, String message, FailureType type) {
        rememberFailure(code, message, type);
        outcomeUncertain |= effectsStarted;
        cleanupSnapshotReady = false;
        cleanupInputExpected = ItemStack.EMPTY;
        cleanupInventoryBefore = 0;
        cleanupTransferCount = 0;
        if (openedMenu && player.containerMenu instanceof AbstractFurnaceMenu
                && menuMatches()) {
            return start(new CloseMenuTaskRecord(
                    childId("abandon-close"), childDeadline(30L * 20L)),
                    Purpose.ABANDON_CLOSE);
        }
        openedMenu = false;
        phase = Phase.COMPLETE;
        return TaskState.RUNNING;
    }

    private TaskState menuLost() {
        outcomeUncertain |= effectsStarted;
        openedMenu = false;
        String message = effectsStarted
                ? "The synchronized workstation menu changed after cooking effects began; "
                        + "blind retry is unsafe."
                : "The synchronized workstation menu changed before cooking began.";
        rememberFailure("cooking_menu_lost", message, FailureType.TARGET_LOST);
        phase = Phase.COMPLETE;
        return TaskState.RUNNING;
    }

    private String childFailureCode(Purpose purpose) {
        return switch (purpose) {
            case ACQUIRE_INPUT -> "missing_recipe_input";
            case ACQUIRE_FUEL -> "missing_allowed_fuel";
            case ACQUIRE_STATION -> "missing_workstation";
            case PLACE_STATION -> "station_placement_failed";
            case MOVE_STATION -> "station_unreachable";
            case OPEN_STATION -> "station_open_failed";
            case CLOSE -> "menu_close_unconfirmed";
            default -> "menu_transaction_unconfirmed";
        };
    }

    private String childFailureMessage(Purpose purpose) {
        return switch (purpose) {
            case ACQUIRE_INPUT ->
                    "Could not obtain enough selected recipe input from allowed_sources.";
            case ACQUIRE_FUEL ->
                    "Could not obtain enough allowed fuel from allowed_sources.";
            case ACQUIRE_STATION ->
                    "Could not obtain the selected cooking workstation from allowed_sources.";
            case PLACE_STATION ->
                    "Could not place the selected workstation through first-person building.";
            case MOVE_STATION ->
                    "Could not approach the selected workstation without altering terrain.";
            case OPEN_STATION ->
                    "Could not open the selected workstation through first-person interaction.";
            case CLOSE -> "The workstation menu close was not confirmed.";
            default -> "A synchronized workstation inventory transaction was not confirmed.";
        };
    }

    private AbstractFurnaceMenu furnaceMenu() {
        return player.containerMenu instanceof AbstractFurnaceMenu menu ? menu : null;
    }

    private boolean menuMatches() {
        return switch (candidate.device) {
            case FURNACE -> player.containerMenu instanceof FurnaceMenu;
            case BLAST_FURNACE -> player.containerMenu instanceof BlastFurnaceMenu;
            case SMOKER -> player.containerMenu instanceof SmokerMenu;
            case CAMPFIRE -> false;
        };
    }

    private int data(AbstractFurnaceMenu menu, int index) {
        List<net.minecraft.world.inventory.DataSlot> data =
                ((MenuDataSlotsAccessor) (Object) menu).maicraft$dataSlots();
        return index >= 0 && index < data.size() ? data.get(index).get() : 0;
    }

    private void markCookEvidence() {
        AbstractFurnaceMenu menu = furnaceMenu();
        lastCookEvidenceTick = player.level().getGameTime();
        lastCookEvidence = menu == null ? "" : data(menu, 0) + ":" + data(menu, 2)
                + ":" + data(menu, 3) + ":" + menu.getSlot(0).getItem().getCount()
                + ":" + menu.getSlot(2).getItem().getCount();
        renewCookProgressLease();
    }

    private void renewCookProgressLease() {
        r.extendDeadlineTo(player.level().getGameTime() + COOK_PROGRESS_LEASE_TICKS);
    }

    private boolean stationReady(Device device) {
        return nearbyBlockDistances.containsKey(device.block)
                || PlayerInv.buildableCount(
                        player.getInventory(), device.block.asItem()) > 0;
    }

    private BlockPos nearest(Block block, int horizontal, int vertical) {
        BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -horizontal; dx <= horizontal; dx++) {
            for (int dz = -horizontal; dz <= horizontal; dz++) {
                for (int dy = -vertical; dy <= vertical; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!player.level().isLoaded(cursor)
                            || !player.level().getBlockState(cursor).is(block)) continue;
                    double distance = origin.distSqr(cursor);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    private BlockPos placementSite() {
        BlockPos origin = player.blockPosition();
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos cell = origin.offset(dx, dy, dz);
                        if (validSite(cell)) return cell;
                    }
                    int surfaceY = ClientSurfaceHeight.motionBlockingNoLeaves(
                            player.clientLevel, origin.getX() + dx, origin.getZ() + dz);
                    BlockPos surface = new BlockPos(
                            origin.getX() + dx, surfaceY, origin.getZ() + dz);
                    if (validSite(surface)) return surface;
                }
            }
        }
        return null;
    }

    private boolean validSite(BlockPos cell) {
        if (!player.level().isLoaded(cell)
                || !player.level().getBlockState(cell).canBeReplaced()
                || player.getBoundingBox().intersects(new AABB(cell))) return false;
        BlockPos support = cell.below();
        return player.level().isLoaded(support)
                && player.level().getBlockState(support)
                        .isFaceSturdy(player.level(), support, Direction.UP);
    }

    private boolean withinReach(BlockPos pos) {
        return player.distanceToSqr(Vec3.atCenterOf(pos)) <= 4.25D * 4.25D;
    }

    private int rawRemaining() {
        return rawRemaining(candidate);
    }

    private int rawRemaining(Candidate cooking) {
        int missing = Math.max(0, r.count - outputCount());
        return Math.max(1, ceilDiv(missing, cooking.outputCount));
    }

    private int outputCount() {
        return PlayerInv.buildableCount(
                player.getInventory(), BuiltInRegistries.ITEM.get(r.itemId));
    }

    private String childId(String label) {
        return r.getToolCallId() + "-cook-" + label + "-" + (++childSerial);
    }

    private long childDeadline(long ticks) {
        // `ticks` is this child's initial no-progress lease, not a slice of the
        // parent's original total duration. Healthy children may renew it and
        // tickChild propagates that renewal back to the parent.
        long lease = player.level().getGameTime() + ticks;
        extendParentPast(lease);
        return lease;
    }

    private void extendParentPast(long childDeadline) {
        // One extra tick lets the parent observe and report a child lease expiry;
        // equality would make TaskSlot time out the parent before tickChild runs.
        r.extendDeadlineTo(childDeadline == Long.MAX_VALUE
                ? Long.MAX_VALUE : childDeadline + 1L);
    }

    @Override
    public boolean mustSettleBeforeSatisfiedCancellation() {
        // Once a synchronized workstation mutation has started, an inventory fact can become
        // visible before its receipt and the close/cleanup tail settle. Let the semantic parent
        // keep ticking this child through that terminal tail instead of cancelling on the first
        // optimistic inventory frame and leaving a cursor stack or open workstation behind.
        // A semantic parent checks its inventory fact before advancing this child. Give a
        // terminal-ready cook one final tick so SUCCESS/FAILED is recorded truthfully instead of
        // being rewritten as CANCELLED merely because the output already reached the inventory.
        if (phase == Phase.COMPLETE) return true;
        if (stationClaimed && ownedInputLoaded > 0) return true;
        if (phase == Phase.CLEANUP || (openedMenu && effectsStarted)) return true;
        if (activeChild == null || activePurpose == null) return false;
        return switch (activePurpose) {
            case LOAD_INPUT, LOAD_FUEL, TAKE_OUTPUT,
                    CLOSE_WAIT, CLEAN_INPUT,
                    CLOSE, ABANDON_CLOSE -> true;
            default -> false;
        };
    }

    private static int ceilDiv(long numerator, long denominator) {
        if (numerator <= 0L) return 0;
        return (int) Math.min(
                Integer.MAX_VALUE, (numerator + denominator - 1L) / denominator);
    }

    @Override
    protected void cleanup() {
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            activeChild = null;
            activeRecord = null;
            activePurpose = null;
        }
        if (openedMenu && player.containerMenu != player.inventoryMenu) {
            try {
                var context = ClientRuntime.requireContext(player);
                context.menus().closeForTaskBoundary(
                        context, 20,
                        "the cooking task ended before its active menu transaction settled");
            } catch (RuntimeException closeFailure) {
                outcomeUncertain = true;
                try {
                    player.closeContainer();
                } catch (RuntimeException ignored) { }
            }
        }
        openedMenu = false;
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        int observed = outputCount();
        data.put("goal", "final_main_inventory_count");
        data.put("item_id", r.itemId.toString());
        data.put("required_final_count", r.count);
        data.put("initial_count", initialOutputCount);
        data.put("observed_final_count", observed);
        data.put("goal_satisfied", observed >= r.count);
        data.put("recipe_preference", r.preference.name().toLowerCase());
        data.put("allow_harm", r.allowHarm);
        data.put("supported_execution_devices",
                List.of("minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker"));
        data.put("campfire_execution_supported", false);
        if (candidate != null) {
            data.put("recipe_id", candidate.recipeId.toString());
            data.put("device", BuiltInRegistries.BLOCK.getKey(candidate.device.block).toString());
            data.put("input_item_id", BuiltInRegistries.ITEM.getKey(candidate.input).toString());
            data.put("recipe_output_count", candidate.outputCount);
        }
        if (fuel != null) {
            data.put("fuel_item_id", BuiltInRegistries.ITEM.getKey(fuel).toString());
        }
        data.put("station_placed", stationPlaced);
        data.put("outcome_uncertain", outcomeUncertain);
        if (failedChildStage != null) {
            data.put("failed_child_stage", failedChildStage);
        }
        if (failedChildMessage != null) {
            data.put("failed_child_message", failedChildMessage);
        }
        if (!prerequisiteFailure.isEmpty()) {
            data.put("prerequisite_failure", prerequisiteFailure);
        }
        if (!planningAttempts.isEmpty()) {
            data.put("preparation_plan_failures", List.copyOf(planningAttempts));
        }
        if (failureCode != null) {
            data.put("decision", Map.of(
                    "required", true,
                    "reason_code", failureCode,
                    "recovery_options", List.of(
                            "change recipe_preference",
                            "expand allowed_sources or allowed_fuels",
                            "provide or clear a compatible workstation",
                            "retry after checking the synchronized workstation state")));
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return "cooked " + r.itemId + " until the main inventory held at least "
                + r.count + " item(s), confirmed by live inventory state";
    }

    @Override
    protected String timeoutMessage() {
        return "cooking timed out; observed " + outputCount() + " of required final "
                + r.count + " in the main inventory";
    }

    @Override
    protected String cancelledMessage() {
        return "cooking interrupted; observed " + outputCount() + " of required final "
                + r.count + " in the main inventory";
    }
}
