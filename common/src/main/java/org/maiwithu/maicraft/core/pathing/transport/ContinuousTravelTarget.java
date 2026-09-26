package org.maiwithu.maicraft.core.pathing.transport;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackNativeAdapter;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackRoute;
import org.maiwithu.maicraft.core.integration.jetpack.MovingFlightTarget;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import baritone.pathing.calc.LoadedFrontier;

/** 保留最终目标，在仍有安全通道时提前延伸飞行方向；中间地面只作为续航不足或前方无路时的备用出口。 */
final class ContinuousTravelTarget implements MovingFlightTarget {
    private final NavGoal destination;
    private final LongSet forbidden;
    private Vec3 point, fallback, position, scannedAt;
    private TransportTargets survey;
    private LoadedFrontier frontier;
    private boolean landing = true, finalApproach, surveyFinal, grounded;
    private int candidate, stableTicks, extensions;
    private List<TransportTargets.Destination> choices = List.of();

    ContinuousTravelTarget(NavGoal destination, Vec3 initialLanding, LongSet forbidden) {
        this.destination = destination; this.forbidden = forbidden;
        point = fallback = position = initialLanding;
    }
    @Override public boolean update(LocalPlayerContext context) {
        position = context.player().position(); grounded = context.player().onGround();
        var space = space(context, forbidden); var power = JetpackNativeAdapter.inspect(context);
        if (!finalApproach) {
            // 继续沿旧走廊飞行，同时分刻检查前方新地形；同一批未知区块不反复触发全盘搜索。
            if (survey == null && (scannedAt == null || scannedAt.distanceToSqr(position) >= 16
                    || frontier.hasNewTerrain(context.level()::isLoaded))) {
                scannedAt = position;
                frontier = LoadedFrontier.capture(BlockPos.containing(position), context.level()::isLoaded);
                surveyFinal = context.level().isLoaded(destination.center())
                        && Math.hypot(destination.center().getX() - position.x, destination.center().getZ() - position.z) < 48;
                NavGoal region = surveyFinal ? destination : new ForwardTravelGoal(BlockPos.containing(position), destination,
                        context.level().dimensionType().hasSkyLight(), 4);
                survey = new TransportTargets(new GoalCompiler.Compiled(region, forbidden), context, forbidden);
                choices = List.of(); candidate = 0;
            }
            if (survey != null && survey.tick(context)) {
                if (choices.isEmpty()) choices = survey.destinations();
                // 扫掠整条飞行通道也分刻处理；规划期间保持旧路线，不能为了新参考点停在半空。
                long until = System.nanoTime() + 1_000_000L;
                for (int checked = 0; candidate < choices.size() && checked < 2 && (checked == 0 || System.nanoTime() < until); checked++) {
                    Vec3 next = choices.get(candidate++).landingPoint();
                    if (extend(position, next, surveyFinal, space, power)) { survey = null; break; }
                }
                if (survey != null && candidate >= choices.size()) survey = null;
            }
            // 前方仍未找到安全延伸时，才转向最近一次验证的备用地面；正常加载会在到这里前延长走廊。
            if (!landing && position.distanceToSqr(point) < 64 && survey == null) { point = fallback; landing = true; }
        }
        if (landing && !supported(space, fallback)) return false;
        stableTicks = contact() && Math.abs(context.player().getDeltaMovement().y) < .1 ? stableTicks + 1 : 0;
        return true;
    }
    boolean extend(Vec3 current, Vec3 next, boolean finalCandidate, JetpackRoute.Space space, JetpackNativeAdapter.Snapshot power) {
        if (current.distanceTo(next) > 56 || !supported(space, next)) return false;
        if (finalCandidate) {
            // 最后才恢复精确位置／高度约束；不能把前向区域内的任意落脚面当作已经回到平台。
            if (!destination.isAt(BlockPos.containing(next))) return false;
        } else if (destination.progressHeuristic(BlockPos.containing(next))
                >= destination.progressHeuristic(BlockPos.containing(fallback)) - 4) return false;
        Vec3 air = new Vec3(next.x, Math.max(current.y, next.y + 2), next.z);
        if (!JetpackRoute.flightClear(space, current, air, power) || !space.clear(air, next)) return false;
        double reserve = 140 + JetpackRoute.edgeTicks(current, air, power) + JetpackRoute.edgeTicks(air, next, power);
        if (power.fuelTicks() < reserve) return false;
        fallback = next; point = finalCandidate ? next : air; landing = finalCandidate; finalApproach = finalCandidate;
        extensions++; return true;
    }
    private static boolean supported(JetpackRoute.Space space, Vec3 point) {
        Vec3 observed = space.landingBelow(point.add(0, .1, 0));
        return observed != null && observed.distanceToSqr(point) < .01;
    }
    @Override public Vec3 point() { return point; }
    @Override public Vec3 velocity() { return Vec3.ZERO; }
    @Override public boolean landingSelected() { return landing; }
    @Override public Vec3 emergencyLanding() { return fallback; }
    @Override public boolean contact() { return landing && grounded && position.distanceToSqr(fallback) < .72; }
    @Override public boolean touchdown() { return stableTicks >= 3; }
    @Override public JetpackRoute.Space space(LocalPlayerContext context, LongSet forbidden) { return JetpackRoute.observed(context, forbidden); }
    @Override public Map<String, Object> diagnostics() {
        return Map.of("kind", "continuous_directional_travel", "extensions", extensions, "final_approach", finalApproach,
                "landing_selected", landing, "survey_pending", survey != null, "destination", destination.center().toShortString());
    }
}
