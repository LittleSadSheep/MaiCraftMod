// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt.Status;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFastDescent.Command;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFastDescent.Observation;

/** Exercises the production switch sequence and delayed native physics without controlling a player. */
public final class JetpackFastDescentTest {
    private static final Vec3 LANDING = new Vec3(0.5, 103, 0.5);
    private static final JetpackNativeAdapter.Snapshot POWER = new JetpackNativeAdapter.Snapshot(
            true, "installed native parameters", "create_jetpack:netherite_jetpack", true, true,
            900, 17000, 0.016, 0.32, 0.6, -0.03, 0.08);

    public static void main(String[] args) {
        normalSequence();
        harmlessShortDrop();
        cancellationRestartsImmediately();
        changedColumnRestoresHover();
        failedReceiptDoesNotReleaseDisabledPack();
        physicsWindow();
        eligibility();
        platformGeometry();
        intermediateHeight();
        System.out.println("JetpackFastDescentTest: passed");
    }

    private static void harmlessShortDrop() {
        var descent=new JetpackFastDescent();
        var start=at(0,2,-.03,true,true,true,Command.NONE,null);
        var harmless=new Observation(start.tick(),start.position(),start.velocity(),start.landing(),start.power(),
                true,true,false,0,Command.NONE,null,true);
        check(descent.advance(harmless,true)==Command.OFF,"a harmless two-block deck approach should not wait in slow hover");
        var off=at(2,1.7,-.25,false,false,true,Command.OFF,Status.CONFIRMED_APPLIED);
        descent.advance(new Observation(off.tick(),off.position(),off.velocity(),off.landing(),off.power(),
                true,false,false,0,Command.OFF,Status.CONFIRMED_APPLIED,true),false);
        check(descent.phase().equals("short_fall"),"short descent retains gravity until its supported landing");
        descent.advance(new Observation(9,LANDING,Vec3.ZERO,LANDING,off.power(),true,false,true,0,
                Command.OFF,Status.CONFIRMED_APPLIED,true),false);
        check(descent.finished(),"short touchdown returns directly to the flight owner's mode restoration");
    }

    private static void normalSequence() {
        var descent = new JetpackFastDescent();
        check(descent.advance(at(0, 12, -0.1, true, true, true, Command.NONE, null), true) == Command.OFF,
                "aligned tall descent did not disable the native pack");
        check(descent.advance(at(1, 11.9, -0.18, false, false, true, Command.OFF, Status.PENDING), false) == Command.NONE,
                "pending disable was submitted twice");
        descent.advance(at(2, 11.7, -0.25, false, false, true, Command.OFF, Status.CONFIRMED_APPLIED), false);
        check(descent.phase().equals("falling"), "disable receipt did not enter free descent");
        check(descent.advance(at(10, 2, -1, false, false, true, Command.OFF, Status.CONFIRMED_APPLIED), false) == Command.ON,
                "restart was not emitted on the threshold tick");
        descent.advance(at(11, 1.8, -0.1, true, true, true, Command.ON, Status.PENDING), false);
        check(descent.phase().equals("enabling"), "client prediction permitted shift before mode receipt");
        descent.advance(at(12, 1.77, -0.1078, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false);
        check(descent.phase().equals("braking"), "confirmed upright restart did not permit native shift descent");
        descent.advance(at(16, 0, 0, true, false, true, Command.ON, Status.CONFIRMED_APPLIED), false);
        check(descent.finished(), "grounded touchdown did not complete");
    }

    private static void cancellationRestartsImmediately() {
        var descent = started();
        descent.requestStop();
        check(descent.advance(at(1, 11.9, -0.18, false, false, true, Command.OFF, Status.PENDING), false) == Command.ON,
                "cancellation waited for old OFF receipt instead of reversing it");
        descent.advance(at(2, 11.7, -0.1, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false);
        check(descent.finished(), "cancellation did not hand back an enabled upright hover");
    }

    private static void changedColumnRestoresHover() {
        var descent = started();
        check(descent.advance(at(1, 11.9, -0.18, false, false, false, Command.OFF, Status.CONFIRMED_APPLIED), false) == Command.ON,
                "changed support continued free descent");
        descent.advance(at(2, 11.7, -0.1, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false);
        check(descent.finished(), "restored geometry incorrectly resumed the abandoned free drop");
        var shifted = started();
        var original = at(1, 11.9, -0.18, false, false, true, Command.OFF, Status.CONFIRMED_APPLIED);
        var changed = new Observation(1, original.position(), original.velocity(), LANDING.add(0, -1, 0),
                original.power(), true, false, false, 0, original.receiptCommand(), original.receiptStatus());
        check(shifted.advance(changed, false) == Command.ON, "a different platform height was silently adopted mid-drop");
    }

    private static void failedReceiptDoesNotReleaseDisabledPack() {
        var descent = started();
        descent.requestStop();
        for (int tick = 1; tick <= 120; tick += 30) {
            check(descent.advance(at(tick, 4, -0.6, false, false, true, Command.ON, Status.UNCERTAIN), false) == Command.ON,
                    "failed recovery did not retry the real native switch");
            check(descent.active() && !descent.finished(), "timeout released a disabled airborne pack");
        }
        descent.advance(at(125, 3, -0.1, true, false, true, Command.ON, Status.CONFIRMED_APPLIED), false);
        check(!descent.finished(), "active flag alone was mistaken for native upright flight");
    }

    private static void physicsWindow() {
        double slow = JetpackFastDescent.restartHeight(-0.1, 0.08, 0);
        check(JetpackFastDescent.restartHeight(-1, 0.08, 0) > slow, "fast downward momentum did not restart earlier");
        check(JetpackFastDescent.restartHeight(-1, 0.08, 200) > JetpackFastDescent.restartHeight(-1, 0.08, 0),
                "connection delay did not enlarge the restart window");
        for (int ping : new int[]{0, 50, 200, 500}) {
            double raw = -0.1, height = 32;
            while (height > JetpackFastDescent.restartHeight(raw, 0.08, ping)) {
                height += raw; raw = (raw - 0.08) * 0.98;
            }
            // Four margin ticks reserve enough room even when detection crosses the threshold by one physics step.
            for (int delay = 0; delay < 2 + (int)Math.ceil(ping / 50D); delay++) {
                height += raw; raw = (raw - 0.08) * 0.98;
            }
            check(height > 0, "simulated restart latency reached the platform before enabling: ping=" + ping);
            check(JetpackDynamics.nextVertical(raw, false, POWER) >= POWER.hoverDescent(),
                    "released UP failed to model native hover braking after restart");
        }
    }

    private static void eligibility() {
        var shortDrop = new JetpackFastDescent();
        shortDrop.advance(at(0, 2, -0.1, true, true, true, Command.NONE, null), true);
        check(!shortDrop.active(), "a short platform landing needlessly toggled the pack");
        check(!JetpackFastDescent.aligned(LANDING.add(0.2, 10, 0), Vec3.ZERO, LANDING), "off-center column admitted");
        check(!JetpackFastDescent.aligned(LANDING.add(0, 10, 0), new Vec3(0.07, 0, 0), LANDING), "sideways drift admitted");
        var unavailable = new JetpackFastDescent();
        unavailable.advance(at(0, 12, -0.1, true, true, false, Command.NONE, null), true);
        check(!unavailable.active(), "unverified descent column admitted");
    }

    private static void platformGeometry() {
        JetpackRoute.Space flat = new JetpackRoute.Space() {
            public boolean clear(Vec3 from, Vec3 to) { return true; }
            public Vec3 landingBelow(Vec3 point) { return new Vec3(point.x, LANDING.y, point.z); }
        };
        check(JetpackFastDescent.columnAvailable(flat, LANDING.add(0.1, 12, 0), LANDING), "flat supported column rejected");
        JetpackRoute.Space edge = new JetpackRoute.Space() {
            public boolean clear(Vec3 from, Vec3 to) { return true; }
            public Vec3 landingBelow(Vec3 point) {
                return new Vec3(point.x, point.x > LANDING.x + 0.05 ? LANDING.y - 1 : LANDING.y, point.z);
            }
        };
        check(!JetpackFastDescent.columnAvailable(edge, LANDING.add(0.1, 12, 0), LANDING),
                "target support was mistaken for equal-height support directly under the body");
        JetpackRoute.Space obstructed = new JetpackRoute.Space() {
            public boolean clear(Vec3 from, Vec3 to) { return Math.abs(from.y - to.y) < 1; }
            public Vec3 landingBelow(Vec3 point) { return new Vec3(point.x, LANDING.y, point.z); }
        };
        check(!JetpackFastDescent.columnAvailable(obstructed, LANDING.add(0, 12, 0), LANDING),
                "intermediate obstruction in the drop column was ignored");
    }

    private static void intermediateHeight() {
        var descent = new JetpackFastDescent();
        check(descent.advance(at(0, 7, -0.1, true, true, true, Command.NONE, null), true, false) == Command.OFF,
                "a long vertical segment did not enter fast descent");
        descent.advance(at(2, 6.7, -0.25, false, false, true, Command.OFF, Status.CONFIRMED_APPLIED), false, false);
        check(descent.advance(at(10, 2, -0.5, false, false, true, Command.OFF, Status.CONFIRMED_APPLIED), false, false) == Command.ON,
                "intermediate-height restart was missed");
        descent.advance(at(12, 1.9, -0.1078, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false, false);
        check(descent.phase().equals("braking"), "room above the intermediate height did not permit shift descent");
        descent.advance(at(15, 1.0, -0.392, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false, false);
        check(descent.finished(), "intermediate descent waited for onGround instead of releasing shift above its target");
        check(JetpackFastDescent.shiftReleaseHeight(POWER, 200) > JetpackFastDescent.shiftReleaseHeight(POWER, 0),
                "mid-flight shift release ignored connection delay");
        for (int ping : new int[]{0, 50, 200}) {
            double height = 5;
            while (height > JetpackFastDescent.shiftReleaseHeight(POWER, ping)) height -= POWER.vertical();
            height -= POWER.vertical() * (1 + (int)Math.ceil(ping / 50D));
            check(height > 0, "shift-release latency crossed below the next cruise segment");
        }
        JetpackRoute.Space shaft = new JetpackRoute.Space() {
            public boolean clear(Vec3 from, Vec3 to) { return true; }
            public Vec3 landingBelow(Vec3 point) { return new Vec3(point.x, LANDING.y, point.z); }
        };
        Vec3 cruise = LANDING.add(0, 7, 0), high = cruise.add(0, 7, 0);
        check(JetpackFastDescent.columnAvailable(shaft, high, cruise, false), "safe intermediate height required a floor at that height");
        check(!JetpackFastDescent.columnAvailable(shaft, high, cruise, true), "airborne intermediate height became a touchdown surface");
        JetpackRoute.Space voidColumn = new JetpackRoute.Space() {
            public boolean clear(Vec3 from, Vec3 to) { return true; }
            public Vec3 landingBelow(Vec3 point) { return null; }
        };
        check(!JetpackFastDescent.columnAvailable(voidColumn, high, cruise, false), "intermediate descent ignored the missing exit surface");
    }

    private static JetpackFastDescent started() {
        var descent = new JetpackFastDescent();
        descent.advance(at(0, 12, -0.1, true, true, true, Command.NONE, null), true);
        return descent;
    }
    private static Observation at(long tick, double height, double rawVy, boolean active, boolean upright,
                                  boolean clear, Command command, Status receipt) {
        var power = new JetpackNativeAdapter.Snapshot(true, "test", POWER.item(), active, true, 900, 17000,
                POWER.horizontal(), POWER.vertical(), POWER.acceleration(), POWER.hoverDescent(), POWER.gravity());
        return new Observation(tick, LANDING.add(0, height, 0), new Vec3(0, rawVy, 0), LANDING, power,
                clear, upright, height == 0, 0, command, receipt);
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
