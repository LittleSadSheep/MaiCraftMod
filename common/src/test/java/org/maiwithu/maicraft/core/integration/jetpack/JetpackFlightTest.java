// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import net.minecraft.world.phys.Vec3;

/**
 * 检查气量预留、分次飞越障碍、水域上方的退出选择、刹车按键和平台对准；使用简化空间与固定设备参数。
 */
public final class JetpackFlightTest {
    private static final JetpackNativeAdapter.Snapshot POWER = new JetpackNativeAdapter.Snapshot(
            true, "fixture", "create_jetpack:netherite_jetpack", true, true, 900, 17000,
            0.016, 0.32, 0.6, -0.03, 0.08);
    public static void main(String[] args) {
        check(JetpackNativeAdapter.usableTicks(2, 900, 900) == 0, "last charges are a landing/billing reserve");
        check(JetpackNativeAdapter.usableTicks(22, 900, 120) == 20, "float usability and integer consumption differ");
        check(JetpackNativeAdapter.usableTicks(900, 900, 0) == 0, "unknown fuel configuration cannot imply infinity");
        check(!new JetpackNativeAdapter.Snapshot(true, "empty", "", true, true, 0, 0,
                0.016, 0.32, 0.6, -0.03, 0.08).controllable(), "empty tank must refuse takeoff");
        var start = new Vec3(0.5, 0, 0.5); var target = new Vec3(4.5, 0, 0.5);
        var floor = new TestSpace(false, false);
        var obstacle = new TestSpace(true, false);
        var search = new JetpackRoute.Search(start, target, POWER);
        search.advance(obstacle, 2, Long.MAX_VALUE);
        check(!search.done() && search.expanded() <= 2, "search must yield at its per-tick node budget");
        for (int i = 0; !search.done() && i < 1000; i++) search.advance(obstacle, 8, Long.MAX_VALUE);
        var route = search.result();
        check(search.done() && route != null, "flight must climb over an obstructing wall");
        check(route.points().stream().anyMatch(p -> p.y >= 4), "3-D search did not take the upper corridor");
        check(route.points().getLast().equals(target), "route must terminate on observed landing height");
        check(route.requiredTicks() > 140 && route.requiredTicks() < POWER.fuelTicks(), "flight cost includes braking and landing");
        for (int i = 1; i < route.points().size(); i++) check(obstacle.clear(route.points().get(i-1), route.points().get(i)), "body sweep crossed wall");
        var water = new TestSpace(false, true);
        var crossing = complete(water, start, target);
        check(crossing != null, "sufficient fuel must permit flying above water with dry departure/arrival");
        Vec3 overWater = new Vec3(3.5, 1, 0.5);
        int next = 1;
        while (next < crossing.points().size()-1 && crossing.points().get(next).x <= overWater.x) next++;
        var escape = JetpackEscape.choose(water, overWater, crossing, next, POWER);
        check(escape != null && escape.points().getLast().equals(target), "cancel above water should choose nearer forward landing, not require a round trip");
        var blocked = new TestSpace(true, false) { public boolean clear(Vec3 from, Vec3 to) { return false; } };
        check(complete(blocked, start, target) == null, "blocked takeoff must fail without input effects");
        var changed = new JetpackRoute.Search(start, target, POWER);
        changed.advance(water, 1, Long.MAX_VALUE);
        for (int i = 0; !changed.done() && i < 1000; i++) changed.advance(blocked, 8, Long.MAX_VALUE);
        check(changed.done() && changed.result() == null, "later ticks must re-read changed geometry");
        var right = JetpackSteering.toward(Vec3.ZERO, Vec3.ZERO, new Vec3(2,1,0), 0, false, POWER);
        check(right.strafe() > 0 && right.jumping() && !right.sneaking(), "native sideways and UP keys");
        var brake = JetpackSteering.toward(Vec3.ZERO, new Vec3(0,0,0.4), new Vec3(0,0,0.1), 0, false, POWER);
        check(brake.forward() < 0, "near target must counter measured momentum");
        var descend = JetpackSteering.toward(new Vec3(0,5,0), Vec3.ZERO, Vec3.ZERO, 0, true, POWER);
        check(!descend.sneaking() && !descend.jumping(), "hover descent releases UP without dangerous airborne Shift");
        var overshoot = JetpackSteering.toward(new Vec3(0, 5, 0), new Vec3(0, -0.02, 0), new Vec3(0, 4, 0), 0, false, POWER);
        check(!overshoot.sneaking() && !overshoot.jumping(), "waypoint height correction must not select fast descent with Shift");
        var hold = JetpackSteering.toward(new Vec3(0, 5, 0), new Vec3(0, -0.1, 0), new Vec3(2, 5, 0), 0, false, POWER);
        check(hold.jumping(), "horizontal approach must anticipate native hover sinking before losing platform clearance");
        Vec3 floorTarget = new Vec3(2, 115, 0);
        Vec3 alignment = JetpackFlightSession.landingAim(new Vec3(1, 116.5, 0), floorTarget, 117, false);
        check(alignment.y == 117, "alignment height must not follow the sinking body down to the platform edge");
        check(alignment.x == 1, "platform entry must regain staging height before horizontal alignment");
        check(JetpackFlightSession.landingAim(new Vec3(1, 117.6, 0), floorTarget, 117, false).y == 117,
                "coasting above the height band must not ratchet the holding target upward");
        check(JetpackFlightSession.landingAim(alignment, floorTarget, 117, true).equals(floorTarget),
                "descent starts after centering over the observed support");
        check(JetpackRoute.approachCell(floor, start, POWER).getY() == 2, "open arrival column must reserve height above the platform");
        var lowCeiling = new TestSpace(false, false) {
            public boolean clear(Vec3 from, Vec3 to) { return from.y <= 1.9 && to.y <= 1.9 && super.clear(from, to); }
        };
        check(JetpackRoute.approachCell(lowCeiling, start, POWER).getY() == 1, "lower ceiling must choose the verified lower approach");
        check(JetpackRoute.descentSpeed(POWER) == 0.03, "fuel budgeting must use slow hover descent after removing Shift");
        System.out.println("JetpackFlightTest: passed");
    }
    private static JetpackRoute.Plan complete(JetpackRoute.Space space, Vec3 start, Vec3 target) {
        var search = new JetpackRoute.Search(start, target, POWER);
        for (int i = 0; !search.done() && i < 1000; i++) search.advance(space, 16, Long.MAX_VALUE);
        check(search.done(), "search did not terminate within its node cap");
        return search.result();
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static class TestSpace implements JetpackRoute.Space {
        private final boolean wall, water;
        TestSpace(boolean wall, boolean water) { this.wall = wall; this.water = water; }
        public boolean clear(Vec3 from, Vec3 to) {
            int samples = Math.max(1, (int) Math.ceil(from.distanceTo(to)*10));
            for (int i=0; i<=samples; i++) {
                Vec3 p = from.lerp(to, (double)i/samples);
                if (p.y < 0 || p.y > 7 || p.x < 0 || p.x > 5 || p.z < 0 || p.z > 1) return false;
                if (wall && p.x > 1.8 && p.x < 3.2 && p.y < 4) return false;
            }
            return true;
        }
        public Vec3 landingBelow(Vec3 p) {
            if (water && p.x > 1 && p.x < 4) return null;
            double height = wall && p.x > 1.8 && p.x < 3.2 ? 4 : 0;
            return p.y >= height ? new Vec3(p.x, height, p.z) : null;
        }
    }
}
