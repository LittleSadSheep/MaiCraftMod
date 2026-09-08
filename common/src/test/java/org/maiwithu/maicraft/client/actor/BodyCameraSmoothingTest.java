package org.maiwithu.maicraft.client.actor;

/** Moving targets cannot make a settled camera drift away or accelerate past its target. */
public final class BodyCameraSmoothingTest {
    public static void main(String[] args) {
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

    private static DefaultBodyControlPort.AxisStep step(float angle, float target, float velocity, float dt) {
        return DefaultBodyControlPort.smoothDamp(angle, target, velocity, .11f, 240, dt);
    }
    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
