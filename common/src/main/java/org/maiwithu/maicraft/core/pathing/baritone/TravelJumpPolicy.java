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
 * 赶路跑跳 —— MaiCraft {@code SprintPolicy} 在 Baritone 执行层的移植。
 *
 * <p>判据是"路线已经承诺了一段完整直跑道,并且能承受落地伤害":投影覆盖的每个路径
 * 原语都必须是同高同向平走,物理走廊的每一列也必须在可承受落点带(抬升一格/平/
 * 落一格)里有干燥实心支撑。于是终点前、拐弯前、落差前、液体或立柱前都会自然
 * 收步,不会为了看起来快而飞出选定路线。恰好处于两格高顶头走廊(树下、隧道)时
 * 投影自动缩短为顶头连跳,且只使用路线本来就经过的顶盖。空中不指望转向:身体沿
 * 出发方向飞,落点偏出路径时由 Baritone 的 splice 逻辑重新对齐。</p>
 *
 * <p>平走接上台的直跳由 {@link #ascendLaunchReady} 提供起跳时机:物理投影证明这一跳
 * 能落上平台,而不是走到面前卡住了再补跳。</p>
 *
 * <p>与原实现的差异:Baritone 的移动原语在空中也持续按住前进(splice 逻辑按真实脚位
 * 对齐路径),因此不移植 travel-jump 的空中相位与压舵状态。</p>
 */
public final class TravelJumpPolicy {

    /** 超出这个物理飞行窗仍不落地的属性组合不交给普通赶路跳。 */
    private static final int MAX_PROJECTED_AIRBORNE_TICKS = 40;
    /** 原版普通地面的摩擦系数;更滑的支撑上起跳,速度投影不可信。 */
    private static final float NORMAL_GROUND_FRICTION = 0.6F;

    private TravelJumpPolicy() {}

    /**
     * 这一 tick 是否应该在疾跑中起跳。调用点在 Baritone 已决定疾跑之后
     * ({@code shouldSprintNextTick} 的 {@code requested} 分支),因此饥饿、疾跑许可
     * 与前进动量都是调用方已验证的前置条件;这里只裁决"这一跳落不落得下去"。
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
        // 交互格全交给走廊与点击门裁决:待挖/待放/待挤目标只在身体真的停下来
        // 操作(本 tick 正在点击)时阻止起跳;走廊扫描已验证飞跃的落点,跳过去
        // 比停下来挖一格或搭一格更快。
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)
                || baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT)) {
            return false;
        }
        Vec3i direction = current.getDirection();
        HorizontalHeading heading = HorizontalHeading.of(direction);
        if (heading == null) {
            return false;
        }

        // 起跳只在前进动量已经建立后才有意义:物理判据而非计时器——新路线先在
        // 普通疾跑下加速,第一个速度指向既定边缘的落地刻才变得 eligible。
        Vec3 velocity = player.getDeltaMovement();
        double forwardSpeed = velocity.x * heading.x() + velocity.z * heading.z();
        if (forwardSpeed <= 0.0) {
            return false;
        }

        if (!isStableTakeoff(ctx, ctx.playerFeet())) {
            return false;
        }

        // 顶头走廊:短投影只在顶盖真覆盖整段短走廊时成立——中途没了顶盖就按
        // 敞空投影(撞上中途顶盖只会提前落在已验证列上,反过来则会飞出走廊)。
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

        // 空中把身体保持在一格宽的走廊里:当前的横向偏移,加上原版空气阻力能耗散的
        // 横向速度,都必须留在出发列的半格余量之内。
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

        // 跳跃可达走廊:从玩家当前格沿移动方向逐列验证——投影距离已从实时速度
        // 出发,余量只需覆盖身体半宽与落点方差。任何一列在可承受落点带里没有
        // 干燥安全支撑(深洞、流体、立柱),这一跳就可能摔进它——不跳,走过去再说。
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
     * The travel jump may only consume cells that the selected path already owns as a straight,
     * level runway. This is route-derived rather than a magic "long trip" distance: the physical
     * jump projection itself decides how many committed movements are required.
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
     * 平走→上台直跳的起跳时机:从当前实时身位与速度投影,这一 tick 起跳能否不先
     * 撞上台沿竖直面,并落上平台或其身后已验证的落格。窗口随距离实时开合——
     * 起跳点在台前一格半左右,不是走到台沿才跳。返回 false 表示窗口未开(或已
     * 错过);普通上升移动保持活跃,执行它原地的近距离跳。
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
        // 落点跑道:上台后接平走则验证到平走终点;接上台(台阶连跳)或其它移动时
        // 只验证当前平台格——足够裁决这一跳能不能落上去。
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

    /**
     * 这一列上是否存在可承受的落点。落点带是 [抬升一格, 平, 落一格]:坠落距离
     * = 投影弧顶 - 落点高度,预计落地后生命值必须大于零。要求落点两格可穿行、
     * 干燥、不是危险格(仙人掌/岩浆块/火/浆果)、支撑是实心整块。
     */
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

    /** Every possible diagonal corner and landing column stays inside the selected dry runway. */
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
     * 从当前路线对齐速度出发的保守原版跳跃投影。竖直飞行时间从玩家权威的
     * 跳跃/重力属性积分;水平距离含疾跑跳冲量与按住前进的空中控制。实心顶盖
     * 截断第一次向上碰撞,自然缩短跳步。同时记录弧顶高度,用于落点带的
     * 坠落距离裁决。
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
            // 局部物理飞行窗防止极小的模组重力把一个客户端 tick 变成无界数值循环。
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

    /**
     * 当前速度加上原版的 0.2 疾跑跳冲量。物理旋转桥会在 jumpFromGround 内把
     * 可见的平滑相机 yaw 临时替换成 Baritone 的路线朝向，所以投影也必须使用同一个
     * 路线坐标系；读 player.getYRot() 会把镜头尚未转完的误差重新带回落点判断。
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
