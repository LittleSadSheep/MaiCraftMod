package org.maiwithu.maicraft.core.pathing.transport;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/** 保留唯一的原终点，只为未知远方的交通接近生成宽松前进区域；本地落地不等于整趟旅行完成。 */
final class LoadedTravelLeg {
    private GoalCompiler.Compiled intermediate;
    private GoalCompiler.CompiledFingerprint destination;
    private int completed;

    GoalCompiler.Compiled resolve(GoalCompiler.Compiled requested, BlockPos from, Predicate<BlockPos> loaded, BooleanSupplier surface) {
        return resolve(requested, from, loaded, surface, false);
    }
    GoalCompiler.Compiled resolve(GoalCompiler.Compiled requested, BlockPos from, Predicate<BlockPos> loaded, BooleanSupplier surface, boolean travelling) {
        if (requested == null) { intermediate = null; destination = null; return null; }
        var fingerprint = requested.semanticFingerprint();
        // 连续飞行自己延伸真实通道，越过最初观察圈不代表任务目标改变；只有调用方改总目标才触发换意图。
        if (intermediate != null && fingerprint.equals(destination)
                && (travelling || ((ForwardTravelGoal) intermediate.goal()).origin.distSqr(from) < 4 * ForwardTravelGoal.RANGE * ForwardTravelGoal.RANGE)) return intermediate;
        intermediate = null; destination = fingerprint;
        NavGoal goal = requested.goal();
        double radius;
        if (goal instanceof NavGoal.Exact) radius = 0;
        else if (goal instanceof NavGoal.Column column) radius = column.radius;
        else if (goal instanceof NavGoal.Near near) radius = near.radius;
        else if (goal instanceof NavGoal.NearGround near) radius = near.radius;
        else return requested; // 不改写战斗避让和工作面等复杂目标。
        double distance = Math.hypot(goal.center().getX() - from.getX(), goal.center().getZ() - from.getZ());
        if (loaded.test(goal.center()) || distance <= Math.max(24, radius + 12)) return requested;
        intermediate = new GoalCompiler.Compiled(new ForwardTravelGoal(from, goal, surface.getAsBoolean(), 4), requested.sacred());
        return intermediate;
    }
    boolean arrived(BlockPos feet) { return intermediate != null && intermediate.goal().isAt(feet); }
    boolean active() { return intermediate != null; }
    int completed() { return completed; }
    void complete() { intermediate = null; completed++; }
    // 整片区域的粗采样没有安全落点时，细化地形采样；不把一个失败坐标当作必须抵达的子目标。
    boolean reject() {
        if (intermediate == null || ((ForwardTravelGoal) intermediate.goal()).sampleStep == 1) return false;
        var prior = (ForwardTravelGoal) intermediate.goal();
        intermediate = new GoalCompiler.Compiled(new ForwardTravelGoal(prior.origin, prior.destination, prior.surface, prior.sampleStep / 2), intermediate.sacred());
        return true;
    }
}
