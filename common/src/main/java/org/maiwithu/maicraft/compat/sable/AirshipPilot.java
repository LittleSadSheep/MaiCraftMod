// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.world.phys.Vec3;

/**
 * 飞艇的飞控（纯函数的状态机，每刻吃一个样本吐一个输出）：
 * 起飞先垂直离地约 8 格、姿态稳了（倾斜 &lt; 8°、斜角每秒变 &lt; 8.6°）才前进和转向 →
 * 巡航保持高度、车头对准落点再开动力 → 到进近范围保持高度移到落点上方、减速 →
 * 落点上方水平 1 格内、水平速度 &lt; 0.3 格/秒才垂直下降（离地 12 格以上每秒约 4 格快降、燃烧器基本关着，
 * 12 格以内每秒约 1 格去接地；按漂移小修，近地停桨）→ 接地交给停稳确认。不在空中慢慢等它飘下来。
 * 升力在悬停配平附近按高度差与升降速度调；配平先按 0.5，平飞时按实测慢慢修正——求不出不当准入条件。
 * 巡航中碰到地面（撞山、压到树）也按落地交给停稳确认，结果写明不是按进近落的。
 */
final class AirshipPilot {

    enum Phase { CLIMB_OUT, CRUISE, APPROACH, DESCENT, LANDED, FAILED }

    /** 起飞先垂直离地多少格。 */
    static final double CLEARANCE = 8;
    /** 姿态稳：倾斜多少度以内、斜角每秒变多少度以内。 */
    static final double STABLE_TILT = 8;
    static final double STABLE_TILT_RATE = Math.toDegrees(0.15);
    /** 巡航速度、爬升与下降速度（每秒几格）。 */
    static final double CRUISE_SPEED = 6;
    static final double CLIMB_RATE = 2;
    static final double DESCENT_RATE = 1;
    /** 离落点还高时的快降速度（每秒几格）与放慢的高度（格）：高处每秒 1 格要等太久。 */
    static final double FAST_DESCENT_RATE = 4;
    static final double SLOW_DOWN_HEIGHT = 12;
    /** 在落点上方多近、水平多慢才往下降。 */
    static final double OVERHEAD = 1;
    static final double OVERHEAD_SPEED = 0.3;
    /** 在落点三格内磨了这么久还对不准，就照现在的位置往下降（刻）。 */
    static final int SETTLE_TICKS = 200;
    /** 按升力这么久还没离地就不再等（刻）。 */
    static final int LIFTOFF_TICKS = 600;
    /** 接地连着几个样本算落地了。 */
    static final int TOUCHDOWN_SAMPLES = 3;

    /**
     * 这一趟：落点（地面那一格的顶面中心）与巡航高度（船底的世界 y）。
     */
    record Course(Vec3 touchdown, double cruiseAltitude) {}

    private final double startY;
    private final List<String> transitions = new ArrayList<>();
    private Phase phase = Phase.CLIMB_OUT;
    private long phaseStart = Long.MIN_VALUE;
    private long nearSince = Long.MIN_VALUE;
    private int groundedSamples;
    private double hover = 0.5;
    private boolean unplannedTouchdown;
    private String failure = "";

    /** @param startY 起飞时船底的高度 */
    AirshipPilot(double startY) {
        this.startY = startY;
    }

    Phase phase() {
        return phase;
    }

    /** 阶段怎么变过来的，一条一句。 */
    List<String> transitions() {
        return List.copyOf(transitions);
    }

    /** 巡航中碰到了地面（不是按进近落的）。 */
    boolean unplannedTouchdown() {
        return unplannedTouchdown;
    }

    /** 失败的原因；没失败为空串。 */
    String failure() {
        return failure;
    }

    /** 此刻的悬停配平（升力开多少能不升不降）。 */
    double hover() {
        return hover;
    }

    PilotCommand tick(PilotSample sample, Course course) {
        if (phaseStart == Long.MIN_VALUE) phaseStart = sample.tick();
        groundedSamples = sample.grounded() ? groundedSamples + 1 : 0;
        double distance = sample.flatDistance(course.touchdown());
        double headingError = PilotSample.turn(sample.heading(), sample.headingTo(course.touchdown()));
        boolean stable = sample.tilt() < STABLE_TILT && Math.abs(sample.tiltRate()) < STABLE_TILT_RATE;
        switch (phase) {
            case CLIMB_OUT -> {
                // 先垂直离地：只开升力，按爬升速度调；离地够高、姿态稳了再前进。
                if (sample.position().y >= startY + CLEARANCE && stable) {
                    go(sample, distance < approachRadius(sample) ? Phase.APPROACH : Phase.CRUISE,
                            "离地 " + Math.round(sample.position().y - startY) + " 格、姿态稳了");
                } else if (sample.tick() - phaseStart >= LIFTOFF_TICKS && sample.position().y < startY + 1.5) {
                    fail(sample, "开着升力 " + LIFTOFF_TICKS / 20 + " 秒没离地（升力不够或气球没充起来）");
                    return PilotCommand.IDLE;
                } else {
                    return new PilotCommand(0, hover + (CLIMB_RATE - sample.velocity().y) * 0.25, 0, false);
                }
            }
            case CRUISE -> {
                if (touchedDown(sample)) return PilotCommand.IDLE;
                if (distance < approachRadius(sample)) go(sample, Phase.APPROACH, "到了进近范围，离落点 " + Math.round(distance) + " 格");
            }
            case APPROACH -> {
                if (touchedDown(sample)) return PilotCommand.IDLE;
                if (distance <= 3) {
                    if (nearSince == Long.MIN_VALUE) nearSince = sample.tick();
                } else {
                    nearSince = Long.MIN_VALUE;
                }
                if (distance <= OVERHEAD && sample.flatSpeed() < OVERHEAD_SPEED) {
                    go(sample, Phase.DESCENT, "到了落点上方、减了速，垂直下降");
                } else if (nearSince != Long.MIN_VALUE && sample.tick() - nearSince >= SETTLE_TICKS) {
                    go(sample, Phase.DESCENT, "在落点上方磨了 " + SETTLE_TICKS / 20 + " 秒对不准，偏 "
                            + Math.round(distance * 10) / 10.0 + " 格就地下降");
                }
            }
            case DESCENT -> {
                if (groundedSamples >= TOUCHDOWN_SAMPLES) {
                    go(sample, Phase.LANDED, "接地了，松开所有键");
                    return PilotCommand.IDLE;
                }
            }
            case LANDED, FAILED -> {
                return PilotCommand.IDLE;
            }
        }
        return command(sample, course, distance, headingError);
    }

    // 各阶段的输出：巡航与进近保持巡航高度；下降高处快、近地每秒约 1 格；转向近地时不动。
    private PilotCommand command(PilotSample sample, Course course, double distance, double headingError) {
        double turn = servo(headingError, sample.turnRate());
        boolean aligned = Math.abs(headingError) < 30;
        return switch (phase) {
            case CRUISE -> {
                trim(sample, course.cruiseAltitude());
                double power = aligned ? 0.55 + (CRUISE_SPEED - sample.flatSpeed()) * 0.1 : 0;
                yield new PilotCommand(power, lift(sample, course.cruiseAltitude()), turn, false);
            }
            case APPROACH -> {
                trim(sample, course.cruiseAltitude());
                // 往落点上方挪、越近越慢；冲得比剩下的距离快就倒车（有倒车键时）刹一刹。
                double power = distance > Math.max(1.5, sample.flatSpeed() * 2) && aligned
                        ? (distance - 1.5) * 0.08 : 0;
                if (power > 0.6) power = 0.6;
                double closing = distance < 1e-6 ? 0 : ((course.touchdown().x - sample.position().x) * sample.velocity().x
                        + (course.touchdown().z - sample.position().z) * sample.velocity().z) / distance;
                if (closing * 2 > distance && closing > OVERHEAD_SPEED) power = -0.5;
                yield new PilotCommand(power, lift(sample, course.cruiseAltitude()), distance > 3 ? turn : 0, false);
            }
            case DESCENT -> {
                double height = sample.position().y - course.touchdown().y;
                // 下降中漂开 2 格以上、离地还有 4 格以上时小推一下修回去；近地停桨，不转向。
                double power = distance > 2 && height > 4 && aligned ? Math.min(0.3, distance * 0.05) : 0;
                // 离地 12 格以上按每秒 4 格往下压（升力低于配平，燃烧器基本关着），之后按每秒 1 格放慢去接地。
                double rate = height > SLOW_DOWN_HEIGHT ? FAST_DESCENT_RATE : DESCENT_RATE;
                yield new PilotCommand(power, hover + (-rate - sample.velocity().y) * 0.5, 0, false);
            }
            default -> PilotCommand.IDLE;
        };
    }

    // 巡航与进近中碰到地面：不是按进近落的，照样交给停稳确认。
    private boolean touchedDown(PilotSample sample) {
        if (groundedSamples < TOUCHDOWN_SAMPLES) return false;
        unplannedTouchdown = true;
        go(sample, Phase.LANDED, "还没到落点就碰到了地面，松开所有键");
        return true;
    }

    // 升力：悬停配平 + 高度差 × 0.12 − 升降速度 × 0.2。
    private double lift(PilotSample sample, double altitude) {
        return hover + (altitude - sample.position().y) * 0.12 - sample.velocity().y * 0.2;
    }

    // 平飞（高度差 1 格内、升降慢）时按这一刻的升力慢慢修配平。
    private void trim(PilotSample sample, double altitude) {
        if (Math.abs(altitude - sample.position().y) < 1 && Math.abs(sample.velocity().y) < 0.3) {
            hover = PilotCommand.clamp(hover * 0.98 + lift(sample, altitude) * 0.02, 0.05, 0.95);
        }
    }

    private double approachRadius(PilotSample sample) {
        return Math.max(24, sample.flatSpeed() * 3);
    }

    // 转向：朝向差 × 1.5 − 转速 × 0.5（都按弧度），太小的不按。
    static double servo(double errorDegrees, double rateDegrees) {
        double value = Math.toRadians(errorDegrees) * 1.5 - Math.toRadians(rateDegrees) * 0.5;
        return Math.abs(value) < 0.025 ? 0 : value;
    }

    private void go(PilotSample sample, Phase next, String why) {
        if (phase == next) return;
        transitions.add(phase.name().toLowerCase(Locale.ROOT) + " → " + next.name().toLowerCase(Locale.ROOT) + "：" + why);
        phase = next;
        phaseStart = sample.tick();
    }

    private void fail(PilotSample sample, String why) {
        failure = why;
        go(sample, Phase.FAILED, why);
    }
}
