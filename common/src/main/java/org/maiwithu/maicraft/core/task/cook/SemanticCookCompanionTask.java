// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.mixin.MenuDataSlotsAccessor;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.menu.CloseMenuTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Receipt-driven furnace-family executor; all concrete operations stay internal. */
public final class SemanticCookCompanionTask
        extends AbstractCompanionTask<SemanticCookTaskRecord> {
    private enum Phase { RESOLVE, PREPARE, OPEN, WAIT_MENU, VALIDATE, LOAD_INPUT,
        LOAD_FUEL, WAIT_COOK, CLEANUP, COMPLETE }
    private enum Purpose { ACQUIRE_INPUT, ACQUIRE_FUEL, ACQUIRE_STATION, PLACE_STATION,
        MOVE_STATION, OPEN_STATION, LOAD_INPUT, LOAD_FUEL, TAKE_OUTPUT,
        CLEAN_RESULT, CLEAN_INPUT, CLEAN_FUEL, CLOSE }
    private enum Device {
        FURNACE(Blocks.FURNACE), BLAST_FURNACE(Blocks.BLAST_FURNACE),
        SMOKER(Blocks.SMOKER), CAMPFIRE(Blocks.CAMPFIRE);
        final Block block;
        Device(Block block) { this.block = block; }
    }
    private record Candidate(
            ResourceLocation recipeId, AbstractCookingRecipe recipe, Device device,
            Item input, int outputCount) {}

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
    private long waitMenuSince;
    private long lastCookEvidenceTick;
    private String lastCookEvidence = "";
    private int openAttempts;
    private Task activeChild;
    private TaskRecord activeRecord;
    private Purpose activePurpose;
    private int childSerial;
    private int initialOutputCount;
    private String failureCode;
    private String failureMessage;
    private FailureType failureType = FailureType.UNKNOWN;
    private boolean outcomeUncertain;
    private Map<String, Object> prerequisiteFailure = Map.of();

    public SemanticCookCompanionTask(LocalPlayer player, SemanticCookTaskRecord record) {
        super(player, record);
    }

    @Override protected void onStart() { initialOutputCount = outputCount(); }

    @Override
    protected TaskState onTick() {
        if (outputCount() >= r.count && !finishRequested) {
            finishRequested = true;
            if (activeChild == null) phase = Phase.CLEANUP;
        }
        if (activeChild != null) return tickChild();
        if (phase == Phase.COMPLETE) {
            if (failureMessage != null) {
                fail(failureMessage, failureType);
                return TaskState.FAILED;
            }
            return TaskState.SUCCESS;
        }
        if (finishRequested && phase != Phase.CLEANUP) phase = Phase.CLEANUP;
        return switch (phase) {
            case RESOLVE -> resolve();
            case PREPARE -> prepare();
            case OPEN -> openStation();
            case WAIT_MENU -> waitMenu();
            case VALIDATE -> validateMenu();
            case LOAD_INPUT -> loadInput();
            case LOAD_FUEL -> loadFuel();
            case WAIT_COOK -> waitCook();
            case CLEANUP -> cleanupMachine();
            case COMPLETE -> TaskState.SUCCESS;
        };
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
        candidates.removeIf(c -> c.device == Device.CAMPFIRE || !preferred(c.device));
        if (candidates.isEmpty()) {
            return failOrClean("no_preferred_recipe",
                    "No recipe matching recipe_preference produces " + r.itemId + ".",
                    FailureType.NO_MATERIAL);
        }
        candidate = candidates.stream().min(candidateComparator()).orElseThrow();
        fuel = chooseFuel();
        fuelBurnTicks = fuel == null ? 0
                : AbstractFurnaceBlockEntity.getFuel().getOrDefault(fuel, 0);
        if (fuelBurnTicks <= 0) {
            return failOrClean("no_allowed_fuel",
                    "No allowed ordinary furnace fuel can be selected.", FailureType.NO_MATERIAL);
        }
        phase = Phase.PREPARE;
        return TaskState.RUNNING;
    }

    private List<Candidate> candidates() {
        List<Candidate> result = new ArrayList<>();
        var manager = ClientRuntime.requireContext(player).connection().getRecipeManager();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (!(holder.value() instanceof AbstractCookingRecipe cooking)) continue;
            ItemStack output = cooking.getResultItem(player.level().registryAccess());
            if (output.isEmpty() || !output.is(BuiltInRegistries.ITEM.get(r.itemId))) continue;
            Device device = device(cooking.getType());
            if (device == null || cooking.getIngredients().isEmpty()) continue;
            LinkedHashSet<Item> inputs = new LinkedHashSet<>();
            for (ItemStack stack : cooking.getIngredients().getFirst().getItems()) {
                if (!stack.isEmpty()) inputs.add(stack.getItem());
            }
            for (Item input : inputs) result.add(new Candidate(
                    holder.id(), cooking, device, input, Math.max(1, output.getCount())));
        }
        return result;
    }

    private Comparator<Candidate> candidateComparator() {
        Comparator<Candidate> ready = Comparator
                .comparingInt((Candidate c) -> stationReady(c.device) ? 0 : 1)
                .thenComparingInt(c -> PlayerInv.buildableCount(
                        player.getInventory(), c.input) > 0 ? 0 : 1)
                .thenComparing(Comparator.comparingInt((Candidate c) ->
                        PlayerInv.buildableCount(player.getInventory(), c.input)).reversed());
        Comparator<Candidate> speed = Comparator.comparingInt(c -> c.recipe.getCookingTime());
        Comparator<Candidate> stable = Comparator
                .comparing((Candidate c) -> c.device.ordinal())
                .thenComparing(c -> BuiltInRegistries.ITEM.getKey(c.input).toString())
                .thenComparing(c -> c.recipeId.toString());
        return r.preference == SemanticCookTaskRecord.Preference.FASTEST
                ? speed.thenComparing(ready).thenComparing(stable)
                : ready.thenComparing(speed).thenComparing(stable);
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

    private Item chooseFuel() {
        List<Item> choices = new ArrayList<>();
        if (!r.allowedFuelIds.isEmpty()) {
            r.allowedFuelIds.forEach(id -> choices.add(BuiltInRegistries.ITEM.get(id)));
        } else {
            for (Item item : AbstractFurnaceBlockEntity.getFuel().keySet()) {
                if (safeDefaultFuel(item)) choices.add(item);
            }
        }
        int raw = Math.min(rawRemaining(), Math.max(1, 64 / candidate.outputCount));
        return choices.stream().distinct().min(Comparator
                .comparingInt((Item item) -> fuelMissing(item, raw))
                .thenComparingInt(this::fuelPriority)
                .thenComparingInt(item -> fuelWaste(item, raw))
                .thenComparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .orElse(null);
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

    private int fuelMissing(Item item, int raw) {
        int burn = AbstractFurnaceBlockEntity.getFuel().getOrDefault(item, 0);
        if (burn <= 0) return Integer.MAX_VALUE;
        int needed = ceilDiv((long) raw * candidate.recipe.getCookingTime(), burn);
        return Math.max(0, needed - PlayerInv.buildableCount(player.getInventory(), item));
    }

    private int fuelWaste(Item item, int raw) {
        int burn = AbstractFurnaceBlockEntity.getFuel().getOrDefault(item, 0);
        if (burn <= 0) return Integer.MAX_VALUE;
        int needed = ceilDiv((long) raw * candidate.recipe.getCookingTime(), burn);
        return needed * burn - raw * candidate.recipe.getCookingTime();
    }

    private TaskState prepare() {
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
