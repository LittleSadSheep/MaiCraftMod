package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;

/** Clear authored occupied cells from the exposed top down before laying the lowest floor. */
final class BuildExcavationFrontier {
    private final Set<BlockPos> pending = new LinkedHashSet<>();
    private final Set<BlockPos> rejected = new LinkedHashSet<>();

    void add(BlockPos pos) { pending.add(pos.immutable()); }
    int remaining() { return pending.size(); }
    Set<BlockPos> cells() { return Set.copyOf(pending); }
    void cleared(BlockPos pos) { pending.remove(pos); rejected.clear(); }
    void reject(BlockPos pos) { rejected.add(pos); }

    static boolean safeDescent(LocalPlayer player, BlockPos target) {
        if (!target.equals(player.blockPosition().below())) return true;
        var level = player.level();
        var landing = target.below();
        return level.isLoaded(landing) && level.getBlockState(landing).getFluidState().isEmpty()
                && !org.maiwithu.maicraft.core.pathing.util.BlockHelper.isHazard(level, landing)
                && level.getBlockState(landing).isFaceSturdy(level, landing, net.minecraft.core.Direction.UP);
    }

    /** A resumed task may start inside its pit; use loaded exterior ground instead of that buried starting point. */
    static BlockPos exit(LocalPlayer player, BlockPos min, BlockPos max) {
        BlockPos start = player.blockPosition();
        if (min == null || max == null) return start.immutable();
        if (start.getX() < min.getX() || start.getX() > max.getX()
                || start.getZ() < min.getZ() || start.getZ() > max.getZ()) return start.immutable();
        int x = Math.floorDiv(min.getX() + max.getX(), 2), z = Math.floorDiv(min.getZ() + max.getZ(), 2);
        List<BlockPos> choices = new ArrayList<>();
        for (BlockPos column : List.of(new BlockPos(min.getX() - 1, start.getY(), z),
                new BlockPos(max.getX() + 1, start.getY(), z), new BlockPos(x, start.getY(), min.getZ() - 1),
                new BlockPos(x, start.getY(), max.getZ() + 1))) {
            var level = player.level();
            if (!level.isLoaded(column)) continue;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
            BlockPos feet = new BlockPos(column.getX(), y, column.getZ());
            if (level.isOutsideBuildHeight(feet.above()) || !level.isLoaded(feet.below())
                    || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)
                    || org.maiwithu.maicraft.core.pathing.util.BlockHelper.isHazard(level, feet.below())
                    || org.maiwithu.maicraft.core.pathing.util.BlockHelper.avoidWalkingInto(level, feet)
                    || org.maiwithu.maicraft.core.pathing.util.BlockHelper.avoidWalkingInto(level, feet.above())) continue;
            if (level.noCollision(player, player.getBoundingBox().move(Vec3.atBottomCenterOf(feet).subtract(player.position())))) choices.add(feet);
        }
        return choices.stream().min(Comparator.comparingDouble(at -> at.distSqr(start))).orElse(start).immutable();
    }

    BlockPos next(LocalPlayer player) {
        pending.removeIf(pos -> player.level().isLoaded(pos) && player.level().getBlockState(pos).isAir());
        if (org.maiwithu.maicraft.core.integration.ultimine.UltimineNative.available()
                && org.maiwithu.maicraft.core.integration.ultimine.UltimineNative.serverAvailable()) {
            var clusters = clusters(pending, player.blockPosition());
            BlockPos clustered = select(clusters, rejected, player.blockPosition());
            if (clustered != null) return clustered;
        }
        return select(pending, rejected, player.blockPosition());
    }

    static Set<BlockPos> clusters(Set<BlockPos> pending, BlockPos feet) {
        int top = pending.stream().mapToInt(BlockPos::getY).max().orElse(Integer.MIN_VALUE);
        Set<BlockPos> result = new LinkedHashSet<>();
        for (BlockPos center : pending) {
            if (center.getY() != top) continue;
            var square = org.maiwithu.maicraft.core.integration.ultimine.UltimineSelectionPolicy.square(center, net.minecraft.core.Direction.UP);
            if (!square.contains(feet.below()) && pending.containsAll(square)) result.add(center);
        }
        return result;
    }

    static BlockPos select(Set<BlockPos> pending, Set<BlockPos> rejected, BlockPos feet) {
        int top = pending.stream().mapToInt(BlockPos::getY).max().orElse(Integer.MIN_VALUE);
        return pending.stream().filter(pos -> pos.getY() == top && !rejected.contains(pos))
                .min(Comparator.comparing((BlockPos pos) -> pos.equals(feet.below()))
                        .thenComparingDouble(pos -> pos.distSqr(feet))
                        .thenComparingLong(BlockPos::asLong)).orElse(null);
    }

    /** A ground approach must expose a real face without standing on the block being removed. */
    static List<NavGoal> approaches(LocalPlayer player, BlockPos target) {
        var level = player.level();
        List<NavGoal> result = new ArrayList<>();
        for (int dy = -2; dy <= 1; dy++) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            BlockPos feet = target.offset(dx, dy, dz);
            if (feet.below().equals(target) || !level.isLoaded(feet) || !level.isLoaded(feet.above())
                    || !level.isLoaded(feet.below())) continue;
            var floor = level.getBlockState(feet.below());
            if (floor.getCollisionShape(level, feet.below()).isEmpty() || !floor.getFluidState().isEmpty()) continue;
            if (org.maiwithu.maicraft.core.pathing.util.BlockHelper.isHazard(level, feet.below())
                    || org.maiwithu.maicraft.core.pathing.util.BlockHelper.avoidWalkingInto(level, feet)
                    || org.maiwithu.maicraft.core.pathing.util.BlockHelper.avoidWalkingInto(level, feet.above())) continue;
            Vec3 body = Vec3.atBottomCenterOf(feet);
            AABB box = player.getBoundingBox().move(body.subtract(player.position()));
            if (!level.noCollision(player, box)) continue;
            Vec3 eye = body.add(0, player.getEyeHeight(), 0);
            if (FirstPersonInteractionTargeting.visibleBlockHit(level, player, eye, target, 4.5) != null)
                result.add(NavGoal.exact(feet));
        }
        return result;
    }
}
