// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.Baritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.game.player.FallDamage;

/**
 * 赶路跑跳：疾跑时沿前方的直线走廊起跳加速，像真人赶路一样一路连跳；顶棚只有两格高的走廊按撞头的短弧
 * 预测，连跳节奏自然更密（低顶连跳）。上游的跑酷与疾跑上升仍各管各的特殊动作。
 *
 * <p>只在这些条件下跳：已决定疾跑、脚踩在完整干燥的方块上、没在挖没在放没潜行、没有漂浮缓降效果；
 * 整段飞行身体不擦到任何方块、落点踩得实且这一跳的高度摔不伤；走廊里有深洞、流体、立柱、耕地都不跳。
 */
public final class TravelJumpPolicy {
    private static final float NORMAL_GROUND_FRICTION = 0.6F;

    private TravelJumpPolicy() {}

    /**
     * 这一刻该不该按住跳。
     *
     * @param verifiedRunway 决定跳时交出核过的走廊，调用方据此让执行器在空中仍按起跳高度判断进度
     */
    public static boolean shouldTravelJump(Baritone baritone, List<IMovement> movements,
                                           int pathPosition, Consumer<List<IMovement>> verifiedRunway) {
        IPlayerContext ctx = baritone.getPlayerContext();
        var player = ctx.player();
        if (player == null || !player.onGround() || player.isInWater() || player.isPassenger()
                || player.isCrouching() || player.hasEffect(MobEffects.LEVITATION)
                || player.hasEffect(MobEffects.SLOW_FALLING)) return false;
        var input = baritone.getInputOverrideHandler();
        if (input.isInputForcedDown(Input.CLICK_LEFT) || input.isInputForcedDown(Input.CLICK_RIGHT)
                || input.isInputForcedDown(Input.SNEAK)) return false;
        TravelRunway runway = TravelRunway.capture(movements, pathPosition, player.position());
        if (runway == null || player.getDeltaMovement().dot(runway.heading()) <= 0
                || !isStableTakeoff(ctx, ctx.playerFeet())) return false;
        var policy = NavigationProtection.snapshot();
        if (player.getY() < policy.minimumFeetY()) return false;
        // 联动的移动结构（Create 的机器）还没接进障碍观察：按没有算，接上后从这里换成实时快照。
        Plan plan = plan(ctx.world(), ctx.world()::isLoaded, policy.noEntryCells(),
                PhysicalObstacleSnapshot.EMPTY, runway,
                TravelJumpPhysics.capture(player, runway.heading()), player.getBbWidth(), player.getDeltaMovement());
        if (plan == null || !FallDamage.capture(player).survives(plan.apexHeight(),
                FallDamage.Landing.ORDINARY, false)) return false;
        verifiedRunway.accept(plan.movements());
        return true;
    }

    /** 核过的一跳：盖住的走廊、最高点、是不是撞头的短弧。 */
    record Plan(List<IMovement> movements, double apexHeight, boolean headHit) {}

    /** 网格移动和经过平滑的任意方向移动共用相同的扫掠身体及地面几何。先试撞头的短弧，再试全弧。 */
    static Plan plan(BlockGetter world, Predicate<BlockPos> loaded, LongSet forbidden,
                     PhysicalObstacleSnapshot physical, TravelRunway runway,
                     TravelJumpPhysics.Launch launch, double width, Vec3 velocity) {
        if (runway == null || !Double.isFinite(velocity.lengthSqr() + width) || width <= 0) return null;
        double sideSpeed = Math.abs(-runway.heading().z * velocity.x + runway.heading().x * velocity.z);
        for (boolean headHit : new boolean[]{true, false}) {
            var flight = TravelJumpPhysics.project(launch, headHit);
            if (flight == null) continue;
            double reach = flight.forwardDistance() + width * .5 + .25;
            List<IMovement> verified = runway.covering(reach);
            if (verified.isEmpty()) continue;
            double drift = sideSpeed * flight.airDragSum();
            var corridor = new GroundCorridor(world, loaded, width + 2 * drift,
                    launch.bodyHeight() + flight.apexHeight(), forbidden, physical);
            Vec3 end = runway.point(reach);
            // 树冠或顶棚存在缺口时，必须预留完整飞行空间，不能假设头部会提前撞上障碍。
            if (headHit && !corridor.hasContinuousCeiling(runway.start(), end, 2)) continue;
            // 跳跃及其落地会把沿线的耕地踩回泥土，地面支撑里有耕地就放弃这次加速跳，退回普通行走。
            if (crossesFarmland(world, runway.start(), end)) continue;
            if (corridor.clear(runway.start(), end)) return new Plan(verified, flight.apexHeight(), headHit);
        }
        return null;
    }

    /** 起跳点到预计落点之间的地面支撑只要有一格耕地就视为穿过农田。 */
    private static boolean crossesFarmland(BlockGetter world, Vec3 start, Vec3 end) {
        Vec3 delta = end.subtract(start);
        double length = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        int steps = Math.max(1, (int) Math.ceil(length * 2));
        double supportY = Math.floor(start.y) - 1;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            BlockPos support = BlockPos.containing(start.x + delta.x * t, supportY, start.z + delta.z * t);
            if (world.getBlockState(support).is(Blocks.FARMLAND)) return true;
        }
        return false;
    }

    /** 完整干燥支撑面确保起跳摩擦和高度与预测飞行一致；身体两格也得是空的。 */
    private static boolean isStableTakeoff(IPlayerContext ctx, BlockPos feet) {
        BlockState support = ctx.world().getBlockState(feet.below());
        return support.getFluidState().isEmpty()
                && support.isCollisionShapeFullBlock(ctx.world(), feet.below())
                && support.getBlock().getFriction() <= NORMAL_GROUND_FRICTION
                && MovementHelper.fullyPassable(ctx, feet)
                && MovementHelper.fullyPassable(ctx, feet.above());
    }
}
