package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.transport.TransportTargets.Destination;

/** Offline controller run against the native upright vertical update and a fixed raised platform. */
public final class JetpackCourseTest {
    private static final JetpackNativeAdapter.Snapshot POWER = new JetpackNativeAdapter.Snapshot(
            true, "fixture", "create_jetpack:netherite_jetpack", true, true, 900, 17000, 0.016, 0.32, 0.6, -0.03, 0.08);

    public static void main(String[] args) {
        descendingCorner();
        var cells = new ArrayList<Destination>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) cells.add(new Destination(new BlockPos(x, 8, z), new Vec3(x + .5, 8, z + .5)));
        var platforms = JetpackPlatform.collect(cells);
        check(platforms.size() == 1 && platforms.getFirst().landings().size() == 9, "one platform must not become nine repeated flight attempts");
        check(platforms.getFirst().anchor().feet().equals(new BlockPos(1, 8, 1)), "landing anchor should be inside the platform");
        cells.add(new Destination(new BlockPos(1, 12, 1), new Vec3(1.5, 12, 1.5)));
        check(JetpackPlatform.collect(cells).size() == 2, "separate floors are distinct landing platforms");
        var missingSupport = new JetpackRoute.Space() {
            public boolean clear(Vec3 a, Vec3 b) { return true; }
            public Vec3 landingBelow(Vec3 p) { return new Vec3(p.x, 0, p.z); }
        };
        check(!JetpackRoute.supportsLanding(missingSupport, new Vec3(1.5, 8, 1.5)),
                "clear air above a lower floor must not validate a platform that disappeared");

        JetpackRoute.Space room = new JetpackRoute.Space() {
            public boolean clear(Vec3 from, Vec3 to) {
                int n = Math.max(1, (int) Math.ceil(from.distanceTo(to) * 10));
                for (int i = 0; i <= n; i++) {
                    Vec3 p = from.lerp(to, (double) i / n);
                    if (p.y < 0 || p.y > 12 || p.x >= 4 && p.y < 8) return false;
                }
                return true;
            }
            public Vec3 landingBelow(Vec3 p) {
                double y = p.x >= 4 ? 8 : 0;
                return p.y >= y ? new Vec3(p.x, y, p.z) : null;
            }
        };
        Vec3 position = new Vec3(.5, 0, .5), target = new Vec3(6.5, 8, .5), velocity = Vec3.ZERO;
        var search = new JetpackRoute.Search(position, target, POWER);
        for (int i = 0; !search.done() && i < 100; i++) search.advance(room, 128, Long.MAX_VALUE);
        var route = search.result();
        check(route != null && route.points().size() == 4, "clear room should use climb/cruise/descent rather than per-block stops");
        check(route.points().get(1).y == 10 && route.points().get(2).y == 10, "horizontal transfer needs a fixed platform clearance band");
        int waypoint = 1; boolean landing = false, arrived = false;
        for (int tick = 0; tick < 1800; tick++) {
            Vec3 aim;
            if (!landing) {
                waypoint = JetpackRoute.nextWaypoint(room, route, position, waypoint);
                aim = route.points().get(waypoint);
                check(room.clear(position, aim), "selected new segment must be checked before moving");
                if (waypoint == 2 && position.y >= aim.y - .1 && Math.hypot(position.x - aim.x, position.z - aim.z) < .4) landing = true;
            } else aim = target;
            boolean centered = landing && Math.hypot(position.x - target.x, position.z - target.z) < .18 && velocity.horizontalDistance() < .08;
            if (landing) aim = JetpackFlightSession.landingAim(position, target, 10, centered);
            var input = JetpackSteering.toward(position, velocity, aim, 0, centered, POWER);
            check(!input.sneaking(), "controller must not select native fast descent");
            double dy = tick == 0 ? .42 : JetpackDynamics.nextVertical(velocity.y, input.jumping(), POWER);
            // Native hover horizontal impulse plus vanilla non-sprinting air movement; yaw is fixed.
            double dx = velocity.x + input.strafe() * (POWER.horizontal() + .02);
            double dz = velocity.z + input.forward() * (POWER.horizontal() * (input.forward() < 0 ? .8 : 1.2) + .02);
            Vec3 next = position.add(dx, dy, dz);
            if (next.y <= 8 && centered) { next = new Vec3(next.x, 8, next.z); arrived = true; }
            check(next.x < 4 || next.y >= 8, "controller crossed the platform face before gaining height");
            if (!arrived) check(room.clear(position, next), "native dynamics left the checked flight corridor");
            position = next; velocity = new Vec3(dx * .91, JetpackDynamics.rawAfterStep(dy, POWER), dz * .91);
            if (arrived) break;
        }
        check(arrived && position.distanceTo(target) < .3, "flight must align above the platform and settle, rather than drift back below it");
        System.out.println("JetpackCourseTest: passed");
    }
    private static void descendingCorner() {
        JetpackRoute.Space open = new JetpackRoute.Space() {
            public boolean clear(Vec3 a, Vec3 b) { return true; }
            public Vec3 landingBelow(Vec3 p) { return new Vec3(p.x, 0, p.z); }
        };
        var route = new JetpackRoute.Plan(List.of(new Vec3(0, 12, 0), new Vec3(0, 11, 0),
                new Vec3(0, 10, 0), new Vec3(1, 10, 0), new Vec3(1, 10, 1), new Vec3(1, 8, 1)), List.of(), 200);
        check(JetpackRoute.nextWaypoint(open, route, new Vec3(0, 11.05, 0), 1) == 2,
                "descending a column must finish the lower height before a horizontal turn");
        check(JetpackRoute.nextWaypoint(open, route, new Vec3(.5, 11.05, 0), 2) == 2,
                "level lookahead must not bypass an unfinished descent when outside the arrival radius");
        check(JetpackRoute.nextWaypoint(open, route, new Vec3(0, 10.05, 0), 2) >= 3,
                "a completed descent must allow the next horizontal course");
        var descendingApproach = new JetpackRoute.Plan(List.of(new Vec3(0, 12, 0), new Vec3(0, 10, 0), new Vec3(0, 8, 0)), List.of(), 100);
        check(!JetpackRoute.atWaypointHeight(descendingApproach, 1, 12),
                "the landing phase must not bypass a descent to the planned approach height");
        var ascending = new JetpackRoute.Plan(List.of(new Vec3(0, 10, 0), new Vec3(0, 12, 0),
                new Vec3(1, 12, 0), new Vec3(1, 10, 0)), List.of(), 100);
        check(JetpackRoute.nextWaypoint(open, ascending, new Vec3(0, 12.8, 0), 1) == 2,
                "native upward coasting above the cruise minimum remains an arrived ascent");
        // Actual failed sweep from the cross-floor trial: turning early intersected the Y114 floor.
        var floor = new net.minecraft.world.phys.AABB(-78, 114, -13, -77, 115, -12);
        JetpackRoute.Space scene = new JetpackRoute.Space() {
            public boolean clear(Vec3 a, Vec3 b) {
                int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) / .2));
                for (int i = 0; i <= steps; i++) {
                    Vec3 p = a.lerp(b, (double) i / steps);
                    if (new net.minecraft.world.phys.AABB(p.x - .38, p.y + .001, p.z - .38,
                            p.x + .38, p.y + 1.88, p.z + .38).intersects(floor)) return false;
                }
                return true;
            }
            public Vec3 landingBelow(Vec3 p) { return null; }
        };
        Vec3 actual = new Vec3(-77.68648930205119, 117.7985766625052, -13.499507934036888);
        Vec3 column = new Vec3(-77.5, 110, -13.5), turn = new Vec3(-77.5, 110, -12.5);
        var observed = new JetpackRoute.Plan(List.of(new Vec3(-77.5, 118, -13.5), column, turn,
                new Vec3(-77.5, 105, -12.5)), List.of(), 300);
        check(scene.clear(actual, column) && !scene.clear(actual, turn), "fixture must reproduce the observed premature floor crossing");
        check(JetpackRoute.nextWaypoint(scene, observed, actual, 1) == 1,
                "stay in the clear descent column until below the floor before turning underneath it");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
