// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.Baritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
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
 * <p>判据是"跳下去不受伤":不要求一段被证明的长直跑道,而是沿当前移动方向扫描
 * 整个跳跃可达走廊,每一列都必须在无摔伤落点带(抬升一格/平/落一格,坠落距离全部
 * 不超过原版安全值)里有干燥实心支撑。走廊里出现深洞、流体或立柱就这一步不跳,
 * 走过去再跳——正是人在森林里冲刺跳的方式。恰好处于两格高顶头走廊(树下、隧道)
 * 时投影自动缩短为顶头连跳,获得更快的节奏。空中不指望转向:身体沿出发方向飞,
 * 落点偏出路径时由 Baritone 的 splice 逻辑重新对齐。</p>
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
    /** 原版安全坠落距离:超过这个下落才开始掉血。 */
    private static final double SAFE_FALL_DISTANCE = 3.0D;

    private TravelJumpPolicy() {}

    /**
     * 这一 tick 是否应该在疾跑中起跳。调用点在 Baritone 已决定疾跑之后
     * ({@code shouldSprintNextTick} 的 {@code requested} 分支),因此饥饿、疾跑许可
     * 与前进动量都是调用方已验证的前置条件;这里只裁决"这一跳落不落得下去"。
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
                || current.getSrc().getY() != current.getDest().getY()) {
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

        if (!isStableTakeoff(ctx, ctx.playerFeet())) {
            return false;
        }

        // 顶头走廊:短投影只在顶盖真覆盖整段短走廊时成立——中途没了顶盖就按
        // 敞空投影(撞上中途顶盖只会提前落在已验证列上,反过来则会飞出走廊)。
        BlockPos src = current.getSrc();
        BlockPos takeoff = ctx.playerFeet();
        boolean headHit = takeoff.equals(src)
                && hasContinuousCeiling(ctx, src, direction, HEAD_HIT_CORRIDOR_COLUMNS);

        JumpProjection projection = projectJump(player, direction, headHit);
        if (projection == null) {
            return false;
        }

        // 空中把身体保持在一格宽的走廊里:当前的横向偏移,加上原版空气阻力能耗散的
        // 横向速度,都必须留在出发列的半格余量之内。
        double lateralOffset = Math.abs(direction.getX() * (src.getZ() + 0.5 - player.getZ()))
                + Math.abs(direction.getZ() * (src.getX() + 0.5 - player.getX()));
        Vec3 launchVelocity = sprintJumpLaunchVelocity(player);
        double lateralSpeed = Math.abs(direction.getX() * launchVelocity.z
                + direction.getZ() * launchVelocity.x);
        double lateralRoom = 0.5 - player.getBbWidth() * 0.5 - lateralOffset;
        if (lateralRoom <= 0.0
                || lateralSpeed * projection.airDragSum() >= lateralRoom) {
            return false;
        }

        // 跳跃可达走廊:从玩家当前格沿移动方向逐列验证——投影距离已从实时速度
        // 出发,余量只需覆盖身体半宽与落点方差。任何一列在无摔伤落点带里没有
        // 干燥安全支撑(深洞、流体、立柱),这一跳就可能摔进它——不跳,走过去再说。
        double reach = projection.forwardDistance() + player.getBbWidth() * 0.5 + 0.25;
        int columns = (int) Math.ceil(reach);
        for (int d = 1; d <= columns; d++) {
            BlockPos column = takeoff.offset(direction.getX() * d, 0, direction.getZ() * d);
            if (!survivableColumn(ctx, column, projection.apexHeight())) {
                return false;
            }
        }
        return true;
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
        Vec3 launchVelocity = sprintJumpLaunchVelocity(player);
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
     * 这一列上是否存在无摔伤的落点。落点带是 [抬升一格, 平, 落一格]:坠落距离
     * = 投影弧顶 - 落点高度,不得超过原版安全坠落距离。要求落点两格可穿行、
     * 干燥、不是危险格(仙人掌/岩浆块/火/浆果)、支撑是实心整块。
     */
    private static boolean survivableColumn(IPlayerContext ctx, BlockPos column, double apexHeight) {
        for (int landing = 1; landing >= -1; landing--) {
            if (apexHeight - landing > SAFE_FALL_DISTANCE) {
                continue;
            }
            BlockPos feetCell = column.above(landing);
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

    /** 从 feet 起连续 columns 格都有两格高实心顶盖(顶头短跳的走廊前提)。 */
    private static boolean hasContinuousCeiling(IPlayerContext ctx, BlockPos src,
                                                Vec3i direction, int columns) {
        if (!hasFullTwoBlockCeiling(ctx, src)) {
            return false;
        }
        for (int d = 1; d <= columns; d++) {
            BlockPos cell = src.offset(direction.getX() * d, 0, direction.getZ() * d);
            if (!hasFullTwoBlockCeiling(ctx, cell)) {
                return false;
            }
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
