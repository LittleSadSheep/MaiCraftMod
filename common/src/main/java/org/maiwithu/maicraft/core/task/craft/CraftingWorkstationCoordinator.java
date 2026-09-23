// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.craft;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CraftingTableBlock;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.build.FirstPersonPlacementProbe;

/**
 * 为需要 3×3 的合成找工作台：能用身边的就用，远处已有的可以走过去，也可以摆出背包里的工作台。
 * 这里只决定下一步，不自己移动或摆方块；缺工作台物品时把需求交回取物流程。
 * 当前通过 CraftingTableBlock 及其子类识别可自动寻找的工作台，不是识别全部能提供合成界面的模组方块。
 */
public final class CraftingWorkstationCoordinator {
    private static final double REACH = 4.5D;
    private static final int INDEX_SECTIONS_PER_QUERY = 32;
    private static final int PLACEMENT_RADIUS = 5;

    /**
     * 记录同伴在当前客户端世界中放置的临时工作台。
     *
     * <p>此账本跟踪实际方块资产，而不是绑定单个任务。合成任务可能在放置后被抢占，下一项合成仍需知道这张工作台可安全回收。
     * 弱引用世界键可避免坐标泄漏到其他存档或会话；每次查找都会重新读取已加载的世界格，再确认所有权。</p>
     */
    private static final Map<ClientLevel, Map<Long, Block>> TEMPORARY_TABLES =
            new WeakHashMap<>();

    public enum Action {
        READY,
        MOVE_TO_EXISTING,
        PLACE_CARRIED,
        SEARCHING,
        UNAVAILABLE
    }

    public record Directive(
            Action action, BlockPos position, Block workstationBlock, String detail) {
        public Directive {
            position = position == null ? null : position.immutable();
        }

        public Directive(Action action, BlockPos position, String detail) {
            this(action, position, null, detail);
        }
    }

    public record PlanningSnapshot(
            CraftPlanCost.Surface surface,
            BlockPos station,
            List<ResourceLocation> prerequisiteItemIds,
            String detail) {
        public PlanningSnapshot {
            station = station == null ? null : station.immutable();
            prerequisiteItemIds = prerequisiteItemIds == null
                    ? List.of() : List.copyOf(prerequisiteItemIds);
        }

        public PlanningSnapshot(
                CraftPlanCost.Surface surface, BlockPos station, String detail) {
            this(surface, station, List.of(), detail);
        }
    }

    private final Set<Long> rejectedStations = new LinkedHashSet<>();
    private final Set<Long> rejectedSites = new LinkedHashSet<>();
    private ClientLevel indexedLevel;
    private List<Block> indexedTables = List.of();

    /** 在一次实体合成任务期间持续维护共享稀疏方块索引。 */
    public void start(LocalPlayer player) {
        ClientLevel level = player.clientLevel;
        if (indexedLevel == level) return;
        close();
        indexedTables = craftingTableBlocks();
        TargetIndex.register(level, indexedTables);
        indexedLevel = level;
    }

    public void close() {
        if (indexedLevel == null) return;
        TargetIndex.unregister(indexedLevel, indexedTables);
        indexedLevel = null;
        indexedTables = List.of();
    }

    /** 登记供后续回收的临时工作台；这里只查当前位置可用，放置来源是否可信必须由调用方保证。 */
    public static synchronized void rememberTemporaryTable(
            LocalPlayer player, BlockPos pos) {
        if (!usableTable(player, pos)) return;
        Block live = player.level().getBlockState(pos).getBlock();
        TEMPORARY_TABLES.computeIfAbsent(player.clientLevel, ignored -> new LinkedHashMap<>())
                .put(pos.asLong(), live);
    }

    /**
     * 返回 {@code pos} 上准确的临时工作台方块；若不属于本模组则返回 {@code null}。
     * 若已加载的方块与记录不符，就撤销过期所有权，不能授权后续任务破坏玩家后来放在相同坐标的方块。
     */
    public static synchronized Block temporaryTable(LocalPlayer player, BlockPos pos) {
        // 读取时只核对方块类型；若别人换成同类型工作台，这份记录目前无法区分。
        if (pos == null) return null;
        Map<Long, Block> entries = TEMPORARY_TABLES.get(player.clientLevel);
        if (entries == null) return null;
        Block expected = entries.get(pos.asLong());
        if (expected == null) return null;
        if (!player.level().isLoaded(pos)) return expected;
        Block live = player.level().getBlockState(pos).getBlock();
        if (live == expected && live instanceof CraftingTableBlock) return live;
        entries.remove(pos.asLong());
        if (entries.isEmpty()) TEMPORARY_TABLES.remove(player.clientLevel);
        return null;
    }

    /** 工作台消失且其物品已回收或确认丢失后，撤销所有权记录。 */
    public static synchronized void forgetTemporaryTable(
            LocalPlayer player, BlockPos pos) {
        if (pos == null) return;
        Map<Long, Block> entries = TEMPORARY_TABLES.get(player.clientLevel);
        if (entries == null) return;
        entries.remove(pos.asLong());
        if (entries.isEmpty()) TEMPORARY_TABLES.remove(player.clientLevel);
    }

    /** 供配方排序共用的只读可行性快照。 */
    public static PlanningSnapshot inspect(LocalPlayer player) {
        // 给合成规划一个只读结论：工作台已就绪、可走近／摆放、需要先取得物品，或还在搜索。
        List<Block> tables = craftingTableBlocks();
        TargetIndex.register(player.clientLevel, tables);
        TableSearch search;
        try {
            search = nearestIndexedTable(player, Set.of(), tables);
        } finally {
            TargetIndex.unregister(player.clientLevel, tables);
        }
        BlockPos station = search.station();
        if (station != null && withinReach(player, station)) {
            return new PlanningSnapshot(
                    CraftPlanCost.Surface.READY, station,
                    "a loaded crafting table is within first-person reach");
        }
        Block carried = carriedTable(player);
        if (carried != null) {
            BlockPos site = placementSite(player, Set.of(), carried);
            if (site != null) {
                return new PlanningSnapshot(
                        CraftPlanCost.Surface.PREPARABLE, null,
                        "a carried crafting table can be placed on a verified nearby support");
            }
        }
        if (station != null) {
            return new PlanningSnapshot(
                    CraftPlanCost.Surface.PREPARABLE, station,
                    "an existing loaded crafting table can be approached");
        }
        if (!search.complete()) {
            return new PlanningSnapshot(CraftPlanCost.Surface.SEARCHING, null,
                    "the loaded crafting-table search is continuing on the next client tick");
        }
        if (carried != null) {
            return new PlanningSnapshot(CraftPlanCost.Surface.UNAVAILABLE, null,
                    "a crafting table is carried, but no nearby placement cell or loaded table is available");
        }
        BlockPos site = placementSite(player, Set.of(), Blocks.CRAFTING_TABLE);
        if (site == null) {
            return new PlanningSnapshot(
                    CraftPlanCost.Surface.UNAVAILABLE, null,
                    "no table is available and no verified nearby placement cell could receive one");
        }
        return new PlanningSnapshot(
                CraftPlanCost.Surface.PREREQUISITE, null,
                craftingTableItemIds(),
                "no loaded or carried crafting table is available; semantic acquisition may "
                        + "materialize the required workstation item before this recipe resumes");
    }

    /** 根据最新客户端事实，解析下一步有界的实际操作。 */
    public Directive next(LocalPlayer player, BlockPos preferredStation) {
        // 身边可用的已有台优先；否则有随身台且能摆就先摆，最后才考虑走较远的路或继续搜索。
        start(player);
        BlockPos preferred = usableTable(player, preferredStation)
                        && !rejectedStations.contains(preferredStation.asLong())
                ? preferredStation : null;
        TableSearch search = nearestIndexedTable(player, rejectedStations, indexedTables);
        BlockPos existing = search.station();

        // 附近已有可用工作台时，保留背包里携带的工作台供后续使用。
        if (preferred != null && withinReach(player, preferred)) {
            Block preferredBlock = player.level().getBlockState(preferred).getBlock();
            return new Directive(Action.READY, preferred, preferredBlock,
                    "the selected crafting table is within reach");
        }
        if (existing != null && withinReach(player, existing)) {
            Block existingBlock = player.level().getBlockState(existing).getBlock();
            return new Directive(Action.READY, existing, existingBlock,
                    "a loaded crafting table is within reach");
        }

        // 若最近的已知工作台需要长途行进，优先使用已经携带的工作台会更安全、更省成本；放置过程仍通过第一人称操作并逐步核实。
        Block carried = carriedTable(player);
        if (carried != null) {
            BlockPos site = placementSite(player, rejectedSites, carried);
            if (site != null) {
                return new Directive(Action.PLACE_CARRIED, site, carried,
                        "place the carried crafting table through first-person building");
            }
        }

        if (preferred != null) {
            Block preferredBlock = player.level().getBlockState(preferred).getBlock();
            return new Directive(Action.MOVE_TO_EXISTING, preferred, preferredBlock,
                    "approach the selected loaded crafting table");
        }
        if (existing != null) {
            Block existingBlock = player.level().getBlockState(existing).getBlock();
            return new Directive(Action.MOVE_TO_EXISTING, existing, existingBlock,
                    "approach the nearest loaded crafting table");
        }
        if (!search.complete()) {
            return new Directive(Action.SEARCHING, null,
                    "continue the bounded loaded crafting-table search on the next tick");
        }
        if (carried == null) {
            return new Directive(Action.UNAVAILABLE, null,
                    "no loaded or carried crafting table is available; semantic acquisition must "
                            + "materialize one before physical placement");
        }
        return new Directive(Action.UNAVAILABLE, null,
                "no verified nearby cell can receive the carried crafting table");
    }

    /** 只排除失败的实际路线；其他工作台或放置位置仍可继续尝试。 */
    public void reject(Directive directive) {
        // 失败的工作台和失败的摆放位置分别记，不能一个位置摆不下就把所有工作台都判为不可用。
        if (directive == null) return;
        switch (directive.action()) {
            case READY, MOVE_TO_EXISTING -> {
                if (directive.position() != null) {
                    rejectedStations.add(directive.position().asLong());
                }
            }
            case PLACE_CARRIED -> {
                if (directive.position() != null) {
                    rejectedSites.add(directive.position().asLong());
                }
            }
            case SEARCHING, UNAVAILABLE -> { }
        }
    }

    public static boolean withinReach(LocalPlayer player, BlockPos pos) {
        if (pos == null) return false;
        Vec3 eye = player.getEyePosition();
        double nearestX = Math.clamp(eye.x, pos.getX(), pos.getX() + 1.0D);
        double nearestY = Math.clamp(eye.y, pos.getY(), pos.getY() + 1.0D);
        double nearestZ = Math.clamp(eye.z, pos.getZ(), pos.getZ() + 1.0D);
        return eye.distanceToSqr(nearestX, nearestY, nearestZ) <= REACH * REACH;
    }

    public static boolean usableTable(LocalPlayer player, BlockPos pos) {
        return pos != null && player.level().isLoaded(pos)
                && player.level().getBlockState(pos).getBlock() instanceof CraftingTableBlock;
    }

    private static Block carriedTable(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int limit = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() instanceof CraftingTableBlock) {
                return blockItem.getBlock();
            }
        }
        return null;
    }

    private record TableSearch(BlockPos station, boolean complete) {}

    private static TableSearch nearestIndexedTable(
            LocalPlayer player, Set<Long> rejected, List<Block> tables) {
        // 多查“已拒绝数量加一”个候选，过滤失败位置后仍能找到下一个，而不是一直拿同一个最近点。
        BlockPos origin = player.blockPosition();
        int wantedCandidates = rejected.size() + 1;
        TargetIndex.Result result = TargetIndex.query(
                player.clientLevel, origin, tables,
                wantedCandidates, 2, INDEX_SECTIONS_PER_QUERY);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : result.hits()) {
            if (rejected.contains(candidate.asLong())
                    || !usableTable(player, candidate)) {
                continue;
            }
            double distance = origin.distSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.immutable();
            }
        }
        return new TableSearch(best, result.complete());
    }

    private static List<Block> craftingTableBlocks() {
        List<Block> result = BuiltInRegistries.BLOCK.stream()
                .filter(block -> block instanceof CraftingTableBlock)
                .toList();
        return result.isEmpty() ? List.of(Blocks.CRAFTING_TABLE) : result;
    }

    private static List<ResourceLocation> craftingTableItemIds() {
        return craftingTableBlocks().stream()
                .map(Block::asItem)
                .filter(item -> item != Items.AIR)
                .filter(item -> BuiltInRegistries.ITEM.getKey(item) != null)
                .map(BuiltInRegistries.ITEM::getKey)
                .distinct()
                .toList();
    }

    /** 可实际提供普通 3×3 合成台面的语义物品候选。 */
    public static List<ResourceLocation> prerequisiteItemIds() {
        return craftingTableItemIds();
    }

    private static BlockPos placementSite(
            LocalPlayer player, Set<Long> rejected, Block workstation) {
        // 先围绕玩家当前高度找附近地板，再补查地表高度；这样普通洞穴也能有摆工作台的位置。
        BlockPos origin = PlayerNav.playerFeet(player);
        for (int radius = 1; radius <= PLACEMENT_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos cell = origin.offset(dx, dy, dz);
                        if (!rejected.contains(cell.asLong())
                                && validSite(player, cell, workstation)) return cell;
                    }
                    int surfaceY = ClientSurfaceHeight.motionBlockingNoLeaves(
                            player.clientLevel,
                            origin.getX() + dx, origin.getZ() + dz);
                    BlockPos surface = new BlockPos(
                            origin.getX() + dx, surfaceY, origin.getZ() + dz);
                    if (!rejected.contains(surface.asLong())
                            && validSite(player, surface, workstation)) {
                        return surface;
                    }
                }
            }
        }
        return null;
    }

    private static boolean validSite(
            LocalPlayer player, BlockPos cell, Block workstation) {
        // 当前要求可替换、无液体、不占玩家身体、上方有空隙、下方有支撑，并且已有能放置它的站位。
        BlockPos feet = PlayerNav.playerFeet(player);
        if (!player.level().isLoaded(cell)
                || !player.level().getBlockState(cell).canBeReplaced()
                || !player.level().getFluidState(cell).isEmpty()
                || cell.equals(feet)
                || cell.equals(feet.above())
                || player.getBoundingBox().intersects(new AABB(cell))) {
            return false;
        }
        // 工作台不只是能放下的方块；放置后玩家还必须能看见并使用它。避免把临时工作台塞在树叶、原木、天花板或液体正下方，同时允许放在普通洞穴地面。
        BlockPos clearance = cell.above();
        if (!player.level().isLoaded(clearance)
                || !player.level().getBlockState(clearance)
                        .getCollisionShape(player.level(), clearance).isEmpty()
                || !player.level().getFluidState(clearance).isEmpty()) {
            return false;
        }
        BlockPos support = cell.below();
        return player.level().isLoaded(support)
                && player.level().getBlockState(support)
                        .isFaceSturdy(player.level(), support, Direction.UP)
                && FirstPersonPlacementProbe.hasExistingStance(player, workstation, cell);
    }
}
