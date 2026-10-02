package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.joml.Matrix3d;
import org.joml.Quaterniond;

/** 用可算出结果的飞艇验证配重、偏置推进与姿态变换，不依赖启动 Minecraft 或 Sable。 */
public final class PhysicsBalanceRegression {
    public static void main(String[] args) {
        PhysicsBody body = vessel(List.of(new PhysicsBody.Load("balloon", "balloon_lift", v(0, 2, 0),
                v(0, 100, 0), PhysicsVector.ZERO, PhysicsBody.Frame.WORLD, false, 0)));
        var idle = wrench(body, body.rotation(), 0);
        near(idle.force().length(), 0, "等重浮力应能悬停");
        var tilted = wrench(body, PhysicsBody.Rotation.of(new Quaterniond().rotationZ(.1)), 0);
        check(tilted.torque().z() < 0, "上方浮力中心应产生扶正力矩");
        var ballast = body.ballast(2, v(3, -2, 1), new Matrix3d().scaling(1.0 / 3));
        near(ballast.mass(), 12, "铁块增加的重量必须计入浮力需求");
        near(ballast.center().x(), .5, "偏心配重必须移动质心");
        check(Math.abs(ballast.inertia().xy()) > 0, "斜向配重应产生非对角惯量");
        var removed = ballast.ballast(-2, v(3, -2, 1), new Matrix3d().scaling(-1.0 / 3));
        near(removed.center().length(), 0, "移除同一配重应恢复质心");
        near(removed.inertia().xx(), body.inertia().xx(), "移除同一配重应恢复惯量");
        var engine = new PhysicsBody.Load("right_propeller", "propulsion", v(2, 0, 0),
                v(0, 100, 0), PhysicsVector.ZERO, PhysicsBody.Frame.BODY, true, 0);
        var powered = vessel(List.of(engine));
        near(wrench(powered, powered.rotation(), 0).verticalAcceleration(), -10, "关桨不能把飞行升力留在预测里");
        near(wrench(powered, powered.rotation(), 1).torque().z(), 200, "侧置桨会造成翻转力矩");
        var turn = PhysicsBody.Rotation.of(new Quaterniond().rotationY(Math.PI / 2));
        near(wrench(powered, turn, 1).torque().x(), 200, "船体转向后应转换力矩坐标");
        check(body.center().equals(PhysicsVector.ZERO), "预测配重不应改动原始快照");
        System.out.println("PhysicsBalanceRegression: passed");
    }
    static PhysicsBody vessel(List<PhysicsBody.Load> loads) {
        return new PhysicsBody(UUID.fromString("00000000-0000-4000-8000-000000000001"), "minecraft:overworld", 1,
                10, PhysicsVector.ZERO, new PhysicsBody.Inertia(20, 20, 20, 0, 0, 0),
                PhysicsBody.Rotation.of(new Quaterniond()), v(0, 100, 0), PhysicsVector.ZERO,
                PhysicsVector.ZERO, v(0, -10, 0), loads, List.of());
    }
    static PhysicsWrench wrench(PhysicsBody b, PhysicsBody.Rotation q, double propulsion) {
        return PhysicsWrench.evaluate(b, q, b.position(), b.angularVelocity(), Map.of(), propulsion);
    }
    static PhysicsVector v(double x, double y, double z) { return new PhysicsVector(x, y, z); }
    static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-8, message + ": " + actual);
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
