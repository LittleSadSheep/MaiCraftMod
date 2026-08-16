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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.mixin.MenuDataSlotsAccessor;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
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
    /**
     * Furnace progress is live server evidence, so each observed change renews a
     * no-progress lease instead of spending one fixed wall-clock budget for the
     * whole (possibly multi-batch, Mod-recipe) cooking goal.
     */
    private static final long COOK_PROGRESS_LEASE_TICKS = 30L * 20L;
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
        if (stationPos == null
                || !player.level().getBlockState(stationPos).is(candidate.device.block)) {
            stationPos = null;
            openAttemptStation = null;
            openAttempts = 0;
            phase = Phase.PREPARE;
            return TaskState.RUNNING;
        }
        if (!withinReach(stationPos)) {
            phase = Phase.PREPARE;
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
                return failOrClean("wrong_station_menu",
                        "The opened workstation does not match the selected cooking recipe.",
                        FailureType.TARGET_LOST);
            }
            // This counter is a consecutive confirmation retry budget for one
            // open operation, not a lifetime cap across later cooking batches.
            openAttempts = 0;
            openedMenu = true;
            phase = Phase.VALIDATE;
            return TaskState.RUNNING;
        }
        if (player.level().getGameTime() - waitMenuSince > 60L) {
            if (openAttempts < 2) {
                phase = Phase.OPEN;
                return TaskState.RUNNING;
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
            phase = Phase.WAIT_COOK;
            markCookEvidence();
            return TaskState.RUNNING;
        }
        return transferTo(fuel, toLoad, 1, Purpose.LOAD_FUEL);
    }

    private TaskState waitCook() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null) return menuLost();
        ItemStack result = menu.getSlot(2).getItem();
        if (!result.isEmpty() && !result.is(BuiltInRegistries.ITEM.get(r.itemId))) {
            return failOrClean("cooking_output_diverged",
                    "The synchronized workstation output no longer matches the selected recipe.",
                    FailureType.UNKNOWN);
        }
        ItemStack input = menu.getSlot(0).getItem();
        if ((!result.isEmpty() && input.isEmpty())
                || (!result.isEmpty() && outputCount() + result.getCount() >= r.count)) {
            return startTransfer(List.of(
                    new ContainerTransferTaskRecord.Move(2, -1, 0)), Purpose.TAKE_OUTPUT);
        }
        String evidence = data(menu, 0) + ":" + data(menu, 2) + ":" + data(menu, 3)
                + ":" + input.getCount() + ":" + result.getCount();
        if (!evidence.equals(lastCookEvidence)) {
            lastCookEvidence = evidence;
            lastCookEvidenceTick = player.level().getGameTime();
            renewCookProgressLease();
        }
        if (!input.isEmpty() && data(menu, 0) <= 0
                && menu.getSlot(1).getItem().isEmpty()) {
            return failOrClean("fuel_exhausted",
                    "The workstation has recipe input but no synchronized burn time or fuel remaining.",
                    FailureType.NO_MATERIAL);
        }
        long quietLimit = Math.max(200L, candidate.recipe.getCookingTime() + 100L);
        if (player.level().getGameTime() - lastCookEvidenceTick > quietLimit) {
            return failOrClean("cooking_stalled",
                    "Synchronized furnace data and output stopped advancing.",
                    FailureType.UNKNOWN);
        }
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
            moves.add(new ContainerTransferTaskRecord.Move(slot, destination, moved));
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
        if (!menu.getSlot(2).getItem().isEmpty()) {
            return startTransfer(List.of(
                    new ContainerTransferTaskRecord.Move(2, -1, 0)), Purpose.CLEAN_RESULT);
        }
        if (!menu.getSlot(0).getItem().isEmpty()) {
            return startTransfer(List.of(
                    new ContainerTransferTaskRecord.Move(0, -1, 0)), Purpose.CLEAN_INPUT);
        }
        if (!menu.getSlot(1).getItem().isEmpty()) {
            return startTransfer(List.of(
                    new ContainerTransferTaskRecord.Move(1, -1, 0)), Purpose.CLEAN_FUEL);
        }
        return start(new CloseMenuTaskRecord(
                childId("close"), childDeadline(30L * 20L)), Purpose.CLOSE);
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
            if (purpose == Purpose.ACQUIRE_INPUT || purpose == Purpose.ACQUIRE_FUEL
                    || purpose == Purpose.ACQUIRE_STATION) {
                prerequisiteFailure = semanticPrerequisiteFailure(result);
            }
            if (purpose == Purpose.LOAD_INPUT || purpose == Purpose.LOAD_FUEL
                    || purpose == Purpose.TAKE_OUTPUT || purpose == Purpose.CLEAN_RESULT
                    || purpose == Purpose.CLEAN_INPUT || purpose == Purpose.CLEAN_FUEL) {
                outcomeUncertain = true;
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
                stationPos = nearest(candidate.device.block, 8, 8);
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
                phase = Phase.LOAD_FUEL;
            }
            case LOAD_FUEL -> {
                effectsStarted = true;
                phase = Phase.WAIT_COOK;
                markCookEvidence();
            }
            case TAKE_OUTPUT -> phase = finishRequested || outputCount() >= r.count
                    ? Phase.CLEANUP : Phase.PREPARE;
            case CLEAN_RESULT, CLEAN_INPUT, CLEAN_FUEL -> phase = Phase.CLEANUP;
            case CLOSE -> {
                openedMenu = false;
                return finishCleanup();
            }
        }
        if (finishRequested && phase != Phase.CLEANUP) phase = Phase.CLEANUP;
        return TaskState.RUNNING;
    }

    private static Map<String, Object> semanticPrerequisiteFailure(TaskResult result) {
        if (result == null || result.data() == null) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of(
                "failure_type", "failure_code", "requires_decision",
                "requires_narration", "recovery_options", "issues")) {
            Object value = result.data().get(key);
            if (value != null) safe.put(key, value);
        }
        return Map.copyOf(safe);
    }

    private TaskState start(TaskRecord record, Purpose purpose) {
        activeRecord = record;
        activeChild = TaskFactory.create(player, record);
        activePurpose = purpose;
        extendParentPast(record.getDeadlineGameTime());
        return TaskState.RUNNING;
    }

    private TaskState failOrClean(String code, String message, FailureType type) {
        if (failureMessage == null) {
            failureCode = code;
            failureMessage = message;
            failureType = type == null ? FailureType.UNKNOWN : type;
        }
        if (openedMenu && player.containerMenu instanceof AbstractFurnaceMenu) {
            phase = Phase.CLEANUP;
            return TaskState.RUNNING;
        }
        phase = Phase.COMPLETE;
        return TaskState.RUNNING;
    }

    private TaskState menuLost() {
        outcomeUncertain |= effectsStarted;
        openedMenu = false;
        return failOrClean("cooking_menu_lost",
                effectsStarted
                        ? "The synchronized workstation menu changed after cooking effects began; "
                                + "blind retry is unsafe."
                        : "The synchronized workstation menu changed before cooking began.",
                FailureType.TARGET_LOST);
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
        return nearest(device.block, 16, 8) != null
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
        int missing = Math.max(0, r.count - outputCount());
        return Math.max(1, ceilDiv(missing, candidate.outputCount));
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
        if (openedMenu && player.containerMenu instanceof AbstractFurnaceMenu) {
            try {
                var context = ClientRuntime.requireContext(player);
                context.menus().close(context, 20);
            } catch (RuntimeException ignored) {
                outcomeUncertain = true;
            }
        }
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
        if (!prerequisiteFailure.isEmpty()) {
            data.put("prerequisite_failure", prerequisiteFailure);
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
