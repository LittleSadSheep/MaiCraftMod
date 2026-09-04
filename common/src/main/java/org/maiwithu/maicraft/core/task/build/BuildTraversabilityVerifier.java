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

        private void beginFlood() {
            if (!standable(level, inside)) {
                fail("interior_entry_blocked", "the cell immediately inside the door is not a valid standing place", inside);
                return;
            }
            reachable.add(inside.asLong());
            frontier.addLast(inside);
            phase = Phase.FLOOD;
        }

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
