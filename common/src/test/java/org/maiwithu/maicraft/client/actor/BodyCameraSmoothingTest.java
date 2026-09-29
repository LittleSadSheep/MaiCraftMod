package org.maiwithu.maicraft.client.actor;

import org.maiwithu.maicraft.entity.InputDriver;

/** 移动目标不能让已稳定的镜头漂移，或使镜头加速越过目标。 */
public final class BodyCameraSmoothingTest {
    public static void main(String[] args) throws Exception {
        lateNavigationYieldsToCurrentInteraction();
        for (float dt : new float[]{1f / 30, 1f / 60, 1f / 144}) {
            for (float velocity : new float[]{-240, -20, 0, 20, 240}) {
                var settled = step(0, 0, velocity, dt);
                check(settled.value() == 0 && settled.velocity() == 0, "a reached target cancels residual velocity");
                for (float target : new float[]{-90, -1, 1, 90}) {
                    float angle = 0, speed = velocity;
                    for (int frame = 0; frame < Math.ceil(3 / dt); frame++) {
                        var next = step(angle, target, speed, dt);
                        check(next.value() >= Math.min(angle, target) && next.value() <= Math.max(angle, target),
                                "camera must stay between its current angle and requested target");
                        angle = next.value(); speed = next.velocity();
                    }
                    check(Math.abs(angle - target) < .01, "bounded response still converges");
                }
            }
        }
        var wrapped = DefaultBodyControlPort.smoothDampAngle(179, -179, 0, .11f, 240, 1f / 60);
        check(wrapped.value() >= 179 && wrapped.value() <= 181, "wrap uses the short arc");
        System.out.println("BodyCameraSmoothingTest: passed");
    }

    private static void lateNavigationYieldsToCurrentInteraction() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 实际帧顺序是任务先瞄准、导航后驱动；晚来的普通路线视角不能让相机在敌人和退路之间摆动。
            InputDriver.look(h.player, -90, -12);
            InputDriver.applyMovement(h.player, 1, 0, false, false, true);
            InputDriver.lookForNavigation(h.player, 90, 80);
            check((float) ActorControlTestHarness.field(DefaultBodyControlPort.class, "targetYaw").get(h.h.body) == -90
                            && (float) ActorControlTestHarness.field(DefaultBodyControlPort.class, "targetPitch").get(h.h.body) == -12,
                    "frame-end navigation cannot overwrite a current interaction aim");
            h.h.body.endTick(h.h.context);
            check(h.player.input.forwardImpulse == 1, "view arbitration does not cancel the retained movement");
            h.nextTick(); InputDriver.lookForNavigation(h.player, 90, 8);
            check((float) ActorControlTestHarness.field(DefaultBodyControlPort.class, "targetYaw").get(h.h.body) == 90,
                    "unrenewed interaction aim expires on the next tick");
            InputDriver.look(h.player, -45, 3); InputDriver.lookForNavigation(h.player, 45, 8);
            check((float) ActorControlTestHarness.field(DefaultBodyControlPort.class, "targetYaw").get(h.h.body) == -45,
                    "interaction also wins when requested after navigation");
            h.h.body.clearLook(); InputDriver.lookForNavigation(h.player, 45, 8);
            check((float) ActorControlTestHarness.field(DefaultBodyControlPort.class, "targetYaw").get(h.h.body) == 45,
                    "explicitly cancelled aim gives the same tick back to navigation");
        }
    }

    private static DefaultBodyControlPort.AxisStep step(float angle, float target, float velocity, float dt) {
        return DefaultBodyControlPort.smoothDamp(angle, target, velocity, .11f, 240, dt);
    }
    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
