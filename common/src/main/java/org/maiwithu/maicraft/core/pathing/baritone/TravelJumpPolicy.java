// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.Baritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementTraverse;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 赶路跑跳 —— MaiCraft {@code SprintPolicy} 在 Baritone 执行层的移植。
 *
 * <p>已经决定疾跑的平直路段,当跑道、支撑与头顶净空都被现成路径证明时,按住跳跃把
 * 疾跑换成跑跳;恰好经过的两格高顶头走廊(树下、隧道)用短促的顶头连跳进一步加速。
 * 只读取既有路径事实,绝不为了找一个顶头点改道。平走接上台的直跳由
 * {@link #ascendLaunchReady} 提供起跳时机:物理投影证明这一跳能落上平台,
 * 而不是走到面前卡住了再补跳。</p>
 *
 * <p>与原实现的差异:Baritone 的移动原语在空中也持续按住前进(splice 逻辑按真实脚位
 * 对齐路径),因此不移植 travel-jump 的空中相位与压舵状态。</p>
 */
public final class TravelJumpPolicy {

    /** 超出这个物理飞行窗仍不落地的属性组合不交给普通赶路跳。 */
    private static final int MAX_PROJECTED_AIRBORNE_TICKS = 40;
    /** 原版普通地面的摩擦系数;更滑的支撑不采用一格制动余量。 */
    private static final float NORMAL_GROUND_FRICTION = 0.6F;

    private TravelJumpPolicy() {}

    /**
     * 这一 tick 是否应该在疾跑中起跳。调用点在 Baritone 已决定疾跑之后
     * ({@code shouldSprintNextTick} 的 {@code requested} 分支),因此饥饿与疾跑许可是
     * 调用方已验证的前置条件;这里只裁决"跳了能不能安全落回原路"。
     */
    public static boolean shouldTravelJump(Baritone baritone, List<IMovement> movements,
                                           int pathPosition) {
        IPlayerContext ctx = baritone.getPlayerContext();
        LocalPlayer player = ctx.player();
        if (player == null) {
            return false;
        }
        if (!player.onGround() || player.isInWater() || player.isPassenger()
                || player.hasEffect(MobEffects.LEVITATION)
                || player.hasEffect(MobEffects.SLOW_FALLING)) {
            return false;
        }
        IMovement currentRaw = movements.get(pathPosition);
        if (!(currentRaw instanceof MovementTraverse current)
                || !isSafeDryTraverse(baritone, current, ctx)) {
            return false;
        }

        Vec3i direction = current.getDirection();
        if (direction.getY() != 0
                || Math.abs(direction.getX()) + Math.abs(direction.getZ()) != 1) {
            return false;
        }

        // 起跳只在前进动量已经建立后才有意义:物理判据而非计时器——新路线先在
        // 普通疾跑下加速,第一个速度指向既定边缘的落地刻才变得 eligible。
        Vec3 velocity = player.getDeltaMovement();
        double forwardSpeed = velocity.x * direction.getX() + velocity.z * direction.getZ();
        if (forwardSpeed <= 0.0) {
            return false;
        }

        BlockPos feet = ctx.playerFeet();
        boolean headHit = feet.equals(current.getSrc())
                && hasFullTwoBlockCeiling(ctx, current.getSrc())
                && hasFullTwoBlockCeiling(ctx, current.getDest());
        JumpProjection projection = projectJump(player, direction, headHit);
        if (projection == null) {
            return false;
        }

        // 投影已被飞行窗约束;只扫描能容纳这次飞行的具体格位外加最坏的结构余量
        // (随后上升占两格)。这段有限跑道一旦证明成立,更长直路的其余部分改变不了
        // 本次起跳决定,刻意不检查。
        double worstControlReserve = 2.0 + player.getBbWidth() * 0.5 + forwardSpeed;
        double sufficientRunway = projection.forwardDistance() + worstControlReserve;
        int lastSafeMovement = pathPosition - 1;
        BlockPos runEnd = current.getSrc();
        boolean boundedRunwayProven = false;
        for (int i = pathPosition; i < movements.size(); i++) {
            IMovement movement = movements.get(i);
            if (!(movement instanceof MovementTraverse traverse)
                    || !direction.equals(movement.getDirection())
                    || !isSafeDryTraverse(baritone, traverse, ctx)) {
                break;
            }
            lastSafeMovement = i;
            runEnd = movement.getDest();
            double checkedRunway = direction.getX()
                    * (runEnd.getX() + 0.5 - player.getX())
                    + direction.getZ() * (runEnd.getZ() + 0.5 - player.getZ());
            if (checkedRunway > sufficientRunway) {
                boundedRunwayProven = true;
                break;
            }
        }
        if (lastSafeMovement < pathPosition) {
            return false;
        }

        double remainingRunway = direction.getX() * (runEnd.getX() + 0.5 - player.getX())
                + direction.getZ() * (runEnd.getZ() + 0.5 - player.getZ());
        IMovement afterRun = !boundedRunwayProven
                && lastSafeMovement + 1 < movements.size()
                ? movements.get(lastSafeMovement + 1)
                : null;
        // 保留一个完整已规划格用于落地后的转向/制动;随后是上升时保留第二格,因为
        // 上升的专属起跳从最后一条平边的起点发起,跑跳必须在那之前落地。
        double controlReserve = boundedRunwayProven
                ? worstControlReserve
                : (afterRun instanceof MovementAscend ? 2.0 : 1.0)
                        + player.getBbWidth() * 0.5 + forwardSpeed;
        double maximumLandingDistance = remainingRunway - controlReserve;
        if (maximumLandingDistance <= 0.0) {
            return false;
        }

        // 空中把整个身体保持在一格宽的走廊里。允许当前的横向速度,条件是原版空气
        // 阻力能在 AABB 离开已验证的路径柱之前耗散它。
        double lateralOffset = Math.abs(direction.getX()
                * (current.getSrc().getZ() + 0.5 - player.getZ()))
                + Math.abs(direction.getZ()
                        * (current.getSrc().getX() + 0.5 - player.getX()));
        Vec3 launchVelocity = sprintJumpLaunchVelocity(player);
        double lateralSpeed = Math.abs(direction.getX() * launchVelocity.z
                + direction.getZ() * launchVelocity.x);
        double lateralRoom = 0.5 - player.getBbWidth() * 0.5 - lateralOffset;
        if (lateralRoom <= 0.0
                || lateralSpeed * projection.airDragSum() >= lateralRoom) {
            return false;
        }

        if (projection.forwardDistance() >= maximumLandingDistance) {
            return false;
        }

        // 敞空跳不允许半途发现顶盖;反过来,顶头连跳只在身体已经处于一段足够覆盖
        // 本次投影跳步的连续实心顶盖之下才启用。路径位逐格检查,共享的移动终点
        // 伪造不出两格走廊。
        return ceilingProfileMatches(ctx, movements, pathPosition, lastSafeMovement, direction,
                projection.forwardDistance(), headHit);
    }
    /**
     * 平走→上台直跳的起跳时机:这一落地刻起跳能否不先撞上台沿竖直面,并落上平台
     * 或其身后已验证的干燥落格。返回 false 表示提前窗口未开(或已错过);普通上升
     * 移动保持活跃,执行它原地的近距离跳。
     */
    public static boolean ascendLaunchReady(Baritone baritone, MovementTraverse current,
                                            MovementAscend next, IMovement nextNext) {
        IPlayerContext ctx = baritone.getPlayerContext();
        LocalPlayer player = ctx.player();
        if (player == null
                || !(nextNext instanceof MovementTraverse)
                || !ctx.playerFeet().equals(current.getSrc())) {
            return false;
        }
        Vec3i direction = current.getDirection();
        Vec3 liveVelocity = player.getDeltaMovement();
        if (liveVelocity.x * direction.getX() + liveVelocity.z * direction.getZ() <= 0.0) {
            return false;
        }

        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        double verticalSpeed = jumpVerticalSpeed(player);
        Vec3 launchVelocity = sprintJumpLaunchVelocity(player);
        double horizontalSpeed = launchVelocity.x * direction.getX()
                + launchVelocity.z * direction.getZ();
        if (gravity <= 0.0 || verticalSpeed <= 0.0 || horizontalSpeed <= 0.0) {
            return false;
        }

        double targetCenter = direction.getX() * (next.getDest().getX() + 0.5 - player.getX())
                + direction.getZ() * (next.getDest().getZ() + 0.5 - player.getZ());
        double firstBodyOverlap = targetCenter - 0.5 - player.getBbWidth() * 0.5;
        MovementTraverse landing = (MovementTraverse) nextNext;
        double lastValidFeet = direction.getX()
                * (landing.getDest().getX() + 0.5 - player.getX())
                + direction.getZ() * (landing.getDest().getZ() + 0.5 - player.getZ())
                + 0.5;
        if (firstBodyOverlap <= 0.0 || lastValidFeet <= firstBodyOverlap) {
            return false;
        }

        double height = 0.0;
        double horizontal = 0.0;
        boolean enteredHighPlatform = false;
        for (int tick = 0; tick < MAX_PROJECTED_AIRBORNE_TICKS; tick++) {
            double previousHorizontal = horizontal;
            horizontal += horizontalSpeed;
            double nextHeight = height + verticalSpeed;
            boolean descending = verticalSpeed <= 0.0;

            if (!enteredHighPlatform && horizontal >= firstBodyOverlap) {
                // 组合移动可能在某一 tick 内落到顶面上;但若该 tick 竖直扫描的两端
                // 都够不到一格高,身体撞的是侧面——动量窗口已经错过了。
                if (Math.max(height, nextHeight) < 1.0) {
                    return false;
                }
                enteredHighPlatform = true;
            }
            if (enteredHighPlatform && descending && height >= 1.0 && nextHeight <= 1.0) {
                // 碰撞解算在本组合移动 tick 的 pre-horizontal 身位与顶面相遇;要求该
                // 身位属于已验证的两格落条。
                return previousHorizontal < lastValidFeet;
            }
            if (horizontal >= lastValidFeet || nextHeight <= 0.0) {
                return false;
            }

            height = nextHeight;
            verticalSpeed = (verticalSpeed - gravity) * 0.98;
            horizontalSpeed = (horizontalSpeed + 0.02) * 0.91;
        }
        return false;
    }

    /** 同向共线、无待挖/待放/待挤格、双端支撑干燥的平走。 */
    private static boolean isSafeDryTraverse(Baritone baritone, MovementTraverse movement,
                                             IPlayerContext ctx) {
        if (movement.getSrc().getY() != movement.getDest().getY()) {
            return false;
        }
        if (!movement.toBreak(baritone.bsi).isEmpty()
                || !movement.toPlace(baritone.bsi).isEmpty()
                || !movement.toWalkInto(baritone.bsi).isEmpty()) {
            return false;
        }
        return isSafeDryCell(ctx, movement.getSrc())
                && isSafeDryCell(ctx, movement.getDest());
    }

    private static boolean isSafeDryCell(IPlayerContext ctx, BlockPos feet) {
        BlockPos support = feet.below();
        BlockState supportState = ctx.world().getBlockState(support);
        return supportState.getFluidState().isEmpty()
                && supportState.isCollisionShapeFullBlock(ctx.world(), support)
                && supportState.getBlock().getFriction() <= NORMAL_GROUND_FRICTION
                && MovementHelper.fullyPassable(ctx, feet)
                && MovementHelper.fullyPassable(ctx, feet.above());
    }

    /** 仅当这一路径格上方恰有两格高的实心顶盖。 */
    private static boolean hasFullTwoBlockCeiling(IPlayerContext ctx, BlockPos feet) {
        BlockPos ceiling = feet.above(2);
        var state = ctx.world().getBlockState(ceiling);
        return state.getFluidState().isEmpty()
                && state.isCollisionShapeFullBlock(ctx.world(), ceiling);
    }

    private static boolean ceilingProfileMatches(
            IPlayerContext ctx, List<IMovement> movements, int pathPosition,
            int lastSafeMovement, Vec3i direction, double forwardDistance,
            boolean headHit) {
        LocalPlayer player = ctx.player();
        double bodyHalf = player.getBbWidth() * 0.5;
        double sweptUntil = forwardDistance + bodyHalf;
        BlockPos first = movements.get(pathPosition).getSrc();
        if (!ceilingCellMatches(ctx, first, headHit)) {
            return false;
        }
        for (int i = pathPosition; i <= lastSafeMovement; i++) {
            BlockPos cell = movements.get(i).getDest();
            double ahead = direction.getX() * (cell.getX() + 0.5 - player.getX())
                    + direction.getZ() * (cell.getZ() + 0.5 - player.getZ());
            if (ahead - bodyHalf > sweptUntil) {
                return true;
            }
            if (!ceilingCellMatches(ctx, cell, headHit)) {
                return false;
            }
            if (ahead >= sweptUntil) {
                return true;
            }
        }
        return false;
    }

    private static boolean ceilingCellMatches(
            IPlayerContext ctx, BlockPos feet, boolean requireFullCeiling) {
        if (requireFullCeiling) {
            return hasFullTwoBlockCeiling(ctx, feet);
        }
        BlockPos ceiling = feet.above(2);
        var state = ctx.world().getBlockState(ceiling);
        return state.getFluidState().isEmpty()
                && state.getCollisionShape(ctx.world(), ceiling).isEmpty();
    }

    /**
     * 从当前路线对齐速度出发的保守原版跳跃投影。竖直飞行时间从玩家权威的
     * 跳跃/重力属性积分;水平距离含疾跑跳冲量与按住前进的空中控制。实心顶盖
     * 截断第一次向上碰撞,自然缩短跳步。
     */
    private static JumpProjection projectJump(LocalPlayer player, Vec3i direction, boolean headHit) {
        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        double verticalSpeed = jumpVerticalSpeed(player);
        if (gravity <= 0.0 || verticalSpeed <= 0.0) {
            return null;
        }
        double height = 0.0;
        double headroom = Math.max(0.0, 2.0 - player.getBbHeight());
        Vec3 launchVelocity = sprintJumpLaunchVelocity(player);
        double forwardSpeed = Math.max(0.0,
                launchVelocity.x * direction.getX()
                        + launchVelocity.z * direction.getZ());
        double forwardDistance = 0.0;
        double airDragSum = 0.0;
        double drag = 1.0;
        int airborneTicks = 0;
        do {
            // 局部物理飞行窗防止极小的模组重力把一个客户端 tick 变成无界数值循环。
            // 调用方把这个有界投影换算成需要检查的精确有限格数。
            if (airborneTicks >= MAX_PROJECTED_AIRBORNE_TICKS) {
                return null;
            }
            forwardDistance += forwardSpeed;
            airDragSum += drag;
            forwardSpeed = (forwardSpeed + 0.02) * 0.91;
            drag *= 0.91;

            double nextHeight = height + verticalSpeed;
            if (headHit && nextHeight > headroom) {
                height = headroom;
                verticalSpeed = 0.0;
            } else {
                height = nextHeight;
                verticalSpeed = (verticalSpeed - gravity) * 0.98;
            }
            airborneTicks++;
        } while (height > 0.0);
        return new JumpProjection(forwardDistance, airDragSum);
    }

    /** LivingEntity#getJumpPower 同源的权威跳跃强度组成。 */
    private static double jumpVerticalSpeed(LocalPlayer player) {
        float bodyJumpFactor = player.level().getBlockState(player.blockPosition())
                .getBlock().getJumpFactor();
        float supportJumpFactor = player.level()
                .getBlockState(player.getBlockPosBelowThatAffectsMyMovement())
                .getBlock().getJumpFactor();
        double blockJumpFactor = bodyJumpFactor == 1.0F
                ? supportJumpFactor : bodyJumpFactor;
        return player.getAttributeValue(Attributes.JUMP_STRENGTH)
                * blockJumpFactor + player.getJumpBoostPower();
    }

    /** 当前速度加上原版按朝向施加的 0.2 疾跑跳冲量。 */
    private static Vec3 sprintJumpLaunchVelocity(LocalPlayer player) {
        double yaw = Math.toRadians(player.getYRot());
        Vec3 velocity = player.getDeltaMovement();
        return velocity.add(-Math.sin(yaw) * 0.2, 0.0, Math.cos(yaw) * 0.2);
    }

    private record JumpProjection(double forwardDistance, double airDragSum) {}
}
