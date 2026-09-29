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
        // 受伤处正下方和原航线都可能贴着追兵；补查有限的侧向出口，每条仍走下方同一套通道、支撑和燃料核验。
        if (risk.applyAsDouble(position) > 0) nearbyExits(candidates, space, position);
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
    private static void nearbyExits(List<List<Vec3>> candidates, JetpackRoute.Space space, Vec3 position) {
        // 只读已加载地形：八个方向、两档距离、原高度或抬高四格，共最多三十二条；不挖路、不假定偏移点可落脚。
        for (int radius : new int[]{8, 16}) for (int direction = 0; direction < 8; direction++) {
            double angle = direction * Math.PI / 4;
            for (int rise : new int[]{0, 4}) {
                Vec3 lift = position.add(0, rise, 0);
                Vec3 above = lift.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
                Vec3 landing = space.landingBelow(above);
                if (landing == null) continue;
                var points = new ArrayList<Vec3>(); points.add(position);
                if (rise > 0) points.add(lift);
                points.add(above);
                if (above.distanceToSqr(landing) > 1e-8) points.add(landing);
                candidates.add(points);
            }
        }
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
