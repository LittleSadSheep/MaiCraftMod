package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Quaterniond;

/** 在隔离的刚体副本中推进姿态；原船、方块实体、背包和动力开关都不会被试算修改。 */
public final class PhysicsSimulation {
    private PhysicsSimulation() {}
    public record Limits(double duration, double maxTiltDegrees, double maxVerticalAcceleration,
                         double maxAngularAcceleration, double perturbationDegrees) {
        public Limits {
            if (!Double.isFinite(duration + maxTiltDegrees + maxVerticalAcceleration
                    + maxAngularAcceleration + perturbationDegrees) || duration < 1 || duration > 30
                    || maxTiltDegrees <= 0 || maxTiltDegrees >= 90 || maxVerticalAcceleration <= 0
                    || maxAngularAcceleration <= 0 || perturbationDegrees <= 0 || perturbationDegrees >= maxTiltDegrees)
                throw new IllegalArgumentException("配平时长应在 1–30 秒内，扰动幅度须小于允许倾角");
        }
        public static Limits defaults() { return new Limits(6, 8, .25, .035, 2); }
    }
    public record Sample(double seconds, PhysicsVector position, PhysicsVector velocity,
                         PhysicsBody.Rotation rotation, PhysicsVector angularVelocity, double tiltDegrees) {}
    public record Trial(String mode, String perturbation, double peakTiltDegrees, double finalTiltDegrees,
                        double altitudeChange, double finalVerticalSpeed, double peakAngularSpeed,
                        boolean withinLimits, boolean numericalFailure, List<Sample> trajectory) {}
    public record Assessment(String model, boolean stoppedEquilibrium, boolean runningEquilibrium,
                             boolean restoringStopped, boolean restoringRunning, boolean predictedBalanced,
                             boolean nativeVerified, PhysicsWrench stopped, PhysicsWrench running,
                             List<Trial> trials, List<String> limitations) {}

    public static Assessment assess(PhysicsBody body, Limits limits, Map<String, Double> settings) {
        for (String id : settings.keySet()) if (body.loads().stream().noneMatch(load -> load.id().equals(id)))
            throw new IllegalArgumentException("推力设置引用了未观察到的来源: " + id);
        var stopped = wrench(body, body.rotation(), settings, 0);
        var running = wrench(body, body.rotation(), settings, 1);
        List<Trial> trials = new ArrayList<>();
        for (String mode : List.of("stopped", "running", "starting", "stopping"))
            trials.add(run(body, limits, settings, mode, PhysicsVector.ZERO, "none"));
        // 同时测试左右倾、前后倾；只有一个方向扶正时，不能把平面配平当成整船稳定。
        for (String mode : List.of("stopped", "running")) for (int axis : new int[]{0, 2}) for (int sign : new int[]{-1, 1}) {
            PhysicsVector disturbance = axis == 0 ? new PhysicsVector(sign, 0, 0) : new PhysicsVector(0, 0, sign);
            trials.add(run(body, limits, settings, mode, disturbance, (axis == 0 ? "pitch_" : "roll_") + sign));
        }
        boolean idle = equilibrium(stopped, limits), cruise = equilibrium(running, limits);
        boolean idleRestoring = restoring(body, settings, 0, limits), cruiseRestoring = restoring(body, settings, 1, limits);
        List<String> limitations = new ArrayList<>(body.unknowns());
        limitations.add("隔离刚体预测：未复演地形碰撞、绳索及多刚体约束、传动网络重建、气球充气和流体变化");
        limitations.add("来源推力按已观察或明确声明的工况计算；预测通过仍须在游戏中核验停机、运行和启停");
        return new Assessment("isolated_rigid_body", idle, cruise, idleRestoring, cruiseRestoring,
                idle && cruise && idleRestoring && cruiseRestoring && trials.stream().allMatch(Trial::withinLimits),
                false, stopped, running, List.copyOf(trials), List.copyOf(limitations));
    }

    private static boolean equilibrium(PhysicsWrench wrench, Limits limits) {
        // 航行允许水平推进，但持续升降和绕任意轴加速旋转都意味着当前姿态没有配平。
        return Math.abs(wrench.verticalAcceleration()) <= limits.maxVerticalAcceleration()
                && wrench.angularAcceleration().length() <= limits.maxAngularAcceleration();
    }
    private static PhysicsWrench wrench(PhysicsBody body, PhysicsBody.Rotation rotation,
                                        Map<String, Double> settings, double propulsion) {
        return PhysicsWrench.evaluate(body, rotation, body.position(), PhysicsVector.ZERO,
                controls(body, settings, propulsion), propulsion);
    }
    private static Map<String, Double> controls(PhysicsBody body, Map<String, Double> settings, double propulsion) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (var load : body.loads()) values.put(load.id(), settings.getOrDefault(load.id(), 1.0)
                * (load.propulsion() ? propulsion : 1));
        return values;
    }
    private static boolean restoring(PhysicsBody body, Map<String, Double> settings, double power, Limits limits) {
        for (PhysicsVector axis : List.of(new PhysicsVector(1, 0, 0), new PhysicsVector(0, 0, 1))) for (int sign : new int[]{-1, 1}) {
            var q = perturb(body.rotation(), axis, sign * limits.perturbationDegrees());
            double restoring = wrench(body, q, settings, power).angularAcceleration().dot(axis) * sign;
            if (restoring >= -1e-7) return false;
        }
        return true;
    }

    private static Trial run(PhysicsBody body, Limits limits, Map<String, Double> settings,
                             String mode, PhysicsVector disturbance, String name) {
        PhysicsBody.Rotation attitude = disturbance.length() == 0 ? body.rotation()
                : perturb(body.rotation(), disturbance, limits.perturbationDegrees());
        PhysicsVector position = body.position(), velocity = mode.equals("stopped") ? PhysicsVector.ZERO : body.velocity();
        PhysicsVector omega = disturbance.length() == 0 ? body.angularVelocity() : PhysicsVector.ZERO;
        Map<String, Double> commands = new LinkedHashMap<>(controls(body, settings,
                mode.equals("running") || mode.equals("stopping") ? 1 : 0));
        List<Sample> trajectory = new ArrayList<>();
        double peak = tilt(attitude, body.gravity()), peakSpeed = omega.length(), maxVerticalSpeed = 0;
        double dt = .01; int steps = (int)Math.ceil(limits.duration() / dt);
        boolean failure = false;
        for (int i = 0; i <= steps; i++) {
            double time = i * dt;
            if (i % 25 == 0 || i == steps)
                trajectory.add(new Sample(time, position, velocity, attitude, omega, tilt(attitude, body.gravity())));
            if (i == steps) break;
            double power = switch (mode) {
                case "stopped" -> 0; case "running" -> 1;
                case "starting" -> Math.min(1, time); default -> Math.max(0, 1 - time);
            };
            var desired = controls(body, settings, power);
            for (var load : body.loads()) {
                double target = desired.get(load.id());
                double fraction = load.responseSeconds() == 0 ? 1 : -Math.expm1(-dt / load.responseSeconds());
                commands.compute(load.id(), (id, old) -> old + (target - old) * fraction);
            }
            try {
                var forces = PhysicsWrench.evaluate(body, attitude, position, omega, commands, power);
                velocity = velocity.add(forces.acceleration().scale(dt));
                position = position.add(velocity.scale(dt));
                omega = omega.add(forces.angularAcceleration().scale(dt));
                if (omega.length() > 100 || velocity.length() > 10000) { failure = true; break; }
                if (omega.length() > 1e-12) attitude = PhysicsBody.Rotation.of(new Quaterniond()
                        .rotationAxis(omega.length() * dt, omega.x() / omega.length(), omega.y() / omega.length(),
                                omega.z() / omega.length()).mul(attitude.mutable()));
                peak = Math.max(peak, tilt(attitude, body.gravity())); peakSpeed = Math.max(peakSpeed, omega.length());
                double vertical = body.gravity().length() == 0 ? 0 : velocity.dot(body.gravity()) / body.gravity().length();
                maxVerticalSpeed = Math.max(maxVerticalSpeed, Math.abs(vertical));
            } catch (IllegalArgumentException invalid) { failure = true; break; }
        }
        double g = body.gravity().length();
        double height = g == 0 ? 0 : -position.subtract(body.position()).dot(body.gravity()) / g;
        double vertical = g == 0 ? 0 : -velocity.dot(body.gravity()) / g;
        boolean pass = !failure && peak <= limits.maxTiltDegrees()
                && maxVerticalSpeed <= limits.maxVerticalAcceleration() * limits.duration()
                && peakSpeed <= Math.toRadians(limits.maxTiltDegrees());
        return new Trial(mode, name, peak, tilt(attitude, body.gravity()), height, vertical,
                peakSpeed, pass, failure, List.copyOf(trajectory));
    }
    private static PhysicsBody.Rotation perturb(PhysicsBody.Rotation q, PhysicsVector axis, double degrees) {
        return PhysicsBody.Rotation.of(new Quaterniond().rotationAxis(Math.toRadians(degrees), axis.x(), axis.y(), axis.z()).mul(q.mutable()));
    }
    private static double tilt(PhysicsBody.Rotation q, PhysicsVector gravity) {
        if (gravity.length() == 0) return 0;
        double cos = q.world(new PhysicsVector(0, 1, 0)).dot(gravity.scale(-1 / gravity.length()));
        return Math.toDegrees(Math.acos(Math.clamp(cos, -1, 1)));
    }
}
