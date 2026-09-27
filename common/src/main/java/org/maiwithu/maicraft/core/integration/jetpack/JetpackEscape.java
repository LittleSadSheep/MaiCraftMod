// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import net.minecraft.world.phys.Vec3;

/**
 * 需要提前结束飞行时，比较正下方降落、继续原路和沿原路退回三种已知出口，选择估计耗时最少且当前通畅的一条。
 */
final class JetpackEscape {
    static JetpackRoute.Plan choose(JetpackRoute.Space space, Vec3 position, JetpackRoute.Plan route,
                                   int next, JetpackNativeAdapter.Snapshot power) {
        return choose(space,position,route,next,power,point -> 0);
    }
    // 正常取消仍比较耗时；受伤撤离由调用方提供已观察威胁的风险，所有候选都先核实落脚面和完整通道。
    static JetpackRoute.Plan choose(JetpackRoute.Space space, Vec3 position, JetpackRoute.Plan route,
            int next, JetpackNativeAdapter.Snapshot power, ToDoubleFunction<Vec3> risk) {
        List<List<Vec3>> candidates = new ArrayList<>();
        Vec3 below = space.landingBelow(position.add(0, 0.1, 0));
        if (below != null) candidates.add(List.of(position, below));
        // 连续飞行的参考点可能在空中；备用落脚面必须单独参与退出比较，并重新核对通道与支撑。
        for (Vec3 exit : route.emergencyLandings()) candidates.add(List.of(position, exit));
        var forward = new ArrayList<Vec3>(); forward.add(position);
        forward.addAll(route.points().subList(Math.min(next, route.points().size()-1), route.points().size()));
        candidates.add(forward);
        var backward = new ArrayList<Vec3>(); backward.add(position);
        for (int i = Math.min(next-1, route.points().size()-1); i >= 0; i--) backward.add(route.points().get(i));
        candidates.add(backward);
        JetpackRoute.Plan best = null;
        for (List<Vec3> points : candidates) {
            if (points.size() < 2) continue;
            Vec3 end = points.getLast(), floor = space.landingBelow(end.add(0, 0.1, 0));
            if (floor == null || Math.abs(floor.y - end.y) > 0.1) continue;
            double ticks = 60; boolean clear = true;
            for (int i = 1; i < points.size(); i++) {
                if (!space.clear(points.get(i-1), points.get(i))) { clear = false; break; }
                ticks += JetpackRoute.edgeTicks(points.get(i-1), points.get(i), power);
            }
            if (clear && Double.isFinite(ticks)) best = better(
                    new JetpackRoute.Plan(points,List.of(floor),(int)Math.ceil(ticks)),best,power,risk);
        }
        return best;
    }
    static JetpackRoute.Plan better(JetpackRoute.Plan candidate, JetpackRoute.Plan current,
            JetpackNativeAdapter.Snapshot power, ToDoubleFunction<Vec3> risk) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        boolean fuel = candidate.requiredTicks() <= power.fuelTicks(), oldFuel = current.requiredTicks() <= power.fuelTicks();
        if (fuel != oldFuel) return fuel ? candidate : current;
        // 燃料允许时先避开攻击者，再比较耗时；都已缺燃料时继续保留最短的已知落地机会。
        if (fuel) {
            int danger = Double.compare(risk.applyAsDouble(candidate.points().getLast()),risk.applyAsDouble(current.points().getLast()));
            if (danger != 0) return danger < 0 ? candidate : current;
        }
        return candidate.requiredTicks() < current.requiredTicks() ? candidate : current;
    }
}
