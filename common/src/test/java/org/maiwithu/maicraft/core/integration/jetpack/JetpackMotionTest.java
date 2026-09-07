// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort.Movement;

public final class JetpackMotionTest {
    private static final JetpackNativeAdapter.Snapshot POWER = new JetpackNativeAdapter.Snapshot(
            true, "installed dynamics", "create_jetpack:jetpack", true, true,
            900, 17000, .016, .32, .6, -.03, .08);

    public static void main(String[] args) {
        var forward = JetpackMotion.step(Vec3.ZERO, Vec3.ZERO, new Movement(1, 0, false, false, false), 0, POWER);
        check(Math.abs(forward.position().z - .0388) < 1e-7, "native and vanilla forward impulses must both be represented");
        var diagonal = JetpackMotion.step(Vec3.ZERO, Vec3.ZERO, new Movement(1, 1, false, false, false), 0, POWER);
        check(Math.abs(diagonal.position().x - (.016 + .02 / Math.sqrt(2))) < 1e-7,
                "only vanilla diagonal input is normalized, not the native side impulse");
        var tiny = JetpackMotion.step(Vec3.ZERO, new Vec3(.002, 0, .002), Movement.STOPPED, 0, POWER);
        check(tiny.position().horizontalDistance() == 0, "vanilla's small horizontal velocity deadzone must be applied");

        var wall = new Volume(List.of(new AABB(1.43, 0, -10, 2.5, 10, 10)));
        Vec3 position = new Vec3(0, 3, 0), velocity = new Vec3(.35, -.1078, 0), aim = new Vec3(0, 3, 5);
        check(wall.clear(position, aim), "the intended geometric route should be clear");
        check(!JetpackMotion.clearTrajectory(wall, position, velocity, aim, 0, 0, POWER),
                "measured sideways drift must predict the wall before the body reaches it");
        int brakingTicks = 0;
        for (int tick = 0; tick < 80; tick++) {
            boolean brake = !JetpackMotion.clearTrajectory(wall, position, velocity, aim, 0, 0, POWER);
            if (brake) brakingTicks++;
            Vec3 target = brake ? new Vec3(position.x, aim.y, position.z) : aim;
            var command = JetpackView.command(position, velocity, target, 0, false, POWER);
            var next = JetpackMotion.step(position, velocity, command, 0, POWER);
            check(wall.clear(position, next.position()), "early full braking must avoid the wall without writing velocity");
            position = next.position(); velocity = next.velocity();
        }
        check(brakingTicks > 0 && position.z > 2, "local avoidance must brake and resume the route, not hover forever");

        var narrow = new Volume(List.of(new AABB(-2, 0, -10, 0, 10, 10), new AABB(1, 0, -10, 3, 10, 10)));
        check(JetpackMotion.clearTrajectory(narrow, new Vec3(.5, 3, 0), Vec3.ZERO, new Vec3(.5, 3, 5), 0, 0, POWER),
                "a centered one-block passage must remain usable by the full body");
        var lintel = new Volume(List.of(new AABB(-2, 4.9, -2, 2, 6, 8)));
        check(!JetpackMotion.clearTrajectory(lintel, new Vec3(0, 3, 0), new Vec3(0, -.1078, 0), aim, 0, 0, POWER),
                "altitude-maintenance pulses must include the body's height under a lintel");
        var open = new Volume(List.of());
        check(JetpackMotion.clearTrajectory(open, Vec3.ZERO, Vec3.ZERO, new Vec3(2, 2, 2), 0, -45, POWER),
                "unobstructed camera turns must allow ordinary flight");
        check(open.queries <= 10, "real-time motion lookahead must have a fixed ten-sweep budget");
        faceApertureBeforeLookingAroundCorner();
        movingRisePastSlab();
        System.out.println("JetpackMotionTest: passed");
    }

    private static void faceApertureBeforeLookingAroundCorner() {
        var doorway = new Volume(List.of(new AABB(-2, 0, -10, 0, 10, 0), new AABB(1, 0, -10, 3, 10, 0)));
        Vec3 position = new Vec3(.6, 3, 0), velocity = new Vec3(0, -.1078, 0), aim = new Vec3(.5, 3, 1);
        Vec3 glimpse = new Vec3(-1.495, 3, 4);
        float yaw = JetpackView.toward(position, 1.62, glimpse, 0, false).yaw();
        check(doorway.clear(position, aim) && !JetpackMotion.clearTrajectory(doorway, position, velocity, aim, yaw, yaw, POWER),
                "looking past a narrow aperture must reproduce a blocked prediction even from zero horizontal speed");
        boolean refocused = false;
        for (int tick = 0; tick < 80 && position.z < .7; tick++) {
            float requested = JetpackView.toward(position, 1.62, glimpse, yaw, false).yaw();
            boolean brake = !JetpackMotion.clearTrajectory(doorway, position, velocity, aim, yaw, requested, POWER);
            if (brake) {
                requested = JetpackView.toward(position, 1.62, aim, yaw, false).yaw();
                refocused = true;
                brake = !JetpackMotion.clearTrajectory(doorway, position, velocity, aim, yaw, requested, POWER);
            }
            yaw += net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(requested - yaw) * .25F, -12, 12);
            Vec3 target = brake ? new Vec3(position.x, aim.y, position.z) : aim;
            var next = JetpackMotion.step(position, velocity, JetpackView.command(position, velocity, target, yaw, false, POWER), yaw, POWER);
            check(doorway.clear(position, next.position()), "turning to the current aperture must avoid its edge");
            position = next.position(); velocity = next.velocity();
        }
        check(refocused && position.z >= .7, "refocusing and rechecking must exit the aperture instead of hovering permanently");
    }

    private static void movingRisePastSlab() {
        var slab = new Volume(List.of(new AABB(-86, 114, -6, -85, 115, -5)));
        Vec3 position = new Vec3(-86.26641760057763, 111.38928709996215, -5.4772518971564885);
        Vec3 velocity = new Vec3(-.17, .23520000457763676, 0), target = new Vec3(-87.5, 112, -5.5);
        double rise = JetpackDynamics.riseEnvelope(velocity.y, true, POWER);
        check(!slab.clear(position, position.add(0, rise, 0)), "live fixture must reproduce the static slab-envelope veto");
        check(JetpackMotion.canRise(slab, position, velocity, target, 90, 90, false, POWER),
                "westward flight leaves the slab before the UP peak and must not be treated as an in-place jump");
        check(!JetpackMotion.canRise(slab, position, velocity, target, 90, 90, true, POWER),
                "a grounded native jump must retain its separate clearance gate");
        Vec3 stopped = new Vec3(0, velocity.y, 0), overhead = new Vec3(position.x, target.y, position.z);
        check(!JetpackMotion.canRise(slab, position, stopped, overhead, 90, 90, false, POWER),
                "an in-place rise under the same slab must still be rejected");
        check(!JetpackMotion.canRise(slab, position, new Vec3(.17, velocity.y, 0), new Vec3(-85, 112, -5.5),
                        -90, -90, false, POWER), "motion farther under the slab must not bypass the body sweep");
        Vec3 p = position, v = velocity;
        for (int tick = 0; tick < 5; tick++) {
            var next = JetpackMotion.step(p, v, JetpackView.command(p, v, target, 90, false, POWER), 90, POWER);
            check(slab.clear(p, next.position()), "the accepted moving UP pulse must keep the full body clear through its peak");
            p = next.position(); v = next.velocity();
        }
        var open = new Volume(List.of());
        check(JetpackMotion.canRise(open, position, velocity, target, 90, 90, false, POWER) && open.queries == 1,
                "unobstructed rise must not pay for a second trajectory forecast");
        var stronger = new JetpackNativeAdapter.Snapshot(true, "configured thrust", POWER.item(), true, true,
                900, 17000, .016, .8, .8, -.03, .08);
        var ceiling = new Volume(List.of(new AABB(-2, 6, -2, 2, 8, 2)));
        check(!JetpackMotion.canRise(ceiling, new Vec3(0, 1, 0), Vec3.ZERO, new Vec3(0, 1.5, 0), 0, 0, false, stronger),
                "a stronger UP pulse must inspect its coasting peak beyond the default five-tick horizon");
    }

    private static final class Volume implements JetpackRoute.Space {
        private final List<AABB> shapes;
        private int queries;
        private Volume(List<AABB> shapes) { this.shapes = shapes; }
        public boolean clear(Vec3 from, Vec3 to) {
            queries++;
            int samples = Math.max(1, (int)Math.ceil(from.distanceTo(to) / .05));
            for (int i = 0; i <= samples; i++) {
                Vec3 p = from.lerp(to, (double)i / samples);
                var body = new AABB(p.x - .38, p.y + .001, p.z - .38, p.x + .38, p.y + 1.88, p.z + .38);
                if (shapes.stream().anyMatch(body::intersects)) return false;
            }
            return true;
        }
        public Vec3 landingBelow(Vec3 point) { return null; }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
