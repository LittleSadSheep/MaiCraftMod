// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.Baritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDiagonal;
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
 * 决定赶路时能不能顺势跑跳，以及靠近一格高平台时何时提前跳。
 * 先按当前速度估计会飞多远，再检查已选路线够不够直、会不会撞头、途中有没有可承受的落点。
 * 它只批准起跳，空中的按键和路线进度仍由现用导航执行。
 */
public final class TravelJumpPolicy {

    /** 超出这个物理飞行窗仍不落地的属性组合不交给普通赶路跳。 */
    private static final int MAX_PROJECTED_AIRBORNE_TICKS = 40;
    /** 原版普通地面的摩擦系数;更滑的支撑上起跳,速度投影不可信。 */
    private static final float NORMAL_GROUND_FRICTION = 0.6F;

    private TravelJumpPolicy() {}

    /**
     * 调用方已经允许疾跑；这里再检查站稳、当前没有点击操作、前进速度和整段起落空间，全部通过才跳。
     */
    public static boolean shouldTravelJump(Baritone baritone, List<IMovement> movements,
                                           int pathPosition, java.util.function.Consumer<List<IMovement>> verifiedRunway) {
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
        IMovement current = movements.get(pathPosition);
        if (!isFlatRunwayMovement(current)) {
            return false;
        }
        // 这一刻正在挖掘或右键操作时不跳；将来路线上的交互格，继续由后面的通行与落点检查判断。
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)
                || baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT)) {
            return false;
        }
        Vec3i direction = current.getDirection();
        HorizontalHeading heading = HorizontalHeading.of(direction);
        if (heading == null) {
            return false;
        }

        // 先沿路线加速，有向前的速度后才考虑跑跳。
        Vec3 velocity = player.getDeltaMovement();
        double forwardSpeed = velocity.x * heading.x() + velocity.z * heading.z();
        if (forwardSpeed <= 0.0) {
            return false;
        }

        if (!isStableTakeoff(ctx, ctx.playerFeet())) {
            return false;
        }

        // 只有低顶盖连续覆盖这一跳时，才按撞头后的短跳计算；中途顶盖消失就按完整跳距预留落点。
        BlockPos src = current.getSrc();
        BlockPos takeoff = ctx.playerFeet();
        boolean headHit = false;
        if (takeoff.equals(src) && hasFullTwoBlockCeiling(ctx, src)) {
            JumpProjection capped = projectJump(player, heading, true);
            if (capped != null) {
                int cappedMovements = (int) Math.ceil(
                        (capped.forwardDistance() + player.getBbWidth() * 0.5 + 0.25)
                                / heading.stepLength());
                headHit = hasContinuousCeiling(
                        ctx, movements, pathPosition, direction, cappedMovements);
            }
        }

        JumpProjection projection = projectJump(player, heading, headHit);
        if (projection == null) {
            return false;
        }

        // 已有侧向偏移加上起跳后的侧向惯性，不能把身体推出这一格宽的通道。
        double relativeX = player.getX() - (src.getX() + 0.5D);
        double relativeZ = player.getZ() - (src.getZ() + 0.5D);
        double lateralOffset = Math.abs(-heading.z() * relativeX + heading.x() * relativeZ);
        Vec3 launchVelocity = sprintJumpLaunchVelocity(player, heading);
        double lateralSpeed = Math.abs(-heading.z() * launchVelocity.x
                + heading.x() * launchVelocity.z);
        double lateralRoom = 0.5 - player.getBbWidth() * 0.5 - lateralOffset;
        if (lateralRoom <= 0.0
                || lateralSpeed * projection.airDragSum() >= lateralRoom) {
            return false;
        }

        // 按预计跳距加上身体宽度和余量检查前方路线；任何可能经过的列缺少安全落点就取消跑跳。
        double reach = projection.forwardDistance() + player.getBbWidth() * 0.5 + 0.25;
        int runwayMovements = (int) Math.ceil(reach / heading.stepLength());
        if (!routeCommitsStraightRunway(
                movements, pathPosition, direction, runwayMovements)) {
            return false;
        }
        FallDamageBudget fallBudget = FallDamageBudget.capture(player);
        for (int offset = 0; offset < runwayMovements; offset++) {
            IMovement movement = movements.get(pathPosition + offset);
            if (!safeFlightMovement(ctx, movement, projection.apexHeight(), headHit, fallBudget)) {
                return false;
            }
        }
        verifiedRunway.accept(List.copyOf(movements.subList(pathPosition, pathPosition + runwayMovements)));
        return true;
    }

    /**
     * 这一跳覆盖的路线段必须首尾相接、方向相同且高度不变；前方就要转弯或到终点时不能提前跳过头。
     */
    private static boolean routeCommitsStraightRunway(
            List<IMovement> movements, int pathPosition, Vec3i direction, int columns) {
        if (columns < 1 || pathPosition < 0) {
            return false;
        }
        BlockPos expectedSource = movements.get(pathPosition).getSrc();
        for (int offset = 0; offset < columns; offset++) {
            int index = pathPosition + offset;
            if (index >= movements.size()
                    || !isFlatRunwayMovement(movements.get(index))
                    || !movements.get(index).getSrc().equals(expectedSource)
                    || !movements.get(index).getDirection().equals(direction)) {
                return false;
            }
            expectedSource = movements.get(index).getDest();
        }
        return true;
    }

    private static boolean isFlatRunwayMovement(IMovement movement) {
        return (movement instanceof MovementTraverse || movement instanceof MovementDiagonal)
                && movement.getSrc().getY() == movement.getDest().getY()
                && HorizontalHeading.of(movement.getDirection()) != null;
    }

    /**
     * 估计现在起跳能否越过平台侧面，并落在平台或紧接着的平走段上；太早、太晚或飞过落点都不批准。
     */
    public static boolean ascendLaunchReady(Baritone baritone, MovementTraverse current,
                                            MovementAscend next, IMovement nextNext) {
        IPlayerContext ctx = baritone.getPlayerContext();
        LocalPlayer player = ctx.player();
        if (player == null
                || !player.onGround() || player.isInWater() || player.isPassenger()
                || player.hasEffect(MobEffects.LEVITATION)
                || player.hasEffect(MobEffects.SLOW_FALLING)) {
            return false;
        }
        Vec3i direction = current.getDirection();
        Vec3 liveVelocity = player.getDeltaMovement();
        if (liveVelocity.x * direction.getX() + liveVelocity.z * direction.getZ() <= 0.0) {
            return false;
        }

        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        double verticalSpeed = jumpVerticalSpeed(player);
        Vec3 launchVelocity = sprintJumpLaunchVelocity(player, direction);
        double horizontalSpeed = launchVelocity.x * direction.getX()
                + launchVelocity.z * direction.getZ();
        if (gravity <= 0.0 || verticalSpeed <= 0.0 || horizontalSpeed <= 0.0) {
            return false;
        }

        double targetCenter = direction.getX() * (next.getDest().getX() + 0.5 - player.getX())
                + direction.getZ() * (next.getDest().getZ() + 0.5 - player.getZ());
        double firstBodyOverlap = targetCenter - 0.5 - player.getBbWidth() * 0.5;
        // 平台后面紧接平走时把该终点也算作可落范围；其他后续动作只计算本次平台。
        BlockPos landingEnd = nextNext instanceof MovementTraverse landing
                ? landing.getDest() : next.getDest();
        double lastValidFeet = direction.getX()
                * (landingEnd.getX() + 0.5 - player.getX())
                + direction.getZ() * (landingEnd.getZ() + 0.5 - player.getZ())
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
                // 身体已经碰到平台边缘，却还没升到一格高，撞到的就是侧面。
                if (Math.max(height, nextHeight) < 1.0) {
                    return false;
                }
                enteredHighPlatform = true;
            }
            if (enteredHighPlatform && descending && height >= 1.0 && nextHeight <= 1.0) {
                // 落到平台高度时，按本次水平位移前的位置检查是否还在允许的落地范围内。
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

    /**
     * 分别检查高一格、同高度和低一格的落点；身体要放得下、地面要干燥结实，而且估计落地后还能活着。
     */
    // 下面的 hasChunkAt 在原版客户端恒为真；它不能单独保证落点列已经加载，仍要区分后续实际支撑检查。
    private static boolean survivableColumn(IPlayerContext ctx, BlockPos column, double apexHeight,
                                            FallDamageBudget fallBudget) {
        for (int landing = 1; landing >= -1; landing--) {
            BlockPos feetCell = column.above(landing);
            if (!ctx.world().hasChunkAt(feetCell)
                    || !fallBudget.survives(Math.max(0, apexHeight - landing),
                    FallDamageBudget.Landing.of(ctx.world().getBlockState(feetCell.below())), false)) continue;
            if (!MovementHelper.fullyPassable(ctx, feetCell)
                    || !MovementHelper.fullyPassable(ctx, feetCell.above())) {
                continue;
            }
            if (MovementHelper.avoidWalkingInto(ctx.world().getBlockState(feetCell))
                    || MovementHelper.avoidWalkingInto(ctx.world().getBlockState(feetCell.above()))
                    || MovementHelper.avoidWalkingInto(ctx.world().getBlockState(feetCell.below()))) {
                continue;
            }
            if (isSolidDrySupport(ctx, feetCell.below())) {
                return true;
            }
        }
        return false;
    }

    /** 起跳格自身要站得稳:实心干燥支撑、普通摩擦、身体两格畅通。 */
    private static boolean isStableTakeoff(IPlayerContext ctx, BlockPos feet) {
        BlockState support = ctx.world().getBlockState(feet.below());
        return support.getFluidState().isEmpty()
                && support.isCollisionShapeFullBlock(ctx.world(), feet.below())
                && support.getBlock().getFriction() <= NORMAL_GROUND_FRICTION
                && MovementHelper.fullyPassable(ctx, feet)
                && MovementHelper.fullyPassable(ctx, feet.above());
    }

    private static boolean isSolidDrySupport(IPlayerContext ctx, BlockPos support) {
        BlockState state = ctx.world().getBlockState(support);
        return state.getFluidState().isEmpty()
                && state.isCollisionShapeFullBlock(ctx.world(), support);
    }

    /** 仅当这一路径格上方恰有两格高的实心顶盖。 */
    private static boolean hasFullTwoBlockCeiling(IPlayerContext ctx, BlockPos feet) {
        BlockPos ceiling = feet.above(2);
        var state = ctx.world().getBlockState(ceiling);
        return state.getFluidState().isEmpty()
                && state.isCollisionShapeFullBlock(ctx.world(), ceiling);
    }

    /** 顶头短跳只使用所选路线实际扫过的格；对角边的两个角格也必须连续有顶。 */
    private static boolean hasContinuousCeiling(
            IPlayerContext ctx, List<IMovement> movements, int pathPosition,
            Vec3i direction, int movementCount) {
        if (movementCount < 1 || pathPosition < 0
                || pathPosition >= movements.size()
                || !hasFullTwoBlockCeiling(ctx, movements.get(pathPosition).getSrc())) {
            return false;
        }
        for (int offset = 0; offset < movementCount; offset++) {
            int index = pathPosition + offset;
            if (index >= movements.size()) return false;
            IMovement movement = movements.get(index);
            if (!isFlatRunwayMovement(movement)
                    || !movement.getDirection().equals(direction)
                    || !hasFullTwoBlockCeiling(ctx, movement.getDest())) {
                return false;
            }
            if (movement instanceof MovementDiagonal) {
                BlockPos src = movement.getSrc();
                BlockPos dest = movement.getDest();
                if (!hasFullTwoBlockCeiling(ctx,
                            new BlockPos(src.getX(), src.getY(), dest.getZ()))
                        || !hasFullTwoBlockCeiling(ctx,
                            new BlockPos(dest.getX(), src.getY(), src.getZ()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 直行检查落点列；斜行还检查身体可能擦过的两个角，不能从危险角上斜穿过去。
     */
    private static boolean safeFlightMovement(
            IPlayerContext ctx, IMovement movement, double apexHeight, boolean headHit, FallDamageBudget fallBudget) {
        if (!safeFlightColumn(ctx, movement.getDest(), apexHeight, headHit, fallBudget)) return false;
        if (movement instanceof MovementDiagonal) {
            BlockPos src = movement.getSrc();
            BlockPos dest = movement.getDest();
            return safeFlightColumn(ctx,
                        new BlockPos(src.getX(), src.getY(), dest.getZ()), apexHeight, headHit, fallBudget)
                    && safeFlightColumn(ctx,
                        new BlockPos(dest.getX(), src.getY(), src.getZ()), apexHeight, headHit, fallBudget);
        }
        return true;
    }

    private static boolean safeFlightColumn(
            IPlayerContext ctx, BlockPos feet, double apexHeight, boolean headHit, FallDamageBudget fallBudget) {
        if (!survivableColumn(ctx, feet, apexHeight, fallBudget)
                || !MovementHelper.fullyPassable(ctx, feet)
                || !MovementHelper.fullyPassable(ctx, feet.above())) {
            return false;
        }
        if (headHit) return hasFullTwoBlockCeiling(ctx, feet);
        int highestBodyCell = Math.max(2,
                (int) Math.ceil(ctx.player().getBbHeight() + apexHeight) - 1);
        for (int y = 2; y <= highestBodyCell; y++) {
            if (!MovementHelper.fullyPassable(ctx, feet.above(y))) return false;
        }
        return true;
    }

    /**
     * 用当前跳跃强度和重力逐刻估计高度、向前距离和侧向惯性。低顶盖会截断上升，超过四十刻仍不落地就放弃估算。
     */
    private static JumpProjection projectJump(
            LocalPlayer player, HorizontalHeading heading, boolean headHit) {
        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        double verticalSpeed = jumpVerticalSpeed(player);
        if (gravity <= 0.0 || verticalSpeed <= 0.0) {
            return null;
        }
        double height = 0.0;
        double apex = 0.0;
        double headroom = Math.max(0.0, 2.0 - player.getBbHeight());
        Vec3 launchVelocity = sprintJumpLaunchVelocity(player, heading);
        double forwardSpeed = Math.max(0.0,
                launchVelocity.x * heading.x()
                        + launchVelocity.z * heading.z());
        double forwardDistance = 0.0;
        double airDragSum = 0.0;
        double drag = 1.0;
        int airborneTicks = 0;
        do {
            // 重力特别小时也只算四十刻，避免一次导航更新在这里耗时过长。
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
                apex = Math.max(apex, height);
                verticalSpeed = (verticalSpeed - gravity) * 0.98;
            }
            airborneTicks++;
        } while (height > 0.0);
        return new JumpProjection(forwardDistance, airDragSum, apex);
    }

    /**
     * 把跳跃属性、脚下方块的跳跃系数和跳跃提升效果合起来，得到起跳时向上的速度。
     */
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

    /**
     * 在当前速度上加沿路线方向的疾跑跳冲量；使用实际控制方向，避免尚未转完的显示镜头影响估计。
     */
    private static Vec3 sprintJumpLaunchVelocity(LocalPlayer player, Vec3i direction) {
        HorizontalHeading heading = HorizontalHeading.of(direction);
        if (heading == null) return player.getDeltaMovement();
        return sprintJumpLaunchVelocity(player, heading);
    }

    private static Vec3 sprintJumpLaunchVelocity(
            LocalPlayer player, HorizontalHeading heading) {
        Vec3 velocity = player.getDeltaMovement();
        return velocity.add(heading.x() * 0.2, 0.0, heading.z() * 0.2);
    }

    private record HorizontalHeading(double x, double z, double stepLength) {
        private static HorizontalHeading of(Vec3i direction) {
            if (direction == null || direction.getY() != 0) return null;
            int x = direction.getX();
            int z = direction.getZ();
            if (Math.abs(x) > 1 || Math.abs(z) > 1 || (x == 0 && z == 0)) return null;
            double length = Math.hypot(x, z);
            return new HorizontalHeading(x / length, z / length, length);
        }
    }

    private record JumpProjection(double forwardDistance, double airDragSum, double apexHeight) {}
}
