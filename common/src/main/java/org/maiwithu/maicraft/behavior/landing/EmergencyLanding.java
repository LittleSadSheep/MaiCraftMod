// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.FallDamage;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 意外掉下去时就地找落点救：先看正下方，再看周围两格内空中推得过去的落点，水优先。
 * 救命不看任务许可；只按已加载、看得懂的碰撞面找，读不懂的（未加载、射线结果对不上世界坐标）当没有。
 */
public final class EmergencyLanding {

    /** 下坠快过这个速度（格每刻）就当在快速坠落：伤害估算过时也照样接手。 */
    private static final double FAST_FALL_SPEED = -0.7;
    /** 往四周找落点的半径（格）：够绕开活板门、种植床、机器这类落点。 */
    private static final int NEARBY_RADIUS = 2;

    private EmergencyLanding() {}

    /**
     * 该不该接手：在往下掉（不在地上、水里、梯子上，没坐载具、没在飞），落点那一柱没有水，
     * 并且预计落地会掉血或已经掉得很快。
     */
    public static boolean needed(LocalPlayer player) {
        if (player.isPassenger() || player.onGround() || player.isInWater() || player.isSwimming()
                || player.onClimbable() || player.getAbilities().flying || player.isFallFlying()
                || player.getDeltaMovement().y >= 0) return false;
        BlockPos ground = groundBelow(player);
        if (ground != null && liquidOnTheWay(player, ground)) return false;
        if (player.getDeltaMovement().y <= FAST_FALL_SPEED) return true;
        return ground != null && predictedDamage(player, ground) > 0;
    }

    /** 找一个落点和那里能用的办法，交给一次空中救援；哪里都救不了时为 null。 */
    public static LandingRescue find(PlayerContext context, Predicate<BlockPos> protectedCell, LongSet noEntryCells) {
        BlockPos ground = groundBelow(context.localPlayer());
        if (ground == null) return null;
        List<LandingPlan> plans = bestPlans(context, ground.above(), protectedCell, noEntryCells);
        return plans.isEmpty() ? null : LandingRescue.airborne(plans, protectedCell, noEntryCells);
    }

    /** 生存反射救命用：不看任务许可与走路的保护格，身体禁入格也不算。 */
    public static LandingRescue findToSaveLife(PlayerContext context) {
        return find(context, cell -> false, LongSets.EMPTY_SET);
    }

    // 正下方能放水、又推得到（或本来就是直着往下掉）就用它；否则在周围找，水优先、离得近优先。
    private static List<LandingPlan> bestPlans(PlayerContext context, BlockPos preferred,
                                               Predicate<BlockPos> protectedCell, LongSet noEntryCells) {
        LocalPlayer player = context.localPlayer();
        List<LandingPlan> direct = plansAt(context, preferred, protectedCell, noEntryCells);
        boolean vertical = player.getDeltaMovement().horizontalDistance() < .05
                && Math.hypot(player.getX() - preferred.getX() - .5, player.getZ() - preferred.getZ() - .5) < .55;
        boolean directReachable = !direct.isEmpty() && (vertical || AirSteering.reachable(player, preferred));
        if (directReachable && hasWater(direct)) return direct;
        List<LandingPlan> best = direct;
        double score = directReachable
                ? 100 + player.position().distanceToSqr(Vec3.atBottomCenterOf(preferred)) : Double.POSITIVE_INFINITY;
        BlockPos ground = preferred.below();
        for (int x = -NEARBY_RADIUS; x <= NEARBY_RADIUS; x++) {
            for (int z = -NEARBY_RADIUS; z <= NEARBY_RADIUS; z++) {
                if (x == 0 && z == 0) continue;
                BlockPos feet = nearbyGround(player, ground.offset(x, 0, z));
                if (feet == null || !AirSteering.reachable(player, feet)) continue;
                List<LandingPlan> plans = plansAt(context, feet, protectedCell, noEntryCells);
                if (plans.isEmpty()) continue;
                double cost = (hasWater(plans) ? 0 : 100) + player.position().distanceToSqr(Vec3.atBottomCenterOf(feet));
                if (cost < score) {
                    best = plans;
                    score = cost;
                }
            }
        }
        return best;
    }

    // 落在这一格能用的办法：身体放得下、干草减完伤还活着的才留。
    private static List<LandingPlan> plansAt(PlayerContext context, BlockPos feet, Predicate<BlockPos> protectedCell,
                                             LongSet noEntryCells) {
        LocalPlayer player = context.localPlayer();
        Level level = context.level();
        if (player.isPassenger() || !level.isLoaded(feet) || !level.isLoaded(feet.below())) return List.of();
        LandingOptions options = LandingOptions.capture(player, true);
        List<LandingPlan> usable = new ArrayList<>();
        for (LandingPlan plan : options.plans(level, feet, protectedCell)) {
            if (!LandingGeometry.safe(level, level::isLoaded, plan, options.width(), options.height(), noEntryCells)) {
                continue;
            }
            if (!plan.survives(FallDamage.capture(player), player.getY(), true)) continue;
            usable.add(plan);
        }
        return usable;
    }

    private static boolean hasWater(List<LandingPlan> plans) {
        return plans.stream().anyMatch(plan -> plan.aid() == LandingAid.WATER);
    }

    // 某一柱从角色高度往下第一处碰撞面：射线要落在这一柱、这一格里才算，落脚的是它上面那一格。
    private static BlockPos nearbyGround(LocalPlayer player, BlockPos column) {
        Level level = player.level();
        if (!level.isLoaded(column)) return null;
        double top = Math.min(player.getY(), level.getMaxBuildHeight());
        var hit = level.clip(new ClipContext(new Vec3(column.getX() + .5, top, column.getZ() + .5),
                new Vec3(column.getX() + .5, level.getMinBuildHeight(), column.getZ() + .5),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        boolean inColumn = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().getX() == column.getX()
                && hit.getBlockPos().getZ() == column.getZ() && level.isLoaded(hit.getBlockPos())
                && new AABB(hit.getBlockPos()).inflate(.00001).contains(hit.getLocation());
        return inColumn ? hit.getBlockPos().above() : null;
    }

    /**
     * 身体底下最先碰到的支撑：身体中心和四个角各往下打一条射线，取最近的——只看中心会漏掉擦到的台阶边。
     * 有一条落在未加载的区块、或射线结果对不上世界坐标（物理结构模组的情况），就当不知道。
     */
    static BlockPos groundBelow(LocalPlayer player) {
        Level level = player.level();
        double top = Math.min(player.getY(), level.getMaxBuildHeight());
        double bottom = level.getMinBuildHeight();
        if (!Double.isFinite(top - bottom) || top <= bottom) return null;
        AABB box = player.getBoundingBox();
        Vec3[] origins = {new Vec3(player.getX(), top, player.getZ()), new Vec3(box.minX, top, box.minZ),
                new Vec3(box.maxX, top, box.minZ), new Vec3(box.minX, top, box.maxZ), new Vec3(box.maxX, top, box.maxZ)};
        BlockPos best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (Vec3 from : origins) {
            if (!level.isLoaded(BlockPos.containing(from.x, Math.min(top, level.getMaxBuildHeight() - 1), from.z))) {
                return null;
            }
            var hit = level.clip(new ClipContext(from, new Vec3(from.x, bottom, from.z),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK) continue;
            double drop = from.y - hit.getLocation().y;
            if (!Double.isFinite(drop) || drop < -1.0E-5 || drop > top - bottom + 1.0E-5
                    || Math.abs(hit.getLocation().x - from.x) > 1.0E-5 || Math.abs(hit.getLocation().z - from.z) > 1.0E-5
                    || !new AABB(hit.getBlockPos()).inflate(1.0E-5).contains(hit.getLocation())) {
                return null;
            }
            if (!level.isLoaded(hit.getBlockPos())) return null;
            if (drop < nearest) {
                nearest = drop;
                best = hit.getBlockPos();
            }
        }
        return best;
    }

    // 从支撑面到身体之间有液体（水、岩浆）就没有撞击：原版落进液体清掉摔落；遇到未加载按没有算，保留救援的机会。
    private static boolean liquidOnTheWay(LocalPlayer player, BlockPos ground) {
        Level level = player.level();
        for (BlockPos pos = ground; pos.getY() <= player.blockPosition().getY(); pos = pos.above()) {
            if (!level.isLoaded(pos)) return false;
            if (!level.getFluidState(pos).isEmpty()) return true;
        }
        return false;
    }

    private static float predictedDamage(LocalPlayer player, BlockPos support) {
        double height = AirSteering.supportHeight(player.level(), support);
        if (!Double.isFinite(height)) height = 1;
        return FallDamage.capture(player).damage(Math.max(0, player.getY() - support.getY() - height),
                FallDamage.Landing.of(player.level().getBlockState(support)), true);
    }
}
