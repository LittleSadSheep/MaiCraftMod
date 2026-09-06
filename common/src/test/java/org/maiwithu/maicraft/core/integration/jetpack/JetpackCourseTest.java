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
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
