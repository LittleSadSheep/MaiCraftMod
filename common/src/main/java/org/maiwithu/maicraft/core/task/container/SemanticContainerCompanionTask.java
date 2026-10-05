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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.core.FailureType;
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
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.integration.machine.MachineSurvey;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.scan.ObservationVisibility;

/**
 * 完成“往箱子存、从箱子取、把背包调整到指定数量”的整条流程。
 * 顺序是找容器、走近、打开、认清菜单两边、先算好容量、一笔一笔搬并核对数量，最后关闭。
 * 这既包含物品目标，也包含选箱子和旁人接近等规则；这些附加限制在审计记录中单独列出供判断。
 */
public final class SemanticContainerCompanionTask
        extends AbstractCompanionTask<SemanticContainerTaskRecord> {
    private static final int LANDMARK_PROTECTION_RADIUS = 12;
    private static final int OTHER_PLAYER_RADIUS = 6;
    private static final long MENU_WAIT_TICKS = 80L;
    /** 玩家和木桶两边数量确实一增一减，才给大批量搬运续时；一直等点击回执不算进展。 */
    private static final long TRANSFER_PROGRESS_LEASE_TICKS = 2L * 60L * 20L;

    private enum Phase { SURVEY, OPEN, WAIT_MENU, PLAN, TRANSFER, CLEANUP, COMPLETE }
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
    private String targetDimension;
    private long targetObservedAt;
    private MenuView view;
    private TagKey<Item> itemTag;
    private Task activeChild;
    private TaskRecord activeRecord;
    private Purpose activePurpose;
    private int childSerial;
    private long waitMenuSince;
    private int expectedContainerId = -1;
    private Class<?> expectedMenuClass;
    private boolean openedMenu;
    private boolean reusedMenu;
    private boolean openRequested;
    private boolean outcomeUncertain;
    private boolean effectsStarted;
    private int confirmedSplitClicks;
    private boolean satisfiedSettlement;
    private AbstractContainerMenu ownedMenu;
    private Map<BlockPos, BlockEntity> supplyIdentities = Map.of();
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
    private long countsObservedAt = -1;
    private Map<String, Object> lastNativeTransfer = Map.of();
    private Map<String, Object> lastContainerMemory = Map.of();
    private String containerKind;
    private boolean goalSatisfied;
    private String failureCode;
    private String failureMessage;
    private String openFailureMessage;
    private FailureType failureType = FailureType.UNKNOWN;

    public SemanticContainerCompanionTask(LocalPlayer player, SemanticContainerTaskRecord record) {
        super(player, record);
    }

    @Override protected void onStart() {
        if (r.tagId != null) itemTag = TagKey.create(Registries.ITEM, r.tagId);
    }

    // 调查补料子任务继承取物父任务的活动范围；公开定向存取没有这份内部范围，仍按自己的目标选箱。
    @Override protected TaskState onTick() {
        return r.investigationScope == null ? tickContainer() : r.investigationScope.boundMovement(this::tickContainer);
    }

    private TaskState tickContainer() {
        if (activeChild != null) return tickChild();
        if (satisfiedSettlement && phase != Phase.CLEANUP && phase != Phase.COMPLETE) {
            stopNav(); goalSatisfied = goalSatisfied();
            phase = openedMenu || openRequested ? Phase.CLEANUP : Phase.COMPLETE;
        }
        if (phase == Phase.COMPLETE) {
            if (failureMessage != null) {
                fail(failureMessage, failureType);
                return TaskState.FAILED;
            }
            return TaskState.SUCCESS;
        }
        return switch (phase) {
            case SURVEY -> survey();
            case OPEN -> open();
            case WAIT_MENU -> waitMenu();
            case PLAN -> plan();
            case TRANSFER -> transfer();
            case CLEANUP -> cleanupMenu();
            case COMPLETE -> TaskState.SUCCESS;
        };
    }

    // 先按物品、容器类型、地标和保护范围选箱子；若该箱已由原生工具打开，就接续现有菜单。
    private TaskState survey() {
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
        List<Candidate> candidates = new ArrayList<>(loadedCandidates(center));
        if (candidates.isEmpty()) {
            return failFinal("no_safe_loaded_container", emptySelectionDetail(center),
                    FailureType.TARGET_LOST);
        }
        candidates.sort(Comparator
                .comparingInt((Candidate c) -> c.nameMatches() ? 0 : 1)
                .thenComparingLong(c -> squared(c.position(), center))
                .thenComparing(c -> c.blockId().toString())
                .thenComparingLong(c -> c.position().asLong()));
        List<Candidate> namedMatches = candidates.stream().filter(Candidate::nameMatches).toList();
        if (!namedMatches.isEmpty()) candidates = new ArrayList<>(namedMatches);
        // 未允许就近选择时要求候选唯一；此处按方块实体计数，大箱两半可能造成歧义，不能称为整箱去重。
        if (r.selection == SemanticContainerTaskRecord.Selection.UNIQUE && candidates.size() != 1) {
            return failFinal("ambiguous_container", "Several loaded containers match. Choose "
                    + "selection=nearest or narrow the block type or landmark.", FailureType.UNKNOWN);
        }
        target = candidates.getFirst();
        targetDimension = player.level().dimension().location().toString();
        targetObservedAt = player.level().getGameTime();
        if (r.storageSupply()) {
            Map<BlockPos, BlockEntity> identities = new LinkedHashMap<>();
            ContainerSupplySources.footprint(player.level(), target.position()).forEach(at -> identities.put(at, player.level().getBlockEntity(at)));
            supplyIdentities = Map.copyOf(identities);
        }
        containerKind = target.blockId().toString();
        if (otherPlayerNearTarget()) {
            return failFinal("other_player_near_container", "Another player is close enough to be "
                    + "using or changing the selected container.", FailureType.ENTITY_BLOCKED);
        }
        if (player.containerMenu != player.inventoryMenu) return reuseOpenMenu();
        // 开箱子任务比较站位并等待交通收尾，父任务不再锁定另一条接近路线。
        phase = Phase.OPEN;
        return TaskState.RUNNING;
    }

    private TaskState reuseOpenMenu() {
        // 合适且空光标的原生箱子直接接续；其他旧菜单先退出，再打开本次已选定的目标，不猜旧槽位。
        if (!MachineMenu.openedAt(player, target.position()) || !player.containerMenu.getCarried().isEmpty()) {
            var context = ClientRuntime.requireContext(player);
            if (!context.menus().ensureWorldVisible(context)) return TaskState.RUNNING;
            phase = Phase.OPEN; return TaskState.RUNNING;
        }
        ownedMenu = player.containerMenu;
        openedMenu = true;
        reusedMenu = true;
        waitMenuSince = player.level().getGameTime();
        phase = Phase.WAIT_MENU;
        return TaskState.RUNNING;
    }

    // 精确点名只认该格；地标以记忆位置为调查中心，名字失效或维度不同就返回原因，不改去脚边找箱子。
    private BlockPos selectionCenter() {
        if (r.storageSupply()) return r.supplyPosition;
        if (r.exactTarget != null) {
            if (!r.exactDimension.equals(player.level().dimension().location().toString())) {
                failFinal("container_target_not_in_dimension", "The exact container is not in the current dimension.", FailureType.TARGET_LOST);
                return null;
            }
            return r.exactTarget;
        }
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
    // 内部供料已经选定具体仓库，并由 ContainerSupplySources 保护大箱子两半；普通选择器仍按方块找候选。
    private List<Candidate> loadedCandidates(BlockPos center) {
        if (r.storageSupply()) {
            if (!ContainerSupplySources.allowed(player, center, r.protectedLabels)) return List.of();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(center).getBlock());
            return r.blockId != null && !r.blockId.equals(id) ? List.of() : List.of(new Candidate(center, id, false));
        }
        if (r.exactTarget != null) {
            Candidate exact = candidateAt(r.exactTarget);
            return exact == null ? List.of() : List.of(exact);
        }
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
                    Candidate candidate = candidateAt(pos);
                    if (candidate != null) result.add(candidate);
                }
            }
        }
        return result;
    }

    private Candidate candidateAt(BlockPos pos) {
        // 精确和就近目标沿用同一类型与保护检查；明确箱子不存在时不另选一只代替。
        var level = player.level();
        if (!level.isLoaded(pos)) return null;
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof Container)) return null;
        var state = level.getBlockState(pos);
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if ((r.blockId != null && !r.blockId.equals(id)) || state.getMenuProvider(level, pos) == null
                || specializedStorage(id) || insideProtectedLandmark(pos)) return null;
        String name = entity instanceof BaseContainerBlockEntity named && named.getCustomName() != null
                ? named.getCustomName().getString() : null;
        if (name != null && r.protectedLabels.stream().anyMatch(label -> label.equalsIgnoreCase(name))) return null;
        return new Candidate(pos.immutable(), id, name != null && r.landmarkLabel != null && name.equalsIgnoreCase(r.landmarkLabel));
    }

    // 候选为空时区分真因：上方被堵无法开启、受保护、类型不符、范围内确无已加载匹配容器。
    // 原版箱子上方格为红石导体（如实木方块）时 getMenuProvider 直接返回 null，遮挡在候选
    // 扫描阶段就被排除；笼统的"不能安全选择"会把这种可自行清障的场景误归为容器不存在。
    private String emptySelectionDetail(BlockPos center) {
        BlockPos probe = r.exactTarget != null ? r.exactTarget : (r.storageSupply() ? center : null);
        var level = player.level();
        if (probe != null && level.isLoaded(probe)
                && level.getBlockEntity(probe) instanceof Container) {
            BlockState state = level.getBlockState(probe);
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (state.getMenuProvider(level, probe) == null) {
                return "The container at " + coords(probe) + " is loaded but cannot be opened: the "
                        + "cell above is occupied and blocks its lid. Clear the cell above the "
                        + "container and retry.";
            }
            if (insideProtectedLandmark(probe)) {
                return "The container at " + coords(probe) + " exists but sits inside a protected "
                        + "landmark; choose an unprotected container or lift the protection.";
            }
            if (r.blockId != null && !r.blockId.equals(id)) {
                return "The container at " + coords(probe) + " exists but is " + id
                        + ", not the requested " + r.blockId + ".";
            }
        }
        return "No matching, loaded and unprotected block container can be selected safely within "
                + "the requested " + r.radius + "-block radius; this covers loaded chunks only and "
                + "is not proof that no container exists beyond them.";
    }

    private String coords(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
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

    // 走近期间若同箱菜单已被原生工具打开，直接接续；否则检查锁定并通过普通右键子任务开箱。
    private TaskState open() {
        if (player.containerMenu != player.inventoryMenu) return reuseOpenMenu();
        if (!targetStillValid()) return failFinal("container_target_changed",
                "The selected block changed before it could be opened.", FailureType.TARGET_LOST);
        // 调查允许开未知或旧记忆无货的箱子；实际出发前仍须处于固定范围内且目标可见。
        if (r.investigationScope != null && (!r.investigationScope.contains(player)
                || !r.investigationScope.contains(target.position())
                || !ObservationVisibility.block(player, target.position())))
            return failFinal("container_not_visible_in_search_scope", "The container is no longer visible within the original search scope.", FailureType.TARGET_LOST);
        // 自动存余料继续要求已有对应材料线索，避免把取物调查许可扩展为陌生箱子的存入许可。
        if (r.storageSupply() && r.investigationScope == null && !ContainerSupplySources.hasObservedItems(player, target.position(), r.itemIds))
            return failFinal("container_stock_evidence_expired", "The automatic storage visit no longer has recent evidence of the requested material. Use a targeted container goal only when authorized or supported by a specific source hint.", FailureType.TARGET_LOST);
        BlockEntity entity = player.level().getBlockEntity(target.position());
        if (entity instanceof BaseContainerBlockEntity container && !container.canOpen(player)) {
            return failFinal("container_locked", "The selected container reports that this player "
                    + "cannot open it. No menu action was attempted.", FailureType.UNKNOWN);
        }
        // 调查许可按视线与原生权限落实，附近站着玩家本身不证明其占用了这只箱子。
        if (r.investigationScope == null && otherPlayerNearTarget()) return failFinal("other_player_near_container",
                "Another player is close enough to be using the selected container.",
                FailureType.ENTITY_BLOCKED);
        openRequested = true;
        if (r.storageSupply()) {
            // 取料也要走到木桶前、准备空手并实际打开 GUI；沿用机器开菜单流程，避免手持工具误用在箱子上。
            var request = new MachineMenu.OpenRequest(
                    player.level().dimension().location().toString(), target.position(), 0,
                    MachineSurvey.fingerprint(player, target.position(), 0), target.position());
            return start(MachineMenu.openTask(
                    childId("open-storage"), childDeadline(30L * 20L), request), Purpose.OPEN);
        }
        return start(new InteractAtTaskRecord(childId("open"), childDeadline(30L * 20L),
                MouseButton.RIGHT, target.position(), 0, null, null, player.level().getBlockState(target.position()).getBlock())
                .withEmptyHand().withApproach(r.mayAlterTerrain).withMenuObservation(), Purpose.OPEN);
    }

    // 等菜单出现、可见且鼠标为空，再分清双方库存；完整服务器内容同步的显式等待目前仅用于内部供料。
    // 公开存取走可见性等待后读取当前菜单，不能把这个分支解释为也执行了下方的完整同步检查。
    private TaskState waitMenu() {
        if (player.containerMenu != player.inventoryMenu) {
            if (ownedMenu != null && ownedMenu != player.containerMenu) return menuLost("A different menu replaced the container opened by this task.");
            openedMenu = true;
            var context = ClientRuntime.requireContext(player);
            if (!context.menus().ensureVisible(context)) return TaskState.RUNNING;
            AbstractContainerMenu menu = player.containerMenu;
            if (r.storageSupply() && !StockEvidence.isContainerSynchronized(player, menu)) {
                // 界面刚出现时的空槽可能尚未同步，不能因此判定仓库没货并转去别处找材料。
                if (player.level().getGameTime() - waitMenuSince > MENU_WAIT_TICKS)
                    return failFinal("container_contents_unconfirmed", "The visible storage menu did not receive native item synchronization.", FailureType.TARGET_LOST);
                return TaskState.RUNNING;
            }
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
            ownedMenu = menu;
            expectedContainerId = menu.containerId;
            expectedMenuClass = menu.getClass();
            stableFingerprint = fingerprint(menu);
            initialPlayerCount = count(view.playerSlots());
            initialContainerCount = count(view.containerSlots());
            lastPlayerCount = initialPlayerCount;
            lastContainerCount = initialContainerCount;
            countsObservedAt = player.level().getGameTime();
            rememberContainer(menu);
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

    // 菜单没换、鼠标为空且旁人条件通过后，按此刻真实库存重新计算要搬多少。
    // 即使最终数量已经满足，也是先开箱走到这里才发现不需要搬。
    private TaskState plan() {
        if (!menuValid()) return menuLost("The synchronized container menu changed before planning.");
        if (r.investigationScope == null && otherPlayerNearTarget()) return failFinal("other_player_near_container",
                "Another player approached the open container; MaiCraft paused before moving items.",
                FailureType.ENTITY_BLOCKED);
        if (!player.containerMenu.getCarried().isEmpty()) {
            outcomeUncertain = true;
            return failFinal("menu_cursor_not_empty", "The menu cursor is not empty, so no "
                    + "semantic transfer can start safely.", FailureType.UNKNOWN);
        }
        // 还没有提交点击，箱子自动抽料只更新计划基线，不迫使模型重新开箱或重发整项请求。
        stableFingerprint = fingerprint(player.containerMenu);

        int playerCount = count(view.playerSlots());
        int containerCount = count(view.containerSlots());
        // 等待规划期间可能被漏斗或玩家取空；尚未点击也要用当前同步槽位覆盖旧记忆和回执数量。
        lastPlayerCount = playerCount; lastContainerCount = containerCount; countsObservedAt = player.level().getGameTime();
        rememberContainer(player.containerMenu);
        direction = direction(playerCount);
        plannedAmount = requestedAmount(playerCount, containerCount, direction);
        if (plannedAmount < 0) return TaskState.RUNNING;
        if (plannedAmount == 0) {
            goalSatisfied = goalSatisfied();
            phase = Phase.CLEANUP;
            return TaskState.RUNNING;
        }
        if (plannedAmount > SemanticContainerTaskRecord.MAX_COUNT) {
            return failFinal("transfer_too_large", "The semantic group exceeds the bounded transfer "
                    + "limit; split the request by item group or explicit count.",
                    FailureType.UNSUPPORTED);
        }
        Planning planning = buildPlan(direction, plannedAmount);
        if (r.storageSupply() && r.operation == SemanticContainerTaskRecord.Operation.DEPOSIT
                && !planning.success() && "destination_full_or_locked".equals(planning.failureCode())) {
            // 存余料时先求这只箱子实际装得下多少；已存数量会写入回执，剩余部分交给上层另找箱子。
            int low = 0, high = plannedAmount;
            while (low < high) {
                int amount = low + (high - low + 1) / 2; Planning candidate = buildPlan(direction, amount);
                if (candidate.success()) low = amount;
                else if ("destination_full_or_locked".equals(candidate.failureCode())) high = amount - 1;
                else return failFinal(candidate.failureCode(), candidate.failureMessage(), candidate.failureType());
            }
            plannedAmount = low;
            if (low == 0) { goalSatisfied = false; phase = Phase.CLEANUP; return TaskState.RUNNING; }
            planning = buildPlan(direction, low);
        }
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

    // balance 多了存、少了取；target_count 存入看箱内、取出看主背包，已经达到时只关页，不反向取回超额。
    // count 始终是本次额外搬运量；两者都省略才取来源侧全量，来源为空则如实报缺货。
    private int requestedAmount(int playerCount, int containerCount, Direction selectedDirection) {
        if (r.storageSupply()) return r.operation == SemanticContainerTaskRecord.Operation.DEPOSIT
                ? Math.min(playerCount, r.count) : Math.min(containerCount, Math.max(0, r.targetCount - playerCount));
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

    /** 每次点击前把本次剩余数量完整分配到菜单槽位；容量或权限不足时，不先拿一半到鼠标上。 */
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
                    "The destination cannot safely hold the complete remaining requested amount.", FailureType.NO_SPACE);

            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(source.getItem());
            // 存入自动抽料箱时点名目标槽，复用机器投料的原生确认；快速移动的两侧守恒可能被溜槽即时抽取打断。
            if (selectedDirection == Direction.WITHDRAW && view.quickMoveSafe() && take == source.getCount()) {
                moves.add(new PlannedMove(
                        new ContainerTransferTaskRecord.Move(sourceIndex, -1, 0), itemId, take));
            } else {
                for (Allocation allocation : allocations) {
                    moves.add(new PlannedMove(new ContainerTransferTaskRecord.Move(
                            sourceIndex, allocation.destination(), allocation.count(),
                            selectedDirection == Direction.DEPOSIT
                                    ? ContainerTransferTaskRecord.DestinationMode.MAY_MUTATE_AFTER_DEPOSIT
                                    : ContainerTransferTaskRecord.DestinationMode.EXACT),
                            itemId, allocation.count()));
                }
            }
            simulated.set(sourceIndex, source.getCount() == take
                    ? ItemStack.EMPTY : source.copyWithCount(source.getCount() - take));
            remaining -= take;
        }
        if (remaining != 0) return Planning.fail("transfer_plan_incomplete",
                "A complete safe slot-semantic plan could not be proven for the remaining amount.",
                FailureType.UNSUPPORTED);
        return Planning.ok(moves);
    }

    // 先合并相同种类及组件的已有堆叠，再用空格；同时考虑物品上限和目标槽自己的容量限制。
    private List<Allocation> allocate(ItemStack source, int amount, List<Integer> destinations,
            AbstractContainerMenu menu, List<ItemStack> simulated) {
        List<Allocation> result = new ArrayList<>();
        int remaining = amount;
        // 先填充可兼容的已有堆叠，再占用空槽位。
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
        if (movedCount >= plannedAmount) {
            goalSatisfied = goalSatisfied();
            if (!goalSatisfied && !r.storageSupply()) {
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
        // 尚未提交下一笔点击时，漏斗或机器改变槽位只需按当前库存重新分配；菜单身份、权限和真实容量仍正常核对。
        if (movedCount > 0 || !fingerprint(player.containerMenu).equals(stableFingerprint)) {
            Planning remaining = buildPlan(direction, plannedAmount - movedCount);
            if (!remaining.success()) return failFinal(remaining.failureCode(), remaining.failureMessage(), remaining.failureType());
            plan = remaining.moves(); planIndex = 0;
        }
        pendingMove = plan.get(planIndex);
        beforePlayerCount = count(view.playerSlots());
        beforeContainerCount = count(view.containerSlots());
        beforeItems = itemCounts(view.playerSlots());
        return start(new ContainerTransferTaskRecord(childId("transfer"),
                childDeadline(2L * 60L * 20L), expectedContainerId,
                List.of(pendingMove.move()), false), Purpose.TRANSFER);
    }

    // 每笔原生点击先核对玩家侧精确增减；普通箱子还要双边一致，允许即时消耗的机器存入另按原生证据结算。
    // 确认后才增加 movedCount；箱内留存量和累计已搬运量分别记录，不能把投料成功当成机器已经加工出货。
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
        boolean flowingDeposit = direction == Direction.DEPOSIT && pendingMove.move().destinationMode()
                == ContainerTransferTaskRecord.DestinationMode.MAY_MUTATE_AFTER_DEPOSIT;
        if (playerDelta != expectedPlayerDelta
                || (!flowingDeposit && containerDelta != -expectedPlayerDelta)
                || playerItemDelta != expectedPlayerDelta) {
            outcomeUncertain = true;
            return failFinal("transfer_delta_diverged", "The real menu did not show the exact "
                    + "player delta and the destination evidence required by this native transfer. "
                    + "Blind retry was stopped.", FailureType.UNKNOWN);
        }
        effectsStarted = true;
        movedCount += pendingMove.count();
        movedByItem.merge(itemId, pendingMove.count(), Integer::sum);
        lastPlayerCount = afterPlayer;
        lastContainerCount = afterContainer;
        countsObservedAt = player.level().getGameTime();
        stableFingerprint = fingerprint(player.containerMenu);
        rememberContainer(player.containerMenu);
        pendingMove = null;
        planIndex++;
        r.extendDeadlineTo(player.level().getGameTime() + TRANSFER_PROGRESS_LEASE_TICKS);
        phase = Phase.TRANSFER;
        return TaskState.RUNNING;
    }

    private void rememberContainer(AbstractContainerMenu menu) {
        // 菜单未获完整原生同步时不刷新世界记忆，避免把界面初始化的默认空槽记成缺货。
        if (!StockEvidence.isContainerSynchronized(player, menu)) return;
        ContainerSupplySources.rememberVisible(player, target.position(), menu, view.containerSlots());
        var memory = ContainerSupplySources.memory(player, target.position());
        // 保存本次实际观察的回执，关箱后目标卸载或被拆除也不能抹去已经确认的库存事实。
        if (memory != null) lastContainerMemory = memory.receipt();
    }

    // 最后按用户目标检查：balance 要背包恰好等于目标；补足模式要求目的侧至少达到目标；普通搬运要求累计量相等。
    private boolean goalSatisfied() {
        if (r.storageSupply() && r.operation == SemanticContainerTaskRecord.Operation.DEPOSIT) return movedCount >= r.count;
        if (r.operation == SemanticContainerTaskRecord.Operation.BALANCE) {
            return lastPlayerCount == r.targetCount;
        }
        if (r.targetCount != null) {
            return r.operation == SemanticContainerTaskRecord.Operation.DEPOSIT
                    ? lastContainerCount >= r.targetCount : lastPlayerCount >= r.targetCount;
        }
        return movedCount == plannedAmount;
    }

    // 搬运与关页分开结算；菜单余物交原生返还，旧页面替换不再要求模型另开关页任务。
    private TaskState cleanupMenu() {
        var context = ClientRuntime.requireContext(player);
        if (!context.menus().ensureWorldVisible(context)) return TaskState.RUNNING;
        openedMenu = false; openRequested = false; phase = Phase.COMPLETE;
        return TaskState.RUNNING;
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
        // 只累计游戏已确认的分堆点击，便于核对少量拆半操作就拿齐材料，而不是按计划步数宣称变快。
        if (purpose == Purpose.TRANSFER && result != null && result.data() != null
                && result.data().get("confirmed_split_clicks") instanceof Number clicks)
            confirmedSplitClicks += Math.max(0, clicks.intValue());
        if (purpose == Purpose.TRANSFER) {
            // 失败也带回底层点击原因和当时实际库存，避免沿用开箱时的旧计数误导模型重复投料。
            // 失败子任务不会进入 verifyTransfer；它已确认的部分量仍在 lastNativeTransfer，本层 movedCount 可能尚未增加。
            Map<String, Object> evidence = result == null || result.data() == null ? Map.of() : result.data();
            lastNativeTransfer = Map.of("message", result == null ? "missing native transfer result" : String.valueOf(result.message()),
                    "data", evidence);
            effectsStarted |= Boolean.TRUE.equals(evidence.get("effects_started"));
            outcomeUncertain |= result == null || result.data() == null || Boolean.TRUE.equals(evidence.get("outcome_uncertain"));
            if (menuValid()) {
                lastPlayerCount = count(view.playerSlots()); lastContainerCount = count(view.containerSlots());
                countsObservedAt = player.level().getGameTime();
            }
        }
        activeChild = null;
        activeRecord = null;
        activePurpose = null;
        boolean success = terminal == TaskState.SUCCESS && result != null && result.success();
        if (!success) {
            return switch (purpose) {
                case OPEN -> {
                    if (result != null && result.data() != null) outcomeUncertain |= Boolean.TRUE.equals(result.data().get("outcome_uncertain"));
                    openFailureMessage = result == null || result.message() == null ? "no opening child result" : result.message();
                    if (openFailureMessage.length() > 512) openFailureMessage = openFailureMessage.substring(0, 512);
                    if (player.containerMenu != player.inventoryMenu) {
                        openedMenu = true;
                        yield failFinal("container_open_unconfirmed", "A menu appeared but the "
                                + "native open receipt was not confirmed: " + openFailureMessage, lastFailure());
                    }
                    yield failFinal("container_interaction_failed", "The selected container could "
                            + "not be opened through ordinary first-person interaction: " + openFailureMessage,
                            lastFailure());
                }
                case TRANSFER -> {
                    yield failFinal("container_transfer_unconfirmed", "Native container transfer did not settle: "
                            + (result == null ? "missing result" : result.message()),
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
                if (player.containerMenu != player.inventoryMenu) { ownedMenu = player.containerMenu; openedMenu = true; }
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

    // 更换后的菜单不属于本任务，保留它并报告已开始的转移是否不确定。
    private TaskState menuLost(String message) {
        outcomeUncertain |= effectsStarted;
        openedMenu = ownedMenu != null && player.containerMenu == ownedMenu;
        if (!openedMenu) openRequested = false;
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
        if (r.storageSupply()) {
            // 走去开箱和继续搬料前都核对原箱子身份，不能在箱子被替换后仍沿用旧库存和旧许可。
            if (!ContainerSupplySources.allowed(player, target.position(), r.protectedLabels)) return false;
            var footprint = ContainerSupplySources.footprint(player.level(), target.position());
            if (!supplyIdentities.keySet().equals(Set.copyOf(footprint)) || supplyIdentities.entrySet().stream()
                    .anyMatch(entry -> player.level().getBlockEntity(entry.getKey()) != entry.getValue())) return false;
        }
        BlockEntity entity = player.level().getBlockEntity(target.position());
        if (!(entity instanceof Container)) return false;
        var state = player.level().getBlockState(target.position());
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(target.blockId())) return false;
        return state.getMenuProvider(player.level(), target.position()) != null;
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

    // 选择器只按物品类型或标签累计，附魔、名称等组件不会在这里排除；逐笔落槽时仍须保持原堆组件身份。
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

    // 取消、死亡等终止路径停止子任务并尝试结清自有菜单；鼠标仍有物品时保留界面和未知状态供核查。
    // 当前这里只调用子任务 stop，没有取得其 result；途中取消的完整搬运证据不能保证都已汇入本层计数。
    @Override protected void cleanup() {
        stopNav();
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            activeChild = null;
            activeRecord = null;
            activePurpose = null;
        }
        if ((openedMenu || openRequested) && player.containerMenu != player.inventoryMenu
                && (ownedMenu == null || player.containerMenu == ownedMenu)) {
            if (!player.containerMenu.getCarried().isEmpty()) { outcomeUncertain = true; super.cleanup(); return; }
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
        data.put("menu_reused", reusedMenu);
        if (r.investigationScope != null) {
            data.put("container_search_scope", r.investigationScope.receipt());
            data.put("container_memory_before", r.priorMemoryState);
        }
        if (containerKind != null) data.put("container_kind", containerKind);
        // 箱体坐标作为与蓝图一致的数组交付，经过旧定位字段整理后仍保留实际选箱身份。
        if (target != null) data.put("container_observation", Map.of("block_id", target.blockId().toString(),
                "dimension", targetDimension, "coordinates", List.of(target.position().getX(), target.position().getY(), target.position().getZ()),
                "observed_at_tick", targetObservedAt, "menu_observed", expectedContainerId >= 0));
        // 尚未开箱就寻路失败时没有读过两侧槽位，默认零值不能冒充已观察到库存为零。
        data.put("inventory_counts_observation_status", countsObservedAt < 0 ? "not_observed" : "observed");
        if (countsObservedAt >= 0) {
            // 开箱和取物后的整份实际库存直接随回执交付，空箱也带稳定标识及更新时间。
            if (!lastContainerMemory.isEmpty()) data.put("container_memory", lastContainerMemory);
            data.put("initial_main_count", initialPlayerCount);
            data.put("observed_final_main_count", lastPlayerCount);
            data.put("initial_container_count", initialContainerCount);
            data.put("observed_final_container_count", lastContainerCount);
            data.put("inventory_counts_observed_at_tick", countsObservedAt);
        }
        if (!lastNativeTransfer.isEmpty()) data.put("last_native_transfer", lastNativeTransfer);
        data.put("moved_count", movedCount);
        // 累计投料与箱内现存量分开解释；物品被后续设备抽走，不会撤销已经确认的存入，也不证明加工完成。
        data.put("moved_count_scope", "cumulative_confirmed_native_transfers");
        data.put("container_count_scope", "remaining_matching_items_at_inventory_counts_observed_at_tick");
        data.put("moved_items", Map.copyOf(movedByItem));
        data.put("goal_satisfied", goalSatisfied);
        data.put("outcome_partial", movedCount > 0 && !goalSatisfied);
        data.put("outcome_uncertain", outcomeUncertain);
        data.put("effects_started", effectsStarted);
        data.put("confirmed_split_clicks", confirmedSplitClicks);
        if (r.storageSupply()) data.put(r.operation == SemanticContainerTaskRecord.Operation.DEPOSIT
                ? "bounded_storage_deposit" : "bounded_storage_withdrawal", true);
        if (r.count != null) data.put("requested_count", r.count);
        if (r.targetCount != null) data.put("target_count", r.targetCount);
        if (failureCode != null) {
            data.put("failure_code", failureCode);
            data.put("decision", Map.of("required", true, "reason_code", failureCode,
                    "recovery_options", recoveryOptions(failureCode, movedCount > 0)));
        }
        if (openFailureMessage != null) data.put("open_failure_message", openFailureMessage);
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

    /** 面板行动行的一句话汇报；容器名取任务单的目标容器，方向按存取方向分句，搬运计数是已确认的数量。 */
    @Override public String describeCurrentAction() {
        if (activeChild != null) {
            String deeper = activeChild.describeCurrentAction();
            if (deeper != null) return deeper;
        }
        String verb = direction == Direction.DEPOSIT ? "存入" : "取出";
        String where = target == null ? "容器" : containerKind == null
                ? "(" + target.position().getX() + "," + target.position().getY() + "," + target.position().getZ() + ")"
                : containerKind + " (" + target.position().getX() + "," + target.position().getZ() + ")";
        return switch (phase) {
            case SURVEY -> "正在寻找可用的" + where;
            case OPEN, WAIT_MENU -> "正在打开" + where;
            case PLAN -> "正在规划存取数量";
            case TRANSFER -> "正在把物品" + verb + where + " (已" + verb + movedCount + " 件)";
            case CLEANUP -> "正在关闭容器界面";
            case COMPLETE -> "正在收尾容器存取";
        };
    }

    @Override protected String successMessage() {
        // 成功摘要也直接给出留箱量，避免模型把自动抽料后的数量变化误认为本次投料失效。
        if (direction == Direction.DEPOSIT) return "Confirmed native deposit of " + movedCount
                + " item(s) into " + containerKind + "; the container held " + lastContainerCount
                + " matching item(s) at observation. Downstream processing remains unverified; the native menu is closed.";
        return "verified " + movedCount + " semantic item(s) against "
                + (containerKind == null ? "the selected container" : containerKind)
                + " and closed its native menu";
    }

    @Override public boolean mustSettleBeforeSatisfiedCancellation() {
        return openedMenu || openRequested && player.containerMenu != player.inventoryMenu
                || activeChild != null && activeChild.mustSettleBeforeSatisfiedCancellation();
    }
    @Override public void requestSatisfiedSettlement() { satisfiedSettlement = true; }

    @Override protected String timeoutMessage() {
        return "container management stopped making verified progress after " + movedCount
                + " verified item(s); no unverified retry was attempted";
    }

    @Override protected String cancelledMessage() {
        return "container management was interrupted after " + movedCount
                + " verified item(s)";
    }
}
