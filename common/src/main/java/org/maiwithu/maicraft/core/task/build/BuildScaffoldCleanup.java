// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;

/** Read-only cleanup stances. The native navigator still has to prove and walk each route. */
final class BuildScaffoldCleanup {
    record Candidate(BlockPos cell, Vec3 feet) {}
    private final LocalPlayer player;
    private final BlockPos target;
    private final LongSet inheritedForbidden;
    private final List<BlockPos> cells = new ArrayList<>();
    private final Set<BlockPos> rejected = new HashSet<>();
    private int cursor, offered;

    BuildScaffoldCleanup(LocalPlayer player, BlockPos target, LongSet forbidden) {
        this.player = player; this.target = target.immutable();
        inheritedForbidden = new LongOpenHashSet(forbidden);
        int radius = Math.min(6, (int) Math.ceil(AimGeometry.blockReachDistance(player)));
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            for (int y = -radius - 1; y <= radius; y++) cells.add(target.offset(x, y, z));
        cells.sort(Comparator.comparingDouble(p -> Vec3.atBottomCenterOf(p).distanceToSqr(player.position())));
    }

    boolean exhausted() { return cursor >= cells.size() || offered >= 24; }
    void rejectCurrent() { rejected.add(PlayerNav.playerFeet(player).immutable()); }

    Candidate next() {
        var physical = PhysicalObstacleSnapshot.capture(player.clientLevel, Vec3.atCenterOf(target));
        var actual = world(false);
        var removed = world(true);
        var forbidden = forbidden();
        long deadline = System.nanoTime() + 3_000_000L;
        for (int work = 0; work < 32 && !exhausted(); work++) {
            BlockPos cell = cells.get(cursor++);
            if (!rejected.contains(cell)) {
                Vec3 feet = ground(actual, forbidden, physical).stance(cell);
                if (feet != null && ground(removed, forbidden, physical).clear(feet, feet)
                        && visible(world(false), feet.add(0, player.getEyeHeight(Pose.STANDING), 0))) {
                    offered++;
                    return new Candidate(cell, feet);
                }
            }
            if (System.nanoTime() >= deadline) break;
        }
        return null;
    }

    boolean ready() {
        if (!player.onGround() || rejected.contains(PlayerNav.playerFeet(player))) return false;
        var physical = PhysicalObstacleSnapshot.capture(player.clientLevel, player.position());
        var forbidden = forbidden();
        Vec3 feet = player.position();
        // Both the live body and its support after removal must be safe, including cell seams.
        return ground(world(false), forbidden, physical).clear(feet, feet)
                && ground(world(true), forbidden, physical).clear(feet, feet)
                && visible(world(false), player.getEyePosition());
    }

    Map<String, Object> evidence() {
        return Map.of("checked_stances", cursor, "candidate_stances", cells.size(),
                "offered_stances", offered, "rejected_actual_stances", rejected.size(),
                "search_complete", exhausted(), "routing", "existing_footing_only");
    }

    private LongSet forbidden() {
        var all = new LongOpenHashSet(inheritedForbidden);
        all.addAll(NavigationSafetyContext.forbiddenBodyCells());
        return all;
    }

    private BuildSupportWorld world(boolean removed) {
        return new BuildSupportWorld(player.level(), player.level()::isLoaded,
                removed ? Map.of(target, Blocks.AIR.defaultBlockState()) : Map.of());
    }

    private GroundCorridor ground(BuildSupportWorld world, LongSet forbidden, PhysicalObstacleSnapshot physical) {
        return new GroundCorridor(world, player.level()::isLoaded, player.getBbWidth(),
                Math.max(player.getBbHeight(), player.getDimensions(Pose.STANDING).height()), forbidden, physical);
    }

    private boolean visible(BuildSupportWorld world, Vec3 eye) {
        try {
            if (!player.level().isLoaded(target)) return false;
            var state = world.getBlockState(target);
            if (state.isAir()) return false;
            VoxelShape shape = state.getShape(world, target);
            if (shape.isEmpty()) shape = Shapes.block();
            VoxelShape collision = state.getCollisionShape(world, target);
            // Keep the exact seven probes used by BlockDigger.digTargetStep. A denser search
            // would promise a shot that the native digger cannot subsequently reproduce.
            List<Vec3> aims = new ArrayList<>();
            Vec3 center = collision.isEmpty() ? Vec3.atCenterOf(target) : point(collision, .5, .5, .5);
            if (!collision.isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock)
                center = new Vec3(center.x, target.getY(), center.z);
            aims.add(center);
            for (Direction face : Direction.values()) aims.add(point(shape,
                    .5 + face.getStepX() * .5, .5 + face.getStepY() * .5, .5 + face.getStepZ() * .5));
            for (Vec3 aim : aims) {
                Vec3 direction = aim.subtract(eye);
                if (direction.lengthSqr() < 1e-8) continue;
                var hit = world.clip(new ClipContext(eye,
                        eye.add(direction.normalize().scale(AimGeometry.blockReachDistance(player))),
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)
                        && !world.sawUnloaded()) return true;
            }
        } catch (RuntimeException | LinkageError unavailable) { return false; }
        return false;
    }

    private Vec3 point(VoxelShape shape, double x, double y, double z) {
        return new Vec3(target.getX() + shape.min(Direction.Axis.X) * x + shape.max(Direction.Axis.X) * (1 - x),
                target.getY() + shape.min(Direction.Axis.Y) * y + shape.max(Direction.Axis.Y) * (1 - y),
                target.getZ() + shape.min(Direction.Axis.Z) * z + shape.max(Direction.Axis.Z) * (1 - z));
    }
}
