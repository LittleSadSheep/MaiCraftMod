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

    BlockPos next(LocalPlayer player) {
        pending.removeIf(pos -> player.level().isLoaded(pos) && player.level().getBlockState(pos).isAir());
        return select(pending, rejected, player.blockPosition());
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
            Vec3 body = Vec3.atBottomCenterOf(feet);
            AABB box = player.getDimensions(player.getPose()).makeBoundingBox(body);
            if (!level.noCollision(player, box)) continue;
            Vec3 eye = body.add(0, player.getEyeHeight(), 0);
            if (FirstPersonInteractionTargeting.visibleBlockHit(level, player, eye, target, 4.5) != null)
                result.add(NavGoal.exact(feet));
        }
        return result;
    }
}
