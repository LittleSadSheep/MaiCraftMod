// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.world.phys.Vec3;

/**
 * 车与船的驾驶（纯函数的状态机）：不建力学模型，朝目标打方向、车头大致对准了再给动力，离目标越近越慢，
 * 到了（3 格内）松动力刹住，慢到每秒 0.35 格以下交给停稳确认。车要有速度才转得动，车头偏太多时也给一点动力；
 * 船能原地慢慢转。按着动力 5 秒还几乎没挪，就是卡住了（或动力不够），照实停下。只开直线：路上的障碍不绕。
 */
final class GroundPilot {

    enum Phase { DRIVE, STOPPING, STOPPED, FAILED }

    /** 到目标多近算到了（格）。 */
    static final double ARRIVE = 3;
    /** 平路上开多快、进了这个半径开始减速（每秒几格、格）。 */
    static final double SPEED = 4;
    static final double SLOW_RADIUS = 10;
    /** 慢到多少算停下了（每秒几格）。 */
    static final double STOPPED_SPEED = 0.35;
    /** 按着动力几乎不挪多久算卡住（刻）。 */
    static final int STUCK_TICKS = 100;
    /** 刹车最多刹多久（刻）。 */
    static final int BRAKE_TICKS = 200;

    private final boolean car;
    private final List<String> transitions = new ArrayList<>();
    private Phase phase = Phase.DRIVE;
    private long phaseStart = Long.MIN_VALUE;
    private long stuckSince = Long.MIN_VALUE;
    private String failure = "";

    /** @param car 是车（转向靠打方向、要有速度才转得动）；船为 false */
    GroundPilot(boolean car) {
        this.car = car;
    }

    Phase phase() {
        return phase;
    }

    List<String> transitions() {
        return List.copyOf(transitions);
    }

    String failure() {
        return failure;
    }

    PilotCommand tick(PilotSample sample, Vec3 target) {
        if (phaseStart == Long.MIN_VALUE) phaseStart = sample.tick();
        double distance = sample.flatDistance(target);
        switch (phase) {
            case DRIVE -> {
                if (distance <= ARRIVE) {
                    go(sample, Phase.STOPPING, "到了目标 " + ARRIVE + " 格内，松动力刹住");
                    return new PilotCommand(0, 0, 0, true);
                }
                double error = PilotSample.turn(sample.heading(), sample.headingTo(target));
                double wanted = distance < SLOW_RADIUS ? Math.max(1, distance * 0.4) : SPEED;
                double power = Math.abs(error) < 60 ? 0.5 + (wanted - sample.flatSpeed()) * 0.15 : car ? 0.3 : 0;
                // 按着动力却几乎不动：卡住了或动力不够。
                if (power > 0.2 && sample.flatSpeed() < 0.2) {
                    if (stuckSince == Long.MIN_VALUE) stuckSince = sample.tick();
                    if (sample.tick() - stuckSince >= STUCK_TICKS) {
                        failure = "按着动力 " + STUCK_TICKS / 20 + " 秒几乎没挪（卡住了或动力不够），离目标 " + Math.round(distance) + " 格";
                        go(sample, Phase.FAILED, failure);
                        return new PilotCommand(0, 0, 0, true);
                    }
                } else {
                    stuckSince = Long.MIN_VALUE;
                }
                return new PilotCommand(power, 0, AirshipPilot.servo(error, sample.turnRate()), false);
            }
            case STOPPING -> {
                if (sample.flatSpeed() < STOPPED_SPEED) {
                    go(sample, Phase.STOPPED, "停下了");
                    return PilotCommand.IDLE;
                }
                if (sample.tick() - phaseStart >= BRAKE_TICKS) {
                    go(sample, Phase.STOPPED, "刹了 " + BRAKE_TICKS / 20 + " 秒还在挪，松开交给停稳确认");
                    return PilotCommand.IDLE;
                }
                return new PilotCommand(0, 0, 0, true);
            }
            default -> {
                return PilotCommand.IDLE;
            }
        }
    }

    private void go(PilotSample sample, Phase next, String why) {
        if (phase == next) return;
        transitions.add(phase.name().toLowerCase(Locale.ROOT) + " → " + next.name().toLowerCase(Locale.ROOT) + "：" + why);
        phase = next;
        phaseStart = sample.tick();
    }
}
