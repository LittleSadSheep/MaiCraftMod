// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt.Status;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFastDescent.Command;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFastDescent.Observation;

/**
 * 给定高度、速度、延迟和模式记录，检查快速下降各阶段和取消后的恢复；无伤短落差直接传布尔结果，没有调用外层真实摔落预算。
 */
public final class JetpackFastDescentTest {
    private static final Vec3 LANDING = new Vec3(0.5, 103, 0.5);
    private static final JetpackNativeAdapter.Snapshot POWER = new JetpackNativeAdapter.Snapshot(
            true, "installed native parameters", "create_jetpack:netherite_jetpack", true, true,
            900, 17000, 0.016, 0.32, 0.6, -0.03, 0.08);

    public static void main(String[] args) {
        normalSequence();
        harmlessShortDrop();
        shortApproachResynchronizesPower();
        longDescentKeepsMakingProgress();
        brakingCorrectsDriftWithinColumn();
        cancellationRestartsImmediately();
        changedColumnRestoresHover();
        failedReceiptDoesNotReleaseDisabledPack();
        physicsWindow();
        eligibility();
        platformGeometry();
        intermediateHeight();
        System.out.println("JetpackFastDescentTest: passed");
    }

    // 这里直接把 harmlessDrop 设为真，因此只验证收到这个结论后的流程，不验证这个结论是否算对。
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

    // 累计高度仍可能造成摔落伤害时，短距离也先确认重启及真实减速，不能直接潜行到底。
    private static void shortApproachResynchronizesPower() {
        var descent = new JetpackFastDescent();
        check(descent.advance(at(0, 2.9, -.1078, true, true, true, Command.NONE, null), true) == Command.ON
                        && descent.phase().equals("enabling"),
                "an accumulated fall must resynchronize native power even near the platform");
        var intermediate = new JetpackFastDescent();
        intermediate.advance(at(0, 2, -.1078, true, true, true, Command.NONE, null), true, false);
        check(intermediate.phase().equals("enabling"), "a short cruise descent must also confirm native power");
        for (int tick = 1; tick <= 3; tick++)
            intermediate.advance(at(tick, .1, -.1078, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false, false);
        check(intermediate.finished(), "observed braking near the cruise height must hand back altitude control");
        var low = new JetpackFastDescent();
        low.advance(at(0, .1, -.1078, true, true, true, Command.NONE, null), true, false);
        check(!low.active(), "an intermediate target inside the release window must retain ordinary hover");
        descent.requestStop();
        check(descent.advance(at(1, 2.8, -.392, true, true, true, Command.NONE, null), false) == Command.ON,
                "cancelling the approach must confirm enabled hover");
        var stalled = new JetpackFastDescent();
        stalled.advance(at(0, 2, -.1078, true, true, true, Command.NONE, null), true);
        check(stalled.advance(at(41, 2, -1, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false) == Command.ON
                        && !stalled.finished(), "a local ON receipt without physical braking must resend ON and retain responsibility");
    }

    private static void longDescentKeepsMakingProgress() {
        var descent = new JetpackFastDescent();
        double height = 180, velocity = -.1078;
        boolean active = true;
        Command receipt = Command.NONE;
        int offCycles = 0, ticks = 0;
        for (; ticks < 700 && !descent.finished(); ticks++) {
            Command command = descent.advance(at(ticks, height, velocity, active, active, true, receipt,
                    receipt == Command.NONE ? null : Status.CONFIRMED_APPLIED), true);
            if (command != Command.NONE) { active = command != Command.OFF; receipt = command; }
            if (command == Command.OFF) offCycles++;
            // 回放只允许原生重力与重启悬停；不给旧潜行分支任何额外下降速度。
            double step = !active ? velocity : JetpackDynamics.nextVertical(velocity, false, POWER);
            height = Math.max(0, height + step);
            velocity = height == 0 ? 0 : JetpackDynamics.rawAfterStep(step, POWER);
        }
        check(offCycles >= 2, "early braking must permit another verified free descent instead of a long hover tail");
        check(descent.finished() && height == 0 && ticks < 500,
                "controlled OFF/ON descent must reach the supported platform without retiring in midair");
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
        check(descent.phase().equals("enabling"), "one client receipt must not replace physical braking observation");
        descent.advance(at(13, 1.74, -0.1078, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false);
        descent.advance(at(14, 1.71, -0.1078, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false);
        check(descent.phase().equals("braking"), "stable native restart should enter the final braking approach");
        descent.advance(at(16, 0, 0, true, false, true, Command.ON, Status.CONFIRMED_APPLIED), false);
        check(descent.finished(), "grounded touchdown did not complete");
    }

    private static void cancellationRestartsImmediately() {
        var descent = started();
        descent.requestStop();
        check(descent.advance(at(1, 11.9, -0.18, false, false, true, Command.OFF, Status.PENDING), false) == Command.ON,
                "cancellation waited for old OFF receipt instead of reversing it");
        for (int tick = 2; tick <= 4; tick++)
            descent.advance(at(tick, 11.7, -0.1, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false);
        check(descent.finished(), "cancellation did not hand back an enabled upright hover");
    }

    private static void brakingCorrectsDriftWithinColumn() {
        for (float yaw : new float[]{0, 35, 90, 170}) {
            Vec3 position = LANDING.add(.17, 20, 0), velocity = Vec3.ZERO;
            for (int tick = 0; tick < 80; tick++) {
                var input = JetpackFastDescent.descentSteering(position, velocity, LANDING, yaw, POWER);
                check(!input.sneaking() && !input.sprinting(), "descent alignment must not sneak or sprint");
                var next = JetpackMotion.step(position, velocity, input, yaw, POWER);
                position = next.position(); velocity = next.velocity();
                check(JetpackFastDescent.aligned(position, velocity, LANDING),
                        "horizontal correction must not accelerate itself outside the descent column");
            }
            check(Math.hypot(position.x - LANDING.x, position.z - LANDING.z) < .06,
                    "braking must converge toward the landing instead of just stopping at its current position");
        }
    }

    private static void changedColumnRestoresHover() {
        var descent = started();
        check(descent.advance(at(1, 11.9, -0.18, false, false, false, Command.OFF, Status.CONFIRMED_APPLIED), false) == Command.ON,
                "changed support continued free descent");
        for (int tick = 2; tick <= 4; tick++)
            descent.advance(at(tick, 11.7, -0.1, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false);
        check(descent.finished(), "restored geometry incorrectly resumed the abandoned free drop");
        var shifted = started();
        var original = at(1, 11.9, -0.18, false, false, true, Command.OFF, Status.CONFIRMED_APPLIED);
        var changed = new Observation(1, original.position(), original.velocity(), LANDING.add(0, -1, 0),
                original.power(), true, false, false, 0, original.receiptCommand(), original.receiptStatus());
        check(shifted.advance(changed, false) == Command.ON, "a different platform height was silently adopted mid-drop");
    }

    // 故意让重启结果长期不确定，确认控制器仍保留恢复责任并再次请求绝对的开启状态。
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
            // 预留四个余量 tick，即使检测晚一个物理步才越过阈值也有足够空间。
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
        check(shortDrop.advance(at(0, 2, -0.1, true, true, true, Command.NONE, null), true) == Command.ON,
                "a short approach without a harmless fall budget must confirm native braking");
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
        for (int tick = 12; tick <= 14; tick++)
            descent.advance(at(tick, 1.9, -0.1078, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false, false);
        check(descent.phase().equals("braking"), "intermediate descent did not observe native braking");
        descent.advance(at(15, .1, -.1078, true, true, true, Command.ON, Status.CONFIRMED_APPLIED), false, false);
        check(descent.finished(), "intermediate descent waited for onGround instead of handing back height control");
        check(JetpackFastDescent.hoverReleaseHeight(POWER, 200) > JetpackFastDescent.hoverReleaseHeight(POWER, 0),
                "mid-flight height handoff ignored connection delay");
        for (int ping : new int[]{0, 50, 200}) {
            double height = 5;
            while (height > JetpackFastDescent.hoverReleaseHeight(POWER, ping)) height += POWER.hoverDescent();
            height += POWER.hoverDescent() * (1 + (int)Math.ceil(ping / 50D));
            check(height > 0, "braking handoff latency crossed below the next cruise segment");
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
