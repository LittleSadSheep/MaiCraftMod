// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.act;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Comparator;
import java.util.Set;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/**
 * 在走过去之前先试算“站在那里能否点到”，相机转到位后再用同一套规则核对。
 * 普通方块按可见表面判断；水桶沿原版取水／倒水的射线判断，不能把水后的机器误当作要点击的目标。
 * 这里只检查几何和当前方块形态，不替原版证明权限、物品消耗或最后效果。
 */
public final class FirstPersonInteractionTargeting {
    private static final double EPSILON = 1.0e-6D;
    private static final double FACE_INSET = 1.0e-3D;

    private FirstPersonInteractionTargeting() {}

    /** 桶由原版 useItem 沿视线取水／倒水，不要改成点击水后面的机器。 */
    public static boolean usesBucketRay(Item item) {
        return item instanceof BucketItem;
    }

    // 空桶射线会命中水源；装着东西的桶忽略流体，寻找能供倒水定位的方块表面。
    public static BlockHitResult bucketRay(
            Level level, Entity observer, Vec3 eye, Vec3 end, Item item) {
        return level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                item == Items.BUCKET ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, observer));
    }

    /** 核对这次桶操作是否对准请求格；空桶要求那里真是可取的水源，满桶还要检查放置方向。 */
    public static boolean acceptsBucketHit(Level level, BlockPos target, Item item, BlockHitResult hit) {
        if (!level.isLoaded(target) || hit.getType() != HitResult.Type.BLOCK
                || !level.isLoaded(hit.getBlockPos())) return false;
        var state = level.getBlockState(target);
        if (item == Items.BUCKET) {
            return hit.getBlockPos().equals(target) && state.getBlock() instanceof BucketPickup
                    && (!(state.getBlock() instanceof LiquidBlock) || state.getFluidState().isSource());
        }
        // 请求格本身是实体方块时，当前规则只要求点击该格；空气或流体格则继续推算水要落在哪里。
        if (!state.isAir() && !(state.getBlock() instanceof LiquidBlock)) return hit.getBlockPos().equals(target);
        // 当前只按方块是否实现含水接口判断，没检查双层台阶等实际不能含水的状态；见审计记录 A31。
        boolean waterlogs = (item == Items.WATER_BUCKET || item instanceof MobBucketItem)
                && level.getBlockState(hit.getBlockPos()).getBlock() instanceof LiquidBlockContainer;
        BlockPos placement = waterlogs ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
        return placement.equals(target);
    }

    /** 出手前先按桶的射线规则试算；相机真正转到位后仍要重新检查，避免水源已变或面点错。 */
    public static BlockHitResult visibleBucketHit(
            Level level, Entity observer, Vec3 eye, BlockPos target, double reach, Item item) {
        if (!Double.isFinite(reach) || reach <= 0.0D
                || !level.isLoaded(BlockPos.containing(eye)) || !level.isLoaded(target)) return null;
        var state = level.getBlockState(target);
        if (item != Items.BUCKET && !state.isAir() && !(state.getBlock() instanceof LiquidBlock)) {
            return visibleBlockHit(level, observer, eye, target, reach);
        }
        Vec3 delta = Vec3.atCenterOf(target).subtract(eye);
        if (delta.lengthSqr() < EPSILON) return null;
        BlockHitResult hit = bucketRay(level, observer, eye, eye.add(delta.normalize().scale(reach)), item);
        return acceptsBucketHit(level, target, item, hit) ? hit : null;
    }

    /**
     * Rehearse the same block ray a converged first-person camera will cast: aim at the target
     * centre, but extend the ray through it to the full native reach. Extending matters for thin
     * outline shapes (doors, trapdoors and similar blocks) whose surface can lie just beyond the
     * centre along the approach direction.
     */
    // 实心目标要找到真正可见的面；空气／流体目标先检查视线能否穿过那一格。
    // 射线延伸到全部触及距离，避免薄门板的表面在整格中心后方时被漏掉。
    public static boolean hasLoadedReachLine(
            Level level, Entity observer, Vec3 eye, BlockPos target, double reach) {
        if (!Double.isFinite(reach) || reach <= 0.0D
                || !level.isLoaded(BlockPos.containing(eye))
                || !level.isLoaded(target)) {
            return false;
        }
        var targetState = level.getBlockState(target);
        if (!targetState.isAir() && !(targetState.getBlock() instanceof LiquidBlock)) {
            return visibleBlockHit(level, observer, eye, target, reach) != null;
        }

        Vec3 targetCenter = Vec3.atCenterOf(target);
        Vec3 delta = targetCenter.subtract(eye);
        double distanceSqr = delta.lengthSqr();
        if (distanceSqr < EPSILON) return true;

        Vec3 end = eye.add(delta.scale(reach / Math.sqrt(distanceSqr)));
        AABB targetCell = new AABB(target);
        if (!targetCell.contains(eye) && targetCell.clip(eye, end).isEmpty()) return false;
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, observer));
        return !blockedByWorld(level, eye, target, end, hit);
    }

    /**
     * Resolve a concrete first-person hit on any visible part of a loaded block.
     *
     * <p>A centre-only ray is not enough: a workstation below a leaf canopy, a chest below a
     * shelf, or a partially exposed machine can have its centre ray blocked while an ordinary
     * player can still click a side. Try the outline centre and then all six face centres, keeping
     * the point slightly inside the outline so an exact boundary never aliases to a neighbour.
     * The returned hit is proof that the target itself is the first block on that native-reach
     * line; callers still converge the real camera and perform a final native ray before use.</p>
     */
    // 先取方块形状的边界，试中心和靠近六个面的点；命中必须属于指定格且在触及距离内。
    public static BlockHitResult visibleBlockHit(
            Level level, Entity observer, Vec3 eye, BlockPos target, double reach) {
        if (!Double.isFinite(reach) || reach <= 0.0D
                || !level.isLoaded(BlockPos.containing(eye))
                || !level.isLoaded(target)) {
            return null;
        }
        var state = level.getBlockState(target);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) return null;

        VoxelShape shape = state.getShape(level, target);
        if (shape.isEmpty()) shape = Shapes.block();
        double minX = shape.min(Direction.Axis.X);
        double minY = shape.min(Direction.Axis.Y);
        double minZ = shape.min(Direction.Axis.Z);
        double maxX = shape.max(Direction.Axis.X);
        double maxY = shape.max(Direction.Axis.Y);
        double maxZ = shape.max(Direction.Axis.Z);
        double midX = (minX + maxX) * 0.5D;
        double midY = (minY + maxY) * 0.5D;
        double midZ = (minZ + maxZ) * 0.5D;
        double lowX = insetMin(minX, maxX);
        double lowY = insetMin(minY, maxY);
        double lowZ = insetMin(minZ, maxZ);
        double highX = insetMax(minX, maxX);
        double highY = insetMax(minY, maxY);
        double highZ = insetMax(minZ, maxZ);
        Vec3[] aims = {
                offset(target, midX, midY, midZ),
                offset(target, midX, lowY, midZ),
                offset(target, midX, highY, midZ),
                offset(target, midX, midY, lowZ),
                offset(target, midX, midY, highZ),
                offset(target, lowX, midY, midZ),
                offset(target, highX, midY, midZ)
        };
        for (Vec3 aim : aims) {
            Vec3 direction = aim.subtract(eye);
            if (direction.lengthSqr() < EPSILON) continue;
            Vec3 end = eye.add(direction.normalize().scale(reach));
            BlockHitResult hit = level.clip(new ClipContext(
                    eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, observer));
            if (hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(target)
                    && eye.distanceToSqr(hit.getLocation()) <= reach * reach + EPSILON) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Pick the nearest loaded, standable feet cell from which some face of {@code target} is
     * genuinely clickable. This is shared physical interaction geometry, not a workstation rule.
     */
    // 只在目标水平三格、上下有限高度内找干燥站位；要有落脚支撑、身体两格空且能看到目标。
    // 最后按与玩家的直线距离选择，不在这里证明有路能走到。
    public static BlockPos nearestVisibleStand(
            LocalPlayer player, BlockPos target, double reach, Set<Long> excluded) {
        if (target == null || !player.level().isLoaded(target)) return null;
        BlockPos current = PlayerNav.playerFeet(player);
        java.util.ArrayList<BlockPos> candidates = new java.util.ArrayList<>();
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -2; dy <= 1; dy++) {
                        BlockPos feet = target.offset(dx, dy, dz);
                        if ((excluded == null || !excluded.contains(feet.asLong()))
                                && standable(player.level(), feet)
                                && visibleBlockHit(
                                        player.level(), player,
                                        Vec3.atBottomCenterOf(feet)
                                                .add(0.0D, player.getEyeHeight(Pose.STANDING), 0.0D),
                                        target, reach) != null) {
                            candidates.add(feet.immutable());
                        }
                    }
                }
            }
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(candidate -> candidate.distSqr(current)))
                .orElse(null);
    }

    // 这是较保守的站位筛选：不接受水中或身体格里有碰撞形状的位置，脚下还得能托住上表面。
    private static boolean standable(Level level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.above())
                || !level.isLoaded(feet.below())) return false;
        if (!level.getFluidState(feet).isEmpty()
                || !level.getFluidState(feet.above()).isEmpty()) return false;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(feet.above())
                        .getCollisionShape(level, feet.above()).isEmpty()) return false;
        BlockPos support = feet.below();
        return level.getBlockState(support).isFaceSturdy(level, support, Direction.UP);
    }

    private static Vec3 offset(BlockPos pos, double x, double y, double z) {
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    private static double insetMin(double min, double max) {
        return Math.min(max, min + Math.min(FACE_INSET, (max - min) * 0.25D));
    }

    private static double insetMax(double min, double max) {
        return Math.max(min, max - Math.min(FACE_INSET, (max - min) * 0.25D));
    }

    /**
     * The common obstruction verdict used both before travel and after the real camera converges.
     * Air aims intentionally pass through. Non-bucket liquid use keeps the Fluid.NONE crosshair
     * policy; buckets instead require {@link #acceptsBucketHit} on their own item ray.
     */
    // 空气坐标目前只被当作朝向提示，直接放行，前方即使有别的命中也不会在这里拒绝。
    // 流体坐标比较先到目标格还是先碰阻挡物；普通方块则必须实际命中目标自身。
    public static boolean blockedByWorld(
            Level level, Vec3 eye, BlockPos target, Vec3 rayEnd, HitResult hit) {
        var targetState = level.getBlockState(target);
        if (targetState.isAir()) return false;
        if (targetState.getBlock() instanceof LiquidBlock) {
            AABB targetCell = new AABB(target);
            if (targetCell.contains(eye)) return false;
            var entry = targetCell.clip(eye, rayEnd);
            if (entry.isEmpty()) return true;
            if (hit == null || hit.getType() == HitResult.Type.MISS) return false;
            if (hit instanceof BlockHitResult blockHit
                    && blockHit.getBlockPos().equals(target)) return false;
            double targetDistance = eye.distanceTo(entry.orElseThrow());
            double hitDistance = eye.distanceTo(hit.getLocation());
            return hitDistance + EPSILON < targetDistance;
        }
        return !(hit instanceof BlockHitResult blockHit)
                || hit.getType() != HitResult.Type.BLOCK
                || !blockHit.getBlockPos().equals(target);
    }
}
