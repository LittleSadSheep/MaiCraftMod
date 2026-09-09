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
import org.maiwithu.maicraft.client.actor.MenuVisibility;
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

/**
 * 完成“往箱子存、从箱子取、把背包调整到指定数量”的整条流程。
 * 顺序是找容器、走近、打开、认清菜单两边、先算好容量、一笔一笔搬并核对数量，最后关闭。
 * 这既包含物品目标，也包含选箱子和旁人接近等规则；这些附加限制在审计记录中单独列出供判断。
 */
public final class SemanticContainerCompanionTask
        extends AbstractCompanionTask<SemanticContainerTaskRecord> {
    private static final double REACH = 4.5D;
    private static final int LANDMARK_PROTECTION_RADIUS = 12;
    private static final int OTHER_PLAYER_RADIUS = 6;
    private static final long MENU_WAIT_TICKS = 80L;
    /** A verified equal-and-opposite inventory delta renews a long multi-stack transfer. */
    private static final long TRANSFER_PROGRESS_LEASE_TICKS = 2L * 60L * 20L;

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
    private TaskRecord activeRecord;
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

    // 先确认没有别的菜单占着，再按物品、容器类型、地标和保护范围找候选；未知保护地标会直接拒绝。
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
        // 默认要求只剩一个候选。当前按方块实体计数，双箱两个半边会被算作两个候选，见 A50。
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

    // 只扫描已加载区块里的容器方块实体，不读未加载区域。
    // 保护和去重都以单个方块位置为单位，没有把一个大箱子的两半当成同一容器（A50／A52）。
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

    // 以每个保护地标为中心，把十二格距离内的容器位置排除；这里没有检查关联的大箱子另一半。
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

    // 走近前反复核对目标方块是否还在、附近是否出现其他玩家；到达几何距离后才开始开箱。
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

    // 检查当前没有意外菜单、容器没有明确锁定，再通过普通右键子任务打开。
    // 这里没指定手持物品，会沿用玩家当前手里的东西；打开失败时的物品兜底风险见 A29。
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

    // 等右键带来的菜单真正出现并显示，要求鼠标上没有残留物品，再记录玩家侧、容器侧和整份菜单状态。
    private TaskState waitMenu() {
        if (player.containerMenu != player.inventoryMenu) {
            openedMenu = true;
            var context = ClientRuntime.requireContext(player);
            if (!context.menus().ensureVisible(context)) return TaskState.RUNNING;
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

    // 先分出玩家前 36 格和外部格；原版箱子、潜影盒、漏斗、发射器和熔炉有已知规则。
    // 其他 Mod 菜单只接受能按普通槽类和单一容器解释的布局，AE2 虚拟槽转交专用能力。
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

    // 菜单没换、物品没被别人改动、鼠标为空且旁人条件通过后，才计算要搬多少。
    // 即使最终数量已经满足，也是先开箱走到这里才发现不需要搬。
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

    // deposit 固定存入，withdraw 固定取出；balance 比较背包数量，多了存、少了取。
    private Direction direction(int playerCount) {
        return switch (r.operation) {
            case DEPOSIT -> Direction.DEPOSIT;
            case WITHDRAW -> Direction.WITHDRAW;
            case BALANCE -> playerCount > r.targetCount ? Direction.DEPOSIT : Direction.WITHDRAW;
        };
    }

    // balance 把背包调到 target_count；普通存取若给 target_count，则只补足目的侧的差额。
    // 给 count 就搬指定数量；两者都省略时搬源侧全部匹配物品。
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
    // 在菜单副本上先完整分配目标容量，确认每个源格允许取出、每个目标格允许放入，才开始真实点击。
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

    // 先合并相同种类及组件的已有堆叠，再用空格；同时考虑物品上限和目标槽自己的容量限制。
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

    // 每笔开始前核对菜单及内容没有被外界改动，再交给低层搬运任务执行。
    // 这里的其他玩家检查只在笔与笔之间进行，长子任务内部不会每刻回来检查。
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
                List.of(pendingMove.move()), false), Purpose.TRANSFER);
    }

    // 低层说完成后，父任务再核对所选物品：玩家侧增加多少，容器侧就应减少多少，反向存入同理。
    // 对不上就停并标记结果不确定，不继续按旧计划盲点。
    private TaskState verifyTransfer() {
        if (!menuValid() || pendingMove == null) {
            outcomeUncertain = true;
            return menuLost("The menu changed before the transfer delta could be verified.");
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            outcomeUncertain = true;
            return failFinal("cursor_rollback_unconfirmed", "A transfer receipt completed without "
                    + "an empty menu cursor.", FailureType.UNKNOWN);
        }
        int afterPlayer = count(view.playerSlots());
        int afterContainer = count(view.containerSlots());
        Map<String, Integer> afterItems = itemCounts(view.playerSlots());
        String itemId = pendingMove.itemId().toString();
        int playerItemDelta = afterItems.getOrDefault(itemId, 0)
                - beforeItems.getOrDefault(itemId, 0);
        int expectedPlayerDelta = direction == Direction.DEPOSIT
                ? -pendingMove.count() : pendingMove.count();
        int playerDelta = afterPlayer - beforePlayerCount;
        int containerDelta = afterContainer - beforeContainerCount;
        if (playerDelta != expectedPlayerDelta
                || containerDelta != -expectedPlayerDelta
                || playerItemDelta != expectedPlayerDelta) {
            outcomeUncertain = true;
            return failFinal("transfer_delta_diverged", "The real menu did not show equal and "
                    + "opposite container/main-inventory deltas for the confirmed semantic item. "
                    + "Blind retry was stopped.", FailureType.UNKNOWN);
        }
        effectsStarted = true;
        movedCount += pendingMove.count();
        movedByItem.merge(itemId, pendingMove.count(), Integer::sum);
        lastPlayerCount = afterPlayer;
        lastContainerCount = afterContainer;
        stableFingerprint = fingerprint(player.containerMenu);
        pendingMove = null;
        planIndex++;
        r.extendDeadlineTo(player.level().getGameTime() + TRANSFER_PROGRESS_LEASE_TICKS);
        phase = Phase.TRANSFER;
        return TaskState.RUNNING;
    }

    // 最后按用户目标检查：balance 要背包恰好等于目标；补足模式要求目的侧至少达到目标；普通搬运要求累计量相等。
    private boolean goalSatisfied() {
        if (r.operation == SemanticContainerTaskRecord.Operation.BALANCE) {
            return lastPlayerCount == r.targetCount;
        }
        if (r.targetCount != null) {
            return r.operation == SemanticContainerTaskRecord.Operation.DEPOSIT
                    ? lastContainerCount >= r.targetCount : lastPlayerCount >= r.targetCount;
        }
        return movedCount == plannedAmount;
    }

    // 已经回到无界面的背包就结束；否则创建关闭当前菜单的子任务。
    // 它没有绑定原菜单身份，因此 menuLost 的错误归属会让它关掉替换菜单（A51）。
    private TaskState cleanupMenu() {
        if (player.containerMenu == player.inventoryMenu && ClientRuntime.requireContext(player).minecraft().screen == null) {
            openedMenu = false;
            openRequested = false;
            if (!player.inventoryMenu.getCarried().isEmpty()) outcomeUncertain = true;
            phase = Phase.COMPLETE;
            return TaskState.RUNNING;
        }
        return start(new CloseMenuTaskRecord(
                childId("close"), childDeadline(30L * 20L)), Purpose.CLOSE);
    }

    // 开箱、搬运、关箱都有自己的子任务。子任务结束先取结果并清理，再按用途继续下一阶段或记录失败。
    private TaskState tickChild() {
        TaskState terminal = runChild(activeChild);
        if (terminal == null) {
            if (activeRecord != null) r.extendDeadlineTo(activeRecord.getDeadlineGameTime());
            return TaskState.RUNNING;
        }
        TaskResult result = activeChild.result(terminal);
        Purpose purpose = activePurpose;
        activeChild = null;
        activeRecord = null;
        activePurpose = null;
        boolean success = terminal == TaskState.SUCCESS && result != null && result.success();
        if (!success) {
            return switch (purpose) {
                case OPEN -> {
                    if (player.containerMenu != player.inventoryMenu) {
                        openedMenu = true;
                        yield failFinal("container_open_unconfirmed", "A menu appeared but the "
                                + "native open receipt was not confirmed.", lastFailure());
                    }
                    yield failFinal("container_interaction_failed", "The selected container could "
                            + "not be opened through ordinary first-person interaction.",
                            lastFailure());
                }
                case TRANSFER -> {
                    outcomeUncertain = true;
                    yield failFinal("container_transfer_unconfirmed", "A menu transfer did not "
                            + "receive a complete cursor-safe confirmation. Blind retry stopped.",
                            lastFailure());
                }
                case CLOSE -> {
                    outcomeUncertain = true;
                    if (failureMessage == null) {
                        failureCode = "container_close_unconfirmed";
                        failureMessage = "The native container close was not confirmed.";
                        failureType = lastFailure();
                    }
                    phase = Phase.COMPLETE;
                    yield TaskState.RUNNING;
                }
            };
        }
        return switch (purpose) {
            case OPEN -> {
                waitMenuSince = player.level().getGameTime();
                phase = Phase.WAIT_MENU;
                yield TaskState.RUNNING;
            }
            case TRANSFER -> verifyTransfer();
            case CLOSE -> {
                openedMenu = false;
                openRequested = false;
                if (player.containerMenu != player.inventoryMenu
                        || ClientRuntime.requireContext(player).minecraft().screen != null
                        || !player.inventoryMenu.getCarried().isEmpty()) {
                    outcomeUncertain = true;
                    if (failureMessage == null) {
                        failureCode = "container_close_unconfirmed";
                        failureMessage = "The menu did not return to an empty native inventory state.";
                        failureType = FailureType.UNKNOWN;
                    }
                }
                phase = Phase.COMPLETE;
                yield TaskState.RUNNING;
            }
        };
    }

    private TaskState start(TaskRecord record, Purpose purpose) {
        activeRecord = record;
        activeChild = TaskFactory.create(player, record);
        activePurpose = purpose;
        r.extendDeadlineTo(record.getDeadlineGameTime());
        return TaskState.RUNNING;
    }

    // 必须还是原来那个菜单对象、编号和类型，且它正在显示；仅仅菜单名字相似不够。
    private boolean menuValid() {
        return view != null && player.containerMenu != player.inventoryMenu
                && player.containerMenu == view.menu()
                && MenuVisibility.matches(ClientRuntime.requireContext(player).minecraft(), player.containerMenu)
                && player.containerMenu.containerId == expectedContainerId
                && player.containerMenu.getClass() == expectedMenuClass;
    }

    // 当前代码把任何新出现的外部菜单也标成“本任务打开”，会进入关闭流程；这是 A51 的归属问题。
    private TaskState menuLost(String message) {
        outcomeUncertain |= effectsStarted;
        openedMenu = player.containerMenu != player.inventoryMenu;
        return failFinal("container_menu_lost", message, FailureType.TARGET_LOST);
    }

    // 保留最早的失败原因，先处理还开的菜单再给最终失败；已经确认的搬运量不会清零。
    private TaskState failFinal(String code, String message, FailureType type) {
        if (failureMessage == null) {
            failureCode = code;
            failureMessage = message;
            failureType = type == null ? FailureType.UNKNOWN : type;
        }
        if ((openedMenu || openRequested) && player.containerMenu != player.inventoryMenu) {
            openedMenu = true;
            phase = Phase.CLEANUP;
        } else {
            openedMenu = false;
            phase = Phase.COMPLETE;
        }
        return TaskState.RUNNING;
    }

    private boolean targetStillValid() {
        if (target == null || !player.level().isLoaded(target.position())) return false;
        BlockEntity entity = player.level().getBlockEntity(target.position());
        if (!(entity instanceof Container)) return false;
        var state = player.level().getBlockState(target.position());
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(target.blockId())) return false;
        return state.getMenuProvider(player.level(), target.position()) != null;
    }

    private boolean withinReach() {
        return target != null && player.getEyePosition().distanceToSqr(
                Vec3.atCenterOf(target.position())) <= REACH * REACH;
    }

    // 只要箱子周围各方向扩六格的盒形范围内有另一位活着、非旁观的玩家，就阻止继续；不检查对方是否实际操作箱子。
    private boolean otherPlayerNearTarget() {
        if (target == null) return false;
        AABB bounds = new AABB(target.position()).inflate(OTHER_PLAYER_RADIUS);
        return !player.clientLevel.getEntitiesOfClass(Player.class, bounds, candidate ->
                candidate != player && !candidate.getUUID().equals(player.getUUID())
                        && candidate.isAlive() && !candidate.isSpectator()).isEmpty();
    }

    private int count(List<Integer> slots) {
        int total = 0;
        AbstractContainerMenu menu = player.containerMenu;
        for (int slot : slots) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (matches(stack)) total += stack.getCount();
        }
        return total;
    }

    private Map<String, Integer> itemCounts(List<Integer> slots) {
        Map<String, Integer> result = new LinkedHashMap<>();
        AbstractContainerMenu menu = player.containerMenu;
        for (int slot : slots) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!matches(stack)) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            result.merge(id, stack.getCount(), Integer::sum);
        }
        return result;
    }

    private boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (itemTag != null) return stack.is(itemTag);
        return r.itemIds.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    // 把整份菜单的槽内物品、数量、组件摘要及鼠标物品记录成文字，下一笔前比较是否有外部变化。
    private static String fingerprint(AbstractContainerMenu menu) {
        StringBuilder result = new StringBuilder(menu.getClass().getName())
                .append(':').append(menu.containerId).append('|');
        for (Slot slot : menu.slots) appendStack(result, slot.getItem());
        result.append("cursor=");
        appendStack(result, menu.getCarried());
        return result.toString();
    }

    private static void appendStack(StringBuilder result, ItemStack stack) {
        if (stack.isEmpty()) {
            result.append("_;");
            return;
        }
        result.append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                .append('@').append(stack.getCount())
                .append('#').append(stack.getComponentsPatch().hashCode()).append(';');
    }

    private boolean sameDimension(Goal.WorldPosition position) {
        return position != null && (position.dimension() == null
                || position.dimension().equals(
                        player.level().dimension().location().toString()));
    }

    private static boolean specializedStorage(ResourceLocation blockId) {
        return blockId != null && (blockId.getNamespace().equals("ae2")
                || blockId.getNamespace().equals("appeng"));
    }

    private static boolean specializedStorage(String className) {
        String value = className == null ? "" : className.toLowerCase(Locale.ROOT);
        return value.contains("appeng") || value.contains(".ae2.");
    }

    private static long squared(BlockPos left, BlockPos right) {
        long dx = (long) left.getX() - right.getX();
        long dy = (long) left.getY() - right.getY();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private String childId(String label) {
        return r.getToolCallId() + "-container-" + label + "-" + (++childSerial);
    }

    private long childDeadline(long ticks) {
        long lease = player.level().getGameTime() + ticks;
        r.extendDeadlineTo(lease);
        return lease;
    }

    // 任务被取消等情况会停止子任务并尝试关菜单；当前只 stop 子任务，没有调用它的 result 来完成全部清理。
    @Override protected void cleanup() {
        stopNav();
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            activeChild = null;
            activeRecord = null;
            activePurpose = null;
        }
        if ((openedMenu || openRequested) && player.containerMenu != player.inventoryMenu) {
            try {
                var context = ClientRuntime.requireContext(player);
                context.menus().closeForTaskBoundary(context, 40, "semantic container task ended");
            } catch (RuntimeException ignored) {
                outcomeUncertain = true;
            }
        }
        super.cleanup();
    }

    // 分别报告已确认搬运数量、最后观察的两边库存、目标是否满足和是否仍有不确定结果，供上层决定后续工作。
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", r.operation.name().toLowerCase(Locale.ROOT));
        if (containerKind != null) data.put("container_kind", containerKind);
        data.put("initial_main_count", initialPlayerCount);
        data.put("observed_final_main_count", lastPlayerCount);
        data.put("initial_container_count", initialContainerCount);
        data.put("observed_final_container_count", lastContainerCount);
        data.put("moved_count", movedCount);
        data.put("moved_items", Map.copyOf(movedByItem));
        data.put("goal_satisfied", goalSatisfied);
        data.put("outcome_partial", movedCount > 0 && !goalSatisfied);
        data.put("outcome_uncertain", outcomeUncertain);
        if (r.count != null) data.put("requested_count", r.count);
        if (r.targetCount != null) data.put("target_count", r.targetCount);
        if (failureCode != null) {
            data.put("decision", Map.of("required", true, "reason_code", failureCode,
                    "recovery_options", recoveryOptions(failureCode, movedCount > 0)));
        }
        return data;
    }

    private static List<String> recoveryOptions(String code, boolean partial) {
        if (partial) return List.of(
                "request only the remaining amount using verified moved_count, or use an idempotent target_count",
                "wait until the container is stable and no other player is using it",
                "choose another container", "cancel");
        return switch (code) {
            case "insufficient_source", "source_slots_locked" -> List.of(
                    "lower the requested count or choose another source/container",
                    "acquire the missing selected items, then retry", "cancel");
            case "destination_full_or_locked" -> List.of(
                    "free destination capacity or choose another container",
                    "lower the requested count", "cancel");
            case "ambiguous_container" -> List.of(
                    "retry with selection=nearest if any nearest safe match is acceptable",
                    "narrow block_id or landmark_label", "cancel");
            case "specialized_storage_required" -> List.of(
                    "use the dedicated storage-network supply or acquisition ability",
                    "choose an ordinary block container", "cancel");
            case "other_player_near_container", "menu_changed_externally" -> List.of(
                    "wait until the container is no longer being used, then retry",
                    "choose another container", "cancel");
            case "container_locked" -> List.of(
                    "obtain authorization or the required key, then retry",
                    "choose another container", "cancel");
            case "unsupported_modded_slots", "unsupported_menu_layout" -> List.of(
                    "use the storage system's dedicated semantic ability",
                    "choose an ordinary chest, barrel, shulker box or furnace-family block",
                    "perform this transfer manually", "cancel");
            default -> List.of(
                    "retry after the container and surrounding players are stable",
                    "choose another loaded ordinary container", "cancel");
        };
    }

    @Override protected String successMessage() {
        return "verified " + movedCount + " semantic item(s) against "
                + (containerKind == null ? "the selected container" : containerKind)
                + " and closed its native menu";
    }

    @Override protected String timeoutMessage() {
        return "container management stopped making verified progress after " + movedCount
                + " verified item(s); no unverified retry was attempted";
    }

    @Override protected String cancelledMessage() {
        return "container management was interrupted after " + movedCount
                + " verified item(s)";
    }
}
