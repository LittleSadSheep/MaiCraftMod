// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 建完后只读检查入口、房间、楼层连接和码头是否符合通行要求，不让角色实际走一遍。
 * 检查分多次游戏更新推进。它使用下方自己的格子通行判断，不能等同于原版完整身体碰撞或导航。
 */
public final class BuildTraversabilityVerifier {
    private BuildTraversabilityVerifier() {}

    public record Result(boolean valid, String code, String message, BlockPos position,
                         Map<String, Object> evidence) {
        public Result {
            position = position == null ? null : position.immutable();
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        }
    }

    public static Verification begin(ClientLevel level, BuildTraversabilityContract contract) {
        return begin(level, level::isLoaded, contract);
    }

    static Verification begin(BlockGetter level, Predicate<BlockPos> loaded,
                              BuildTraversabilityContract contract) {
        return new Verification(new World(level, loaded), contract);
    }

    private record World(BlockGetter level, Predicate<BlockPos> loaded) {
        boolean isLoaded(BlockPos pos) { return loaded.test(pos); }
        BlockState getBlockState(BlockPos pos) { return level.getBlockState(pos); }
    }

    /** One task keeps one session. A null tick result means the scan has yielded, not failed. */
    public static final class Verification {
        private enum Phase { INITIAL, ENTRANCE, BOUNDS, VERTICAL, FLOOD, WAYPOINTS, DOCK, DONE }
        private static final int CELLS_PER_TICK = 128;
        private static final long SLICE_NANOS = 2_000_000L;
        private final World level;
        private final BuildTraversabilityContract contract;
        private Set<Long> reachable = new LinkedHashSet<>();
        private ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        private Phase phase = Phase.INITIAL;
        private BuildTraversabilityContract.Bounds bounds;
        private BlockPos inside, lineCursor, lineEnd;
        private int chunkX, chunkZ, firstChunkZ, lastChunkX, lastChunkZ;
        private int verticalY, waypointIndex, dockLength;
        private Result result;

        private Verification(World level, BuildTraversabilityContract contract) {
            this.level = level;
            this.contract = contract;
        }

        public Result tick() { return tick(CELLS_PER_TICK); }

        // 一次最多推进指定数量的步骤，并在步骤之间检查约两毫秒预算；null 表示还没检查完，完成后一直返回同一结果。
        Result tick(int cellBudget) {
            if (cellBudget < 1) throw new IllegalArgumentException("positive scan budget required");
            long started = System.nanoTime();
            for (int i = 0; i < cellBudget && result == null; i++) {
                advance();
                if (System.nanoTime() - started >= SLICE_NANOS) break;
            }
            return result;
        }

        private void advance() {
            switch (phase) {
                case INITIAL -> initialize();
                case ENTRANCE -> {
                    if (scanWalkingCell("entrance_approach_blocked")) {
                        chunkX = Math.floorDiv(bounds.minX(), 16);
                        chunkZ = firstChunkZ = Math.floorDiv(bounds.minZ(), 16);
                        lastChunkX = Math.floorDiv(bounds.maxX(), 16);
                        lastChunkZ = Math.floorDiv(bounds.maxZ(), 16);
                        phase = Phase.BOUNDS;
                    }
                }
                case BOUNDS -> scanChunk();
                case VERTICAL -> scanVerticalCell();
                case FLOOD -> expandInteriorCell();
                case WAYPOINTS -> scanWaypoint();
                case DOCK -> { if (scanWalkingCell("dock_walkway_blocked")) finish(); }
                case DONE -> { }
            }
        }

        // 没有要求时返回“未要求验证”；有要求时先核对必要端点、范围和门的上下两半。
        private void initialize() {
            if (contract == null) { result = ok(Map.of("contract", "not_requested")); return; }
            if (contract.exteriorApproach() == null || contract.entranceDoor() == null
                    || contract.interiorEntry() == null || contract.interiorBounds() == null) {
                fail("invalid_traversability_contract", "the internal traversability contract is incomplete", null);
                return;
            }
            bounds = contract.interiorBounds();
            if (bounds.minX() > bounds.maxX() || bounds.minY() > bounds.maxY()
                    || bounds.minZ() > bounds.maxZ()) {
                fail("invalid_traversability_bounds", "the internal traversability bounds are empty", null);
                return;
            }
            BlockPos outside = contract.exteriorApproach().pos();
            BlockPos door = contract.entranceDoor().pos();
            inside = contract.interiorEntry().pos();
            for (BlockPos pos : new BlockPos[]{outside, door, door.above(), inside}) {
                if (!level.isLoaded(pos)) {
                    fail("traversability_observation_unloaded", "a required route cell could not be observed", pos);
                    return;
                }
            }
            BlockState lower = level.getBlockState(door), upper = level.getBlockState(door.above());
            if (!(lower.getBlock() instanceof DoorBlock) || !(upper.getBlock() instanceof DoorBlock)
                    || lower.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER
                    || upper.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER) {
                fail("entrance_door_missing", "the planned exterior entrance is not a complete two-block door", door);
                return;
            }
            if (!adjacentAcrossDoor(outside, door, inside) || !standable(level, inside)) {
                fail("entrance_crossing_blocked", "the outside approach, door and interior landing do not form one usable crossing", inside);
                return;
            }
            if (!bounds.contains(inside)) {
                fail("invalid_interior_entry", "the verified doorway does not enter the bounded interior", inside);
                return;
            }
            if (startWalkingLine(outside, door)) phase = Phase.ENTRANCE;
        }

        // 入口和码头只支持同层直线，不会在这里自动绕障碍或搜索台阶路线。
        private boolean startWalkingLine(BlockPos from, BlockPos to) {
            if (from.getY() != to.getY() || from.getX() != to.getX() && from.getZ() != to.getZ()) {
                fail("invalid_straight_path_contract", "a planner-authored entrance or dock path is not a straight level line", from);
                return false;
            }
            lineCursor = from;
            lineEnd = to;
            return true;
        }

        private boolean scanWalkingCell(String code) {
            if (!level.isLoaded(lineCursor)) {
                fail(code, "a required walking path is not fully loaded for observation", lineCursor);
                return false;
            }
            if (!standable(level, lineCursor)) {
                fail(code, "a required walking path has no solid floor or two-block clearance", lineCursor);
                return false;
            }
            if (phase == Phase.DOCK) dockLength++;
            if (lineCursor.equals(lineEnd)) return true;
            lineCursor = lineCursor.offset(Integer.compare(lineEnd.getX(), lineCursor.getX()), 0,
                    Integer.compare(lineEnd.getZ(), lineCursor.getZ()));
            return false;
        }

        // 逐一确认室内范围覆盖的区块已加载，全部通过后才检查楼层连接或开始查室内连通。
        private void scanChunk() {
            BlockPos pos = new BlockPos(Math.max(bounds.minX(), chunkX * 16), bounds.minY(),
                    Math.max(bounds.minZ(), chunkZ * 16));
            if (!level.isLoaded(pos)) {
                fail("interior_observation_unloaded", "the complete bounded interior could not be observed", pos);
                return;
            }
            if (chunkZ < lastChunkZ) { chunkZ++; return; }
            if (chunkX < lastChunkX) { chunkX++; chunkZ = firstChunkZ; return; }
            var link = contract.verticalLink();
            if (link == null) { beginFlood(); return; }
            if (link.bottomY() > link.topY()) {
                fail("invalid_vertical_link", "the vertical connector has inverted bounds", null);
                return;
            }
            verticalY = link.bottomY();
            phase = Phase.VERTICAL;
        }

        // 沿要求的同一列逐格检查：必须可攀爬、身体位置通过本类检查，有朝向时背后还要有承重面。
        private void scanVerticalCell() {
            var link = contract.verticalLink();
            BlockPos pos = new BlockPos(link.x(), verticalY, link.z());
            if (!bounds.contains(pos) || !level.isLoaded(pos)) {
                fail("vertical_link_unloaded", "the complete storey connector could not be observed", pos);
                return;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.is(BlockTags.CLIMBABLE) || !bodyClear(level, pos)
                    || !supportedClimbable(level, pos, state)) {
                fail("vertical_link_broken", "the storeys are not joined by one continuous supported climbable column", pos);
                return;
            }
            if (verticalY == link.topY()) beginFlood();
            else verticalY++;
        }

        // 从进门后的第一格开始扩展可达位置，下一次更新从保存的队列继续，不重复扫描入口。
        private void beginFlood() {
            if (!standable(level, inside)) {
                fail("interior_entry_blocked", "the cell immediately inside the door is not a valid standing place", inside);
                return;
            }
            reachable.add(inside.asLong());
            frontier.addLast(inside);
            phase = Phase.FLOOD;
        }

        // 每格尝试四个水平方向；只有当前或相邻格可攀爬时才尝试上下移动，没有普通台阶上下跨格规则。
        private void expandInteriorCell() {
            if (frontier.isEmpty()) { phase = Phase.WAYPOINTS; return; }
            BlockPos current = frontier.removeFirst();
            for (Direction direction : Direction.Plane.HORIZONTAL) addReachable(current.relative(direction));
            BlockState state = level.getBlockState(current);
            BlockPos above = current.above(), below = current.below();
            if (state.is(BlockTags.CLIMBABLE)
                    || bounds.contains(above) && level.getBlockState(above).is(BlockTags.CLIMBABLE)) addReachable(above);
            if (state.is(BlockTags.CLIMBABLE)
                    || bounds.contains(below) && level.getBlockState(below).is(BlockTags.CLIMBABLE)) addReachable(below);
        }

        private void addReachable(BlockPos pos) {
            if (!bounds.contains(pos) || reachable.contains(pos.asLong()) || !standable(level, pos)) return;
            reachable.add(pos.asLong());
            frontier.addLast(pos);
        }

        // 每个指定房间或楼层落脚点都必须在范围内、可站且已从入口连到；完成后再单独检查码头中心线。
        private void scanWaypoint() {
            if (waypointIndex < contract.floorWaypoints().size()) {
                var cell = contract.floorWaypoints().get(waypointIndex++);
                if (cell == null) {
                    fail("invalid_floor_waypoint", "the internal floor waypoint list contains an empty entry", null);
                    return;
                }
                BlockPos waypoint = cell.pos();
                if (!bounds.contains(waypoint) || !standable(level, waypoint)
                        || !reachable.contains(waypoint.asLong())) {
                    fail("interior_floor_unreachable", "an intended room or storey has no continuous walk/climb route from the entrance", waypoint);
                }
                return;
            }
            var dock = contract.dockPath();
            if (dock == null) { finish(); return; }
            if (dock.houseSide() == null || dock.deckEnd() == null) {
                fail("invalid_dock_contract", "the internal dock path is incomplete", null);
                return;
            }
            if (startWalkingLine(dock.houseSide().pos(), dock.deckEnd().pos())) phase = Phase.DOCK;
        }

        // 只有前面各阶段都通过才输出已验证的连接数量；结果描述的是这些检查项，不是实走完成。
        private void finish() {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("status", "verified");
            evidence.put("basis", "read_only_collision_and_connectivity_scan_of_the_finished_world");
            evidence.put("entrance_crossing", true);
            evidence.put("reachable_floor_waypoints", waypointIndex);
            evidence.put("reachable_interior_cells", reachable.size());
            evidence.put("vertical_link", contract.verticalLink() == null ? "not_required" : "continuous");
            evidence.put("dock_centerline_cells", dockLength);
            result = ok(evidence);
            phase = Phase.DONE;
            frontier = null;
            reachable = null;
        }

        private void fail(String code, String message, BlockPos position) {
            result = bad(code, message, position);
            phase = Phase.DONE;
            frontier = null;
            reachable = null;
        }
    }

    // 能读到水平朝向时查背后的支撑面；没有可用朝向属性则直接通过这一步，不推断模组的其他附着规则。
    private static boolean supportedClimbable(World level, BlockPos pos, BlockState state) {
        Direction facing = null;
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        } else if (state.hasProperty(BlockStateProperties.FACING)) {
            Direction candidate = state.getValue(BlockStateProperties.FACING);
            if (candidate.getAxis().isHorizontal()) facing = candidate;
        }
        if (facing == null) return true;
        BlockPos support = pos.relative(facing.getOpposite());
        return level.isLoaded(support)
                && level.getBlockState(support).isFaceSturdy(level.level(), support, facing);
    }

    private static boolean adjacentAcrossDoor(BlockPos outside, BlockPos door, BlockPos inside) {
        if (outside.getY() != door.getY() || door.getY() != inside.getY()) return false;
        int dx1 = Integer.compare(door.getX(), outside.getX());
        int dz1 = Integer.compare(door.getZ(), outside.getZ());
        int dx2 = Integer.compare(inside.getX(), door.getX());
        int dz2 = Integer.compare(inside.getZ(), door.getZ());
        return door.distManhattan(inside) == 1 && dx1 == dx2 && dz1 == dz2;
    }

    // 脚、头、脚下都要已加载。脚在可攀爬方块中可不另有地板，否则脚下必须无流体且顶面承重。
    private static boolean standable(World level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.above())
                || !level.isLoaded(feet.below())) return false;
        BlockState feetState = level.getBlockState(feet);
        if (!bodyClear(level, feet) || !bodyClear(level, feet.above())) return false;
        if (feetState.is(BlockTags.CLIMBABLE)) return true;
        BlockPos floor = feet.below();
        BlockState support = level.getBlockState(floor);
        return support.getFluidState().isEmpty()
                && support.isFaceSturdy(level.level(), floor, Direction.UP);
    }

    // 当前规则把所有门和可攀爬方块直接当作可通过，也容许局部高度不超过半格的碰撞体。
    // 脚和头调用的是同一规则：头部半砖也会被放过；门是否能手开、是否有电源没有在这里验证。
    private static boolean bodyClear(World level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        if (state.getBlock() instanceof DoorBlock || state.is(BlockTags.CLIMBABLE)) return true;
        VoxelShape shape = state.getCollisionShape(level.level(), pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.5D;
    }

    private static Result ok(Map<String, Object> evidence) {
        return new Result(true, "verified", "all required routes are usable", null, evidence);
    }

    private static Result bad(String code, String message, BlockPos position) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("status", "failed");
        evidence.put("code", code);
        evidence.put("message", message);
        if (position != null) {
            evidence.put("position", Map.of(
                    "x", position.getX(), "y", position.getY(), "z", position.getZ()));
        }
        return new Result(false, code, message, position, evidence);
    }
}
