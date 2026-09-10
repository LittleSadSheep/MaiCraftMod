package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.List;
import net.minecraft.world.phys.Vec3;

// 检查转头前先稳住高度、镜头沿路线看前方，以及拐弯时不能因看得太远而阻止当前路段的按键。
public final class JetpackViewTest {
    public static void main(String[] args) {
        var power = new JetpackNativeAdapter.Snapshot(true, "fixture", "create_jetpack:jetpack", true, true,
                900, 17000, .016, .32, .6, -.03, .08);
        Vec3 target = new Vec3(0, 4, 5);
        var turning = JetpackView.command(Vec3.ZERO, Vec3.ZERO, target, 180, false, power);
        check(turning.forward() == 0 && turning.strafe() == 0 && turning.jumping(),
                "face the course while holding altitude instead of accelerating backwards");
        var aligned = JetpackView.command(Vec3.ZERO, Vec3.ZERO, target, 0, false, power);
        check(aligned.forward() > 0 && aligned.jumping(), "a faced clear course should advance while climbing");
        var east = JetpackView.toward(Vec3.ZERO, 1.62, new Vec3(5, 0, 0), 0, false);
        check(east.yaw() == -90 && east.pitch() > 0 && east.pitch() < 30, "camera should face the nearby route with a natural pitch");
        var landing = JetpackView.toward(Vec3.ZERO, 1.62, new Vec3(0, -8, 0), 37, true);
        check(landing.yaw() == 37 && landing.pitch() == 75, "vertical landing must show the platform without yaw jitter");
        Vec3 ahead = JetpackView.lookAhead(List.of(Vec3.ZERO, new Vec3(0, 0, 3), new Vec3(3, 0, 3)), 1, Vec3.ZERO, 4);
        check(ahead.distanceTo(new Vec3(1, 0, 3)) < 1e-9, "lookahead follows a short course distance around the upcoming turn");
        var course = new JetpackRoute.Plan(List.of(Vec3.ZERO, new Vec3(0, 2, 0),
                new Vec3(10, 2, 0), new Vec3(10, 0, 0)), List.of(), 200);
        JetpackRoute.Space open = new JetpackRoute.Space() {
            public boolean clear(Vec3 from, Vec3 to) { return true; }
            public Vec3 landingBelow(Vec3 point) { return point; }
        };
        Vec3 offset = new Vec3(0, 2, -1);
        int index = JetpackRoute.nextWaypoint(open, course, offset, 1, power);
        check(index == 1, "residual takeoff drift must still approach the uncompleted lift corner");
        Vec3 aim = course.points().get(index);
        Vec3 glance = JetpackView.lookAhead(course.points(), index, offset, 6);
        Vec3 focus = JetpackView.focus(offset, aim, glance);
        var cornerLook = JetpackView.toward(offset, 1.62, focus, 0, false);
        var progress = JetpackView.command(offset, Vec3.ZERO, aim, cornerLook.yaw(), false, power);
        check(focus.equals(aim) && progress.forward() > 0,
                "looking past a sharp corner must not permanently close the current leg's steering gate");
        check(JetpackView.focus(Vec3.ZERO, new Vec3(0, 0, 3), new Vec3(1, 0, 4)).equals(new Vec3(1, 0, 4)),
                "a shallow upcoming turn should remain visible");
        check(JetpackView.focus(Vec3.ZERO, new Vec3(0, 0, 3), new Vec3(0, 0, .2)).equals(new Vec3(0, 0, 3)),
                "a near-vertical glance must not leave the camera's yaw pointing away from the current leg");
        System.out.println("JetpackViewTest: passed");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
