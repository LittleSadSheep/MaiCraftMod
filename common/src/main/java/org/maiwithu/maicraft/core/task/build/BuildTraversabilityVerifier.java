// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only proof that planner-named entrances, floors, ladders and docks are actually usable. */
public final class BuildTraversabilityVerifier {
    private static final long MAX_INTERIOR_VOLUME = 96L * 48L * 96L;

    private BuildTraversabilityVerifier() {}

    public record Result(boolean valid, String code, String message, BlockPos position,
                         Map<String, Object> evidence) {
        public Result {
            position = position == null ? null : position.immutable();
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        }
    }

    public static Result verify(ClientLevel level, BuildTraversabilityContract contract) {
        if (contract == null) return ok(Map.of("contract", "not_requested"));
        if (contract.exteriorApproach() == null || contract.entranceDoor() == null
                || contract.interiorEntry() == null || contract.interiorBounds() == null) {
            return bad("invalid_traversability_contract",
                    "the internal traversability contract is incomplete", null);
        }
        BuildTraversabilityContract.Bounds bounds = contract.interiorBounds();
        if (bounds.volume() <= 0 || bounds.volume() > MAX_INTERIOR_VOLUME) {
            return bad("invalid_traversability_bounds",
                    "the internal traversability bounds are empty or exceed the bounded verifier", null);
        }

        BlockPos outside = contract.exteriorApproach().pos();
        BlockPos door = contract.entranceDoor().pos();
        BlockPos inside = contract.interiorEntry().pos();
        Result loaded = requireLoaded(level, List.of(outside, door, door.above(), inside));
        if (loaded != null) return loaded;
        BlockState lowerDoor = level.getBlockState(door);
        BlockState upperDoor = level.getBlockState(door.above());
        if (!(lowerDoor.getBlock() instanceof DoorBlock)
                || !(upperDoor.getBlock() instanceof DoorBlock)
                || lowerDoor.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && lowerDoor.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER
                || upperDoor.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && upperDoor.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER) {
            return bad("entrance_door_missing",
                    "the planned exterior entrance is not a complete two-block door", door);
        }
        Result entrance = verifyAlignedWalkingLine(level, outside, door, "entrance_approach_blocked");
        if (entrance != null) return entrance;
        if (!adjacentAcrossDoor(outside, door, inside) || !standable(level, inside)) {
            return bad("entrance_crossing_blocked",
                    "the outside approach, door and interior landing do not form one usable crossing", inside);
        }

        Result boundsLoaded = requireBoundsLoaded(level, bounds);
        if (boundsLoaded != null) return boundsLoaded;
        if (!bounds.contains(inside)) {
            return bad("invalid_interior_entry",
                    "the verified doorway does not enter the bounded interior", inside);
        }

        BuildTraversabilityContract.VerticalLink link = contract.verticalLink();
        if (link != null) {
            Result ladder = verifyVerticalLink(level, link, bounds);
            if (ladder != null) return ladder;
        }

        Set<Long> reachable = floodInterior(level, inside, bounds);
        if (!reachable.contains(inside.asLong())) {
            return bad("interior_entry_blocked",
                    "the cell immediately inside the door is not a valid standing place", inside);
        }
        int waypointCount = 0;
        for (BuildTraversabilityContract.Cell cell : contract.floorWaypoints()) {
            if (cell == null) {
                return bad("invalid_floor_waypoint",
                        "the internal floor waypoint list contains an empty entry", null);
            }
            BlockPos waypoint = cell.pos();
            waypointCount++;
            if (!bounds.contains(waypoint) || !standable(level, waypoint)
                    || !reachable.contains(waypoint.asLong())) {
                return bad("interior_floor_unreachable",
                        "an intended room or storey has no continuous walk/climb route from the entrance",
                        waypoint);
            }
        }

        BuildTraversabilityContract.DockPath dock = contract.dockPath();
        int dockLength = 0;
        if (dock != null) {
            if (dock.houseSide() == null || dock.deckEnd() == null) {
                return bad("invalid_dock_contract",
                        "the internal dock path is incomplete", null);
            }
            BlockPos start = dock.houseSide().pos();
            BlockPos end = dock.deckEnd().pos();
            Result dockResult = verifyAlignedWalkingLine(level, start, end, "dock_walkway_blocked");
            if (dockResult != null) return dockResult;
            dockLength = start.distManhattan(end) + 1;
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("status", "verified");
        evidence.put("basis", "read_only_collision_and_connectivity_scan_of_the_finished_world");
        evidence.put("entrance_crossing", true);
        evidence.put("reachable_floor_waypoints", waypointCount);
        evidence.put("reachable_interior_cells", reachable.size());
        evidence.put("vertical_link", link == null ? "not_required" : "continuous");
        evidence.put("dock_centerline_cells", dockLength);
        return ok(evidence);
    }

    private static Result verifyVerticalLink(
            ClientLevel level, BuildTraversabilityContract.VerticalLink link,
            BuildTraversabilityContract.Bounds bounds) {
        if (link.bottomY() > link.topY()) {
            return bad("invalid_vertical_link", "the vertical connector has inverted bounds", null);
        }
        for (int y = link.bottomY(); y <= link.topY(); y++) {
            BlockPos pos = new BlockPos(link.x(), y, link.z());
            if (!bounds.contains(pos) || !level.isLoaded(pos)) {
                return bad("vertical_link_unloaded",
                        "the complete storey connector could not be observed", pos);
            }
            BlockState state = level.getBlockState(pos);
            if (!state.is(BlockTags.CLIMBABLE) || !bodyClear(level, pos)
                    || !supportedClimbable(level, pos, state)) {
                return bad("vertical_link_broken",
                        "the storeys are not joined by one continuous supported climbable column", pos);
            }
        }
        return null;
    }

    private static boolean supportedClimbable(ClientLevel level, BlockPos pos, BlockState state) {
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
                && level.getBlockState(support).isFaceSturdy(level, support, facing);
    }

    private static Set<Long> floodInterior(
            ClientLevel level, BlockPos start, BuildTraversabilityContract.Bounds bounds) {
        Set<Long> visited = new LinkedHashSet<>();
        if (!standable(level, start)) return visited;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        visited.add(start.asLong());
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                addReachable(level, current.relative(direction), bounds, visited, queue);
            }
            BlockState currentState = level.getBlockState(current);
            BlockPos above = current.above();
            BlockPos below = current.below();
            if (currentState.is(BlockTags.CLIMBABLE)
                    || bounds.contains(above) && level.getBlockState(above).is(BlockTags.CLIMBABLE)) {
                addReachable(level, above, bounds, visited, queue);
            }
            if (currentState.is(BlockTags.CLIMBABLE)
                    || bounds.contains(below) && level.getBlockState(below).is(BlockTags.CLIMBABLE)) {
                addReachable(level, below, bounds, visited, queue);
            }
        }
        return visited;
    }

    private static void addReachable(
            ClientLevel level, BlockPos pos, BuildTraversabilityContract.Bounds bounds,
            Set<Long> visited, ArrayDeque<BlockPos> queue) {
        long key = pos.asLong();
        if (!bounds.contains(pos) || visited.contains(key) || !standable(level, pos)) return;
        visited.add(key);
        queue.addLast(pos.immutable());
    }

    private static Result verifyAlignedWalkingLine(
            ClientLevel level, BlockPos from, BlockPos to, String code) {
        if (from.getY() != to.getY()
                || from.getX() != to.getX() && from.getZ() != to.getZ()) {
            return bad("invalid_straight_path_contract",
                    "a planner-authored entrance or dock path is not a straight level line", from);
        }
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        BlockPos cursor = from;
        while (true) {
            if (!level.isLoaded(cursor)) {
                return bad(code, "a required walking path is not fully loaded for observation", cursor);
            }
            if (!standable(level, cursor)) {
                return bad(code, "a required walking path has no solid floor or two-block clearance", cursor);
            }
            if (cursor.equals(to)) return null;
            cursor = cursor.offset(dx, 0, dz);
        }
    }

    private static boolean adjacentAcrossDoor(BlockPos outside, BlockPos door, BlockPos inside) {
        if (outside.getY() != door.getY() || door.getY() != inside.getY()) return false;
        int dx1 = Integer.compare(door.getX(), outside.getX());
        int dz1 = Integer.compare(door.getZ(), outside.getZ());
        int dx2 = Integer.compare(inside.getX(), door.getX());
        int dz2 = Integer.compare(inside.getZ(), door.getZ());
        return door.distManhattan(inside) == 1 && dx1 == dx2 && dz1 == dz2;
    }

    private static boolean standable(ClientLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.above())
                || !level.isLoaded(feet.below())) return false;
        BlockState feetState = level.getBlockState(feet);
        if (!bodyClear(level, feet) || !bodyClear(level, feet.above())) return false;
        if (feetState.is(BlockTags.CLIMBABLE)) return true;
        BlockPos floor = feet.below();
        BlockState support = level.getBlockState(floor);
        return support.getFluidState().isEmpty()
                && support.isFaceSturdy(level, floor, Direction.UP);
    }

    private static boolean bodyClear(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        if (state.getBlock() instanceof DoorBlock || state.is(BlockTags.CLIMBABLE)) return true;
        VoxelShape shape = state.getCollisionShape(level, pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.5D;
    }

    private static Result requireLoaded(ClientLevel level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (!level.isLoaded(pos)) {
                return bad("traversability_observation_unloaded",
                        "a required route cell could not be observed", pos);
            }
        }
        return null;
    }

    private static Result requireBoundsLoaded(
            ClientLevel level, BuildTraversabilityContract.Bounds bounds) {
        for (int x = bounds.minX(); x <= bounds.maxX(); x += 16) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 16) {
                BlockPos pos = new BlockPos(x, bounds.minY(), z);
                if (!level.isLoaded(pos)) {
                    return bad("interior_observation_unloaded",
                            "the complete bounded interior could not be observed", pos);
                }
            }
        }
        BlockPos farCorner = new BlockPos(bounds.maxX(), bounds.minY(), bounds.maxZ());
        return level.isLoaded(farCorner) ? null : bad("interior_observation_unloaded",
                "the complete bounded interior could not be observed", farCorner);
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
