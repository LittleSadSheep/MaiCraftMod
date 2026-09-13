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

/** 先从露出的地表往下刨坑，清掉蓝图中的阻挡物，再铺地下室地板，避免直接寻路到埋在土里的底层。 */
public final class BuildExcavationFrontier {
    private final Set<BlockPos> pending = new LinkedHashSet<>();
    private final Set<BlockPos> rejected = new LinkedHashSet<>();

    void add(BlockPos pos) { pending.add(pos.immutable()); }
    int remaining() { return pending.size(); }
    Set<BlockPos> cells() { return Set.copyOf(pending); }
    void cleared(BlockPos pos) { pending.remove(pos); rejected.clear(); }
    void reject(BlockPos pos) { rejected.add(pos); }

    public enum AccessStatus { READY, EXIT_REQUIRED, BLOCKED }
    /** 离场证明只描述已观察通行条件，既不生成地面，也不把未知出口视为准备完成。 */
    public record SupplyAccess(AccessStatus status, BlockPos exit, String code) {}
    public static SupplyAccess supplyAccess(LocalPlayer player, BuildTaskRecord plan) {
        return supplyAccess(player, plan.targets, plan.scaffoldLedger().snapshot().keySet());
    }
    public static SupplyAccess supplyAccess(LocalPlayer player, List<BuildTaskRecord.Target> targets) {
        return supplyAccess(player, targets, Set.of());
    }
    public static SupplyAccess supplyAccess(LocalPlayer player, List<BuildTaskRecord.Target> targets, Set<BlockPos> scaffolds) {
        if (targets.isEmpty()) return new SupplyAccess(AccessStatus.READY, null, "empty_build_footprint");
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var target : targets) {
            minX = Math.min(minX, target.pos().getX()); minZ = Math.min(minZ, target.pos().getZ());
            maxX = Math.max(maxX, target.pos().getX()); maxZ = Math.max(maxZ, target.pos().getZ());
        }
        return supplyAccess(player, new BlockPos(minX, 0, minZ), new BlockPos(maxX, 0, maxZ), scaffolds);
    }
    public static SupplyAccess supplyAccess(LocalPlayer player, BlockPos min, BlockPos max, Set<BlockPos> scaffolds) {
        return BuildSupplyExit.inspect(player, min, max, scaffolds);
    }
    public static boolean needsSupplyAccess(LocalPlayer player, List<BuildTaskRecord.Target> targets) {
        return supplyAccess(player, targets).status() != AccessStatus.READY;
    }

    static boolean safeDescent(LocalPlayer player, BlockPos target) {
        // 刨脚下方块前，先确认下一格有安全落脚面；不能把角色送进深坑、岩浆块或流体里。
        if (!target.equals(player.blockPosition().below())) return true;
        var level = player.level();
        var landing = target.below();
        return level.isLoaded(landing) && level.getBlockState(landing).getFluidState().isEmpty()
                && !org.maiwithu.maicraft.core.pathing.util.BlockHelper.isHazard(level, landing)
                && level.getBlockState(landing).isFaceSturdy(level, landing, net.minecraft.core.Direction.UP);
    }

    /** 保留旧的只读出口查询入口；未知出口返回空，不能拿当前柱顶假装已经离场。 */
    static BlockPos exit(LocalPlayer player, BlockPos min, BlockPos max) {
        return supplyAccess(player, min, max, Set.of()).exit();
    }

    BlockPos next(LocalPlayer player) {
        // 优先保留能一并连锁的完整土方，减少先挖散边角再来回换站位；最终选区仍由原生准星决定。
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

    /** 找能真正看见目标面的地面站位，同时避开流体、伤害方块以及马上要拆掉的落脚块。 */
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
