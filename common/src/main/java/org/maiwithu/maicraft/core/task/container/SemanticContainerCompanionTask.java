// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.menu.CloseMenuTaskRecord;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Receipt-driven first-person container executor; slots and positions stay internal. */
public final class SemanticContainerCompanionTask
        extends AbstractCompanionTask<SemanticContainerTaskRecord> {
    private static final double REACH = 4.5D;
    private static final int LANDMARK_PROTECTION_RADIUS = 12;
    private static final int OTHER_PLAYER_RADIUS = 6;
    private static final long MENU_WAIT_TICKS = 80L;

    private enum Phase { SURVEY, APPROACH, OPEN, WAIT_MENU, PLAN, TRANSFER, CLEANUP, COMPLETE }
    private enum Purpose { OPEN, TRANSFER, CLOSE }
    private enum Direction { DEPOSIT, WITHDRAW }

    private record Candidate(BlockPos position, ResourceLocation blockId, boolean nameMatches) {}
    private record MenuView(AbstractContainerMenu menu, List<Integer> playerSlots,
            List<Integer> containerSlots, boolean quickMoveSafe, boolean genericProof) {}
    private record PlannedMove(ContainerTransferTaskRecord.Move move,
            ResourceLocation itemId, int count) {}
    private record Allocation(int destination, int count) {}
    private record Planning(List<PlannedMove> moves, String failureCode,
            String failureMessage, FailureType failureType) {
        static Planning ok(List<PlannedMove> moves) {
            return new Planning(List.copyOf(moves), null, null, FailureType.UNKNOWN);
        }
        static Planning fail(String code, String message, FailureType type) {
            return new Planning(List.of(), code, message, type);
        }
        boolean success() { return failureCode == null; }
    }

    private Phase phase = Phase.SURVEY;
    private Candidate target;
    private MenuView view;
    private TagKey<Item> itemTag;
    private Task activeChild;
    private Purpose activePurpose;
    private int childSerial;
    private int approachDudTicks;
    private long waitMenuSince;
    private int expectedContainerId = -1;
    private Class<?> expectedMenuClass;
    private boolean openedMenu;
    private boolean openRequested;
    private boolean outcomeUncertain;
    private boolean effectsStarted;
    private String stableFingerprint;
    private List<PlannedMove> plan = List.of();
    private int planIndex;
    private PlannedMove pendingMove;
    private int beforePlayerCount;
    private int beforeContainerCount;
    private Map<String, Integer> beforeItems = Map.of();
    private Direction direction;
    private int plannedAmount;
    private int movedCount;
    private final Map<String, Integer> movedByItem = new LinkedHashMap<>();
    private int initialPlayerCount;
    private int initialContainerCount;
    private int lastPlayerCount;
    private int lastContainerCount;
    private String containerKind;
    private boolean goalSatisfied;
    private String failureCode;
    private String failureMessage;
    private FailureType failureType = FailureType.UNKNOWN;

    public SemanticContainerCompanionTask(LocalPlayer player, SemanticContainerTaskRecord record) {
        super(player, record);
    }

    @Override protected void onStart() {
        if (r.tagId != null) itemTag = TagKey.create(Registries.ITEM, r.tagId);
    }

    @Override protected TaskState onTick() {
        if (activeChild != null) return tickChild();
        if (phase == Phase.COMPLETE) {
            if (failureMessage != null) {
                fail(failureMessage, failureType);
                return TaskState.FAILED;
            }
            return TaskState.SUCCESS;
        }
        return switch (phase) {
            case SURVEY -> survey();
            case APPROACH -> approach();
            case OPEN -> open();
            case WAIT_MENU -> waitMenu();
            case PLAN -> plan();
            case TRANSFER -> transfer();
            case CLEANUP -> cleanupMenu();
            case COMPLETE -> TaskState.SUCCESS;
        };
    }

    private TaskState survey() {
        if (player.containerMenu != player.inventoryMenu) {
            return failFinal("menu_busy", "Another synchronized menu is already open; MaiCraft "
                    + "will not repurpose or close a menu it did not open.", FailureType.UNKNOWN);
        }
        if (itemTag != null && BuiltInRegistries.ITEM.getTag(itemTag).isEmpty()) {
            return failFinal("unknown_item_tag", "The selected item tag is not present in the "
                    + "active registries.", FailureType.NO_MATERIAL);
        }
        if (!unknownLabels().isEmpty()) {
            return failFinal("unknown_protected_label", "Some protected labels are not remembered, "
                    + "so container safety cannot be proven.", FailureType.UNKNOWN);
        }
        BlockPos center = selectionCenter();
        if (center == null) return TaskState.RUNNING;
        if (r.blockId != null && specializedStorage(r.blockId)) {
            return failFinal("specialized_storage_required", "That storage network has its own "
                    + "semantic supply path; generic transfer will not guess its terminal slots.",
                    FailureType.UNSUPPORTED);
        }
        List<Candidate> candidates = loadedCandidates(center);
        if (candidates.isEmpty()) {
            return failFinal("no_safe_loaded_container", "No matching, loaded and unprotected "
                    + "block container can be selected safely.", FailureType.TARGET_LOST);
        }
        candidates.sort(Comparator
                .comparingInt((Candidate c) -> c.nameMatches() ? 0 : 1)
                .thenComparingLong(c -> squared(c.position(), center))
                .thenComparing(c -> c.blockId().toString())
                .thenComparingLong(c -> c.position().asLong()));
        List<Candidate> namedMatches = candidates.stream().filter(Candidate::nameMatches).toList();
        if (!namedMatches.isEmpty()) candidates = new ArrayList<>(namedMatches);
        if (r.selection == SemanticContainerTaskRecord.Selection.UNIQUE && candidates.size() != 1) {
            return failFinal("ambiguous_container", "Several loaded containers match. Choose "
                    + "selection=nearest or narrow the block type or landmark.", FailureType.UNKNOWN);
        }
        target = candidates.getFirst();
        containerKind = target.blockId().toString();
        if (otherPlayerNearTarget()) {
            return failFinal("other_player_near_container", "Another player is close enough to be "
                    + "using or changing the selected container.", FailureType.ENTITY_BLOCKED);
        }
        phase = Phase.APPROACH;
        return TaskState.RUNNING;
    }

    private BlockPos selectionCenter() {
        if (r.landmarkLabel == null) return player.blockPosition();
        IntentRuntime.Landmark landmark = IntentRuntime.get().landmark(r.landmarkLabel);
        if (landmark == null) {
            failFinal("unknown_target_landmark", "The requested container landmark is not "
                    + "remembered.", FailureType.TARGET_LOST);
            return null;
        }
        Goal.WorldPosition position = landmark.position();
        if (!sameDimension(position)) {
            failFinal("target_landmark_not_in_dimension", "The requested container landmark is in "
                    + "another dimension.", FailureType.TARGET_LOST);
            return null;
        }
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private List<String> unknownLabels() {
        return r.protectedLabels.stream()
                .filter(label -> IntentRuntime.get().landmark(label) == null).toList();
    }

    private List<Candidate> loadedCandidates(BlockPos center) {
        List<Candidate> result = new ArrayList<>();
        ClientLevel level = player.clientLevel;
        int chunkRadius = (r.radius + 15) / 16;
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        Set<Long> visited = new HashSet<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(
                        centerChunkX + dx, centerChunkZ + dz);
                if (chunk == null) continue;
                for (Map.Entry<BlockPos, BlockEntity> entry
                        : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (!visited.add(pos.asLong())
                            || squared(pos, center) > (long) r.radius * r.radius) continue;
                    BlockEntity entity = entry.getValue();
                    if (!(entity instanceof Container)) continue;
                    var state = level.getBlockState(pos);
                    ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    if (r.blockId != null && !r.blockId.equals(blockId)) continue;
                    MenuProvider provider = state.getMenuProvider(level, pos);
                    if (provider == null || specializedStorage(blockId)
                            || insideProtectedLandmark(pos)) continue;
                    String customName = entity instanceof BaseContainerBlockEntity named
                            && named.getCustomName() != null
                            ? named.getCustomName().getString() : null;
                    if (customName != null && r.protectedLabels.stream()
                            .anyMatch(label -> label.equalsIgnoreCase(customName))) continue;
                    boolean nameMatches = customName != null && r.landmarkLabel != null
                            && customName.equalsIgnoreCase(r.landmarkLabel);
                    result.add(new Candidate(pos.immutable(), blockId, nameMatches));
                }
            }
        }
        return result;
    }

    private boolean insideProtectedLandmark(BlockPos position) {
        for (String label : r.protectedLabels) {
            IntentRuntime.Landmark landmark = IntentRuntime.get().landmark(label);
            if (landmark == null || !sameDimension(landmark.position())) continue;
            Goal.WorldPosition at = landmark.position();
            BlockPos center = new BlockPos(at.x(), at.y(), at.z());
            if (squared(position, center)
                    <= (long) LANDMARK_PROTECTION_RADIUS * LANDMARK_PROTECTION_RADIUS) return true;
        }
        return false;
    }

    private TaskState approach() {
        if (!targetStillValid()) return failFinal("container_target_changed",
                "The selected block is no longer the same loaded container.",
                FailureType.TARGET_LOST);
        if (otherPlayerNearTarget()) return failFinal("other_player_near_container",
                "Another player approached the selected container, so MaiCraft paused before use.",
                FailureType.ENTITY_BLOCKED);
        if (withinReach()) {
            stopNav();
            approachDudTicks = 0;
            phase = Phase.OPEN;
            return TaskState.RUNNING;
        }
        if (nav == null) {
            nav = PlayerNav.to(player, () -> GoalCompiler.interact(target.position()),
                    1.0D, this::withinReach).withTerrainProbe();
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                if (++approachDudTicks < 10) yield TaskState.RUNNING;
                stopNav();
                yield failFinal("container_out_of_reach",
                        "The route ended without a valid first-person interaction stance.",
                        FailureType.STANCE_DUD);
            }
            case FAILED -> {
                FailureType type = nav.failType();
                stopNav();
                yield failFinal("container_unreachable", "No safe first-person route reached the "
                        + "selected container under the current terrain policy.", type);
            }
        };
    }

    private TaskState open() {
        if (player.containerMenu != player.inventoryMenu) {
            return failFinal("menu_changed_before_open", "A menu appeared before MaiCraft opened "
                    + "the selected container.", FailureType.UNKNOWN);
        }
        if (!targetStillValid()) return failFinal("container_target_changed",
                "The selected block changed before it could be opened.", FailureType.TARGET_LOST);
        BlockEntity entity = player.level().getBlockEntity(target.position());
        if (entity instanceof BaseContainerBlockEntity container && !container.canOpen(player)) {
            return failFinal("container_locked", "The selected container reports that this player "
                    + "cannot open it. No menu action was attempted.", FailureType.UNKNOWN);
        }
        if (otherPlayerNearTarget()) return failFinal("other_player_near_container",
                "Another player is close enough to be using the selected container.",
                FailureType.ENTITY_BLOCKED);
        openRequested = true;
        return start(new InteractAtTaskRecord(childId("open"), childDeadline(30L * 20L),
                MouseButton.RIGHT, target.position(), 0, null), Purpose.OPEN);
    }

    private TaskState waitMenu() {
        if (player.containerMenu != player.inventoryMenu) {
            openedMenu = true;
            AbstractContainerMenu menu = player.containerMenu;
            if (!menu.getCarried().isEmpty()) {
                outcomeUncertain = true;
                return failFinal("menu_cursor_not_empty", "The newly opened menu already carries "
                        + "an item stack; safe ownership cannot be proven.", FailureType.UNKNOWN);
            }
            if (!targetStillValid()) return failFinal("container_target_changed",
                    "The selected block changed while its menu was opening.",
                    FailureType.TARGET_LOST);
            MenuView classified = classify(menu);
            if (classified == null) return TaskState.RUNNING;
            view = classified;
            expectedContainerId = menu.containerId;
            expectedMenuClass = menu.getClass();
            stableFingerprint = fingerprint(menu);
            initialPlayerCount = count(view.playerSlots());
            initialContainerCount = count(view.containerSlots());
            lastPlayerCount = initialPlayerCount;
            lastContainerCount = initialContainerCount;
            phase = Phase.PLAN;
            return TaskState.RUNNING;
        }
        if (player.level().getGameTime() - waitMenuSince > MENU_WAIT_TICKS) {
            return failFinal("container_open_unconfirmed", "The selected container did not produce "
                    + "a synchronized menu. It may be locked, blocked or unavailable.",
                    FailureType.TARGET_LOST);
        }
        return TaskState.RUNNING;
    }

    private MenuView classify(AbstractContainerMenu menu) {
        if (specializedStorage(target.blockId())
                || specializedStorage(menu.getClass().getName())) {
            failFinal("specialized_storage_required", "This storage terminal has a dedicated "
                    + "semantic supply path; generic clicks are unsafe for its virtual slots.",
                    FailureType.UNSUPPORTED);
            return null;
        }
        List<Integer> playerSlots = new ArrayList<>();
        List<Integer> containerSlots = new ArrayList<>();
        Set<Integer> inventoryIndices = new HashSet<>();
        Container genericBacking = null;
        boolean genericProof = true;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == player.getInventory()) {
                int inventorySlot = slot.getContainerSlot();
                if (inventorySlot >= 0 && inventorySlot < 36) {
                    if (!inventoryIndices.add(inventorySlot)) genericProof = false;
                    playerSlots.add(i);
                }
                continue;
            }
            containerSlots.add(i);
            if (genericBacking == null) genericBacking = slot.container;
            else if (genericBacking != slot.container) genericProof = false;
            if (slot.getClass() != Slot.class || slot.getContainerSlot() < 0
                    || slot.getContainerSlot() >= slot.container.getContainerSize()) {
                genericProof = false;
            }
        }
        if (playerSlots.size() != 36 || inventoryIndices.size() != 36
                || containerSlots.isEmpty()) {
            failFinal("unsupported_menu_layout", "The opened menu does not expose one provable "
                    + "main-inventory side and one container side.", FailureType.UNSUPPORTED);
            return null;
        }
        boolean knownStorage = menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu
                || menu instanceof HopperMenu || menu instanceof DispenserMenu;
        boolean knownMachine = menu instanceof AbstractFurnaceMenu;
        if (!knownStorage && !knownMachine && !genericProof) {
            failFinal("unsupported_modded_slots", "This modded menu uses slot classes or backing "
                    + "inventories whose semantics cannot be proven safely. No transfer was "
                    + "attempted.", FailureType.UNSUPPORTED);
            return null;
        }
        return new MenuView(menu, List.copyOf(playerSlots), List.copyOf(containerSlots),
                knownStorage, !knownStorage && !knownMachine);
    }

    private TaskState plan() {
        if (!menuValid()) return menuLost("The synchronized container menu changed before planning.");
        if (otherPlayerNearTarget()) return failFinal("other_player_near_container",
                "Another player approached the open container; MaiCraft paused before moving items.",
                FailureType.ENTITY_BLOCKED);
        if (!player.containerMenu.getCarried().isEmpty()) {
            outcomeUncertain = true;
            return failFinal("menu_cursor_not_empty", "The menu cursor is not empty, so no "
                    + "semantic transfer can start safely.", FailureType.UNKNOWN);
        }
        if (!fingerprint(player.containerMenu).equals(stableFingerprint)) {
            return failFinal("menu_changed_externally", "The menu contents changed after inspection "
                    + "and before the first click.", FailureType.TARGET_LOST);
        }

        int playerCount = count(view.playerSlots());
        int containerCount = count(view.containerSlots());
        direction = direction(playerCount);
        plannedAmount = requestedAmount(playerCount, containerCount, direction);
        if (plannedAmount < 0) return TaskState.RUNNING;
        if (plannedAmount == 0) {
            goalSatisfied = true;
            phase = Phase.CLEANUP;
            return TaskState.RUNNING;
        }
        if (plannedAmount > SemanticContainerTaskRecord.MAX_COUNT) {
            return failFinal("transfer_too_large", "The semantic group exceeds the bounded transfer "
                    + "limit; split the request by item group or explicit count.",
                    FailureType.UNSUPPORTED);
        }
        Planning planning = buildPlan(direction, plannedAmount);
        if (!planning.success()) {
            return failFinal(planning.failureCode(), planning.failureMessage(), planning.failureType());
        }
        plan = planning.moves();
        planIndex = 0;
        phase = Phase.TRANSFER;
        return TaskState.RUNNING;
    }

    private Direction direction(int playerCount) {
        return switch (r.operation) {
            case DEPOSIT -> Direction.DEPOSIT;
            case WITHDRAW -> Direction.WITHDRAW;
            case BALANCE -> playerCount > r.targetCount ? Direction.DEPOSIT : Direction.WITHDRAW;
        };
    }

    private int requestedAmount(int playerCount, int containerCount, Direction selectedDirection) {
        if (r.operation == SemanticContainerTaskRecord.Operation.BALANCE) {
            return Math.abs(playerCount - r.targetCount);
        }
        if (r.targetCount != null) {
            return r.operation == SemanticContainerTaskRecord.Operation.DEPOSIT
                    ? Math.max(0, r.targetCount - containerCount)
                    : Math.max(0, r.targetCount - playerCount);
        }
        if (r.count != null) return r.count;
        List<Integer> source = selectedDirection == Direction.DEPOSIT
                ? view.playerSlots() : view.containerSlots();
        int all = count(source);
        if (all == 0) {
            failFinal("insufficient_source", "The selected source side contains no matching items.",
                    FailureType.NO_MATERIAL);
            return -1;
        }
        return all;
    }

    /** Builds the complete bounded plan before the first click; failure leaves both sides untouched. */
    private Planning buildPlan(Direction selectedDirection, int amount) {
        List<Integer> sources = selectedDirection == Direction.DEPOSIT
                ? view.playerSlots() : view.containerSlots();
        List<Integer> destinations = selectedDirection == Direction.DEPOSIT
                ? view.containerSlots() : view.playerSlots();
        AbstractContainerMenu menu = view.menu();
        List<ItemStack> simulated = new ArrayList<>(menu.slots.size());
        for (Slot slot : menu.slots) simulated.add(slot.getItem().copy());

        int rawSource = 0;
        int transferableSource = 0;
        for (int sourceIndex : sources) {
            Slot slot = menu.getSlot(sourceIndex);
            ItemStack stack = slot.getItem();
            if (!matches(stack)) continue;
            rawSource += stack.getCount();
            if (slot.mayPickup(player)) transferableSource += stack.getCount();
        }
        if (rawSource < amount) return Planning.fail("insufficient_source",
                "The source contains fewer matching items than the requested amount.",
                FailureType.NO_MATERIAL);
        if (transferableSource < amount) return Planning.fail("source_slots_locked",
                "Matching items exist, but the menu does not prove they can be picked up safely.",
                FailureType.UNKNOWN);

        List<PlannedMove> moves = new ArrayList<>();
        int remaining = amount;
        for (int sourceIndex : sources) {
            if (remaining <= 0) break;
            Slot sourceSlot = menu.getSlot(sourceIndex);
            ItemStack source = simulated.get(sourceIndex);
            if (!matches(source) || !sourceSlot.mayPickup(player)) continue;
            int take = Math.min(remaining, source.getCount());
            List<Allocation> allocations = allocate(
                    source, take, destinations, menu, simulated);
            int allocated = allocations.stream().mapToInt(Allocation::count).sum();
            if (allocated != take) return Planning.fail("destination_full_or_locked",
                    "The destination cannot safely hold the complete requested amount. Nothing was "
                            + "moved.", FailureType.NO_SPACE);

            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(source.getItem());
            if (view.quickMoveSafe() && take == source.getCount()) {
                moves.add(new PlannedMove(
                        new ContainerTransferTaskRecord.Move(sourceIndex, -1, 0), itemId, take));
            } else {
                for (Allocation allocation : allocations) {
                    moves.add(new PlannedMove(new ContainerTransferTaskRecord.Move(
                            sourceIndex, allocation.destination(), allocation.count()),
                            itemId, allocation.count()));
                }
            }
            simulated.set(sourceIndex, source.getCount() == take
                    ? ItemStack.EMPTY : source.copyWithCount(source.getCount() - take));
            remaining -= take;
        }
        if (remaining != 0) return Planning.fail("transfer_plan_incomplete",
                "A complete safe slot-semantic plan could not be proven. Nothing was moved.",
                FailureType.UNSUPPORTED);
        return Planning.ok(moves);
    }

    private List<Allocation> allocate(ItemStack source, int amount, List<Integer> destinations,
            AbstractContainerMenu menu, List<ItemStack> simulated) {
        List<Allocation> result = new ArrayList<>();
        int remaining = amount;
        // Fill compatible stacks before consuming empty slots.
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int destinationIndex : destinations) {
                if (remaining <= 0) break;
                Slot destinationSlot = menu.getSlot(destinationIndex);
                ItemStack destination = simulated.get(destinationIndex);
                boolean empty = destination.isEmpty();
                if ((pass == 0 && empty) || (pass == 1 && !empty)) continue;
                if (!destinationSlot.mayPlace(source)) continue;
                if (!empty && !ItemStack.isSameItemSameComponents(destination, source)) continue;
                int limit = Math.min(source.getMaxStackSize(),
                        destinationSlot.getMaxStackSize(source));
                int capacity = Math.max(0, limit - (empty ? 0 : destination.getCount()));
                if (capacity == 0) continue;
                int placed = Math.min(remaining, capacity);
                result.add(new Allocation(destinationIndex, placed));
                simulated.set(destinationIndex, source.copyWithCount(
                        (empty ? 0 : destination.getCount()) + placed));
                remaining -= placed;
            }
        }
        return remaining == 0 ? List.copyOf(result) : List.of();
    }

    private TaskState transfer() {
        if (planIndex >= plan.size()) {
            goalSatisfied = goalSatisfied();
            if (!goalSatisfied) {
                outcomeUncertain = true;
                return failFinal("aggregate_goal_not_satisfied", "All planned receipts completed, "
                        + "but the aggregate semantic inventory goal is not true.",
                        FailureType.UNKNOWN);
            }
            phase = Phase.CLEANUP;
            return TaskState.RUNNING;
        }
        if (!menuValid()) return menuLost(
                "The synchronized container menu changed during the transfer.");
        if (!targetStillValid()) return failFinal("container_target_changed",
                "The selected container block changed during the transfer.",
                FailureType.TARGET_LOST);
        if (otherPlayerNearTarget()) return failFinal("other_player_near_container",
                "Another player approached the container. Verified moves were kept and MaiCraft "
                        + "paused before the next move.", FailureType.ENTITY_BLOCKED);
        if (!player.containerMenu.getCarried().isEmpty()) {
            outcomeUncertain = true;
            return failFinal("menu_cursor_not_empty", "The menu cursor stopped being empty between "
                    + "verified moves.", FailureType.UNKNOWN);
        }
        if (!fingerprint(player.containerMenu).equals(stableFingerprint)) {
            return failFinal("menu_changed_externally", "The container or main inventory changed "
                    + "between verified moves. MaiCraft paused instead of using a stale plan.",
                    FailureType.TARGET_LOST);
        }
        pendingMove = plan.get(planIndex);
        beforePlayerCount = count(view.playerSlots());
        beforeContainerCount = count(view.containerSlots());
        beforeItems = itemCounts(view.playerSlots());
        return start(new ContainerTransferTaskRecord(childId("transfer"),
                childDeadline(2L * 60L * 20L), expectedContainerId,
                List.of(pendingMove.move())), Purpose.TRANSFER);
    }

