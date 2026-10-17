// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * 飞控与开车：放进一个粗糙的玩具物理里跑——升力开一半刚好悬停、动力沿车头推、转向转车头——
 * 飞艇要走完"垂直离地 → 巡航 → 进近 → 垂直下降 → 接地"，落在落点附近；车开到目标附近停下；
 * 键按 4 刻一组调、零清掉累计、没有的角色不按。
 */
class PilotsTest {

    /** 玩具载具：每刻按输出改速度，地面在 y=64；飞艇落地时地面把水平速度吃掉一半，车在路上不吃。 */
    private static final class Toy {
        final boolean road;
        double x, y = 64, z, vx, vy, vz, heading;
        long tick;

        Toy(boolean road) {
            this.road = road;
        }

        PilotSample sample() {
            boolean grounded = y <= 64.001;
            return new PilotSample(tick, new Vec3(x, y, z), new Vec3(vx, vy, vz), heading, 0, 0, 0,
                    grounded ? VehicleAttitude.GROUNDED : VehicleAttitude.AIRBORNE);
        }

        void step(PilotCommand command, double turnRate) {
            double dt = 0.05;
            vy += ((command.lift() - 0.5) * 4 - vy * 0.5) * dt;
            double fx = -Math.sin(Math.toRadians(heading));
            double fz = Math.cos(Math.toRadians(heading));
            vx += (command.power() * 3 * fx - vx * 0.3) * dt;
            vz += (command.power() * 3 * fz - vz * 0.3) * dt;
            heading += command.turn() * turnRate * dt;
            x += vx * dt;
            y += vy * dt;
            z += vz * dt;
            if (y < 64) {
                y = 64;
                vy = 0;
                if (!road) {
                    vx *= 0.5;
                    vz *= 0.5;
                }
            }
            tick++;
        }
    }

    @Test
    void 飞艇垂直离地_巡航_进近_垂直下降_接地() {
        Toy ship = new Toy(false);
        AirshipPilot pilot = new AirshipPilot(64);
        AirshipPilot.Course course = new AirshipPilot.Course(new Vec3(60, 64, 0), 64 + 20);
        double highestBeforeForward = 64;
        for (int i = 0; i < 12000 && pilot.phase() != AirshipPilot.Phase.LANDED
                && pilot.phase() != AirshipPilot.Phase.FAILED; i++) {
            PilotCommand command = pilot.tick(ship.sample(), course);
            if (pilot.phase() == AirshipPilot.Phase.CLIMB_OUT) {
                assertEquals(0, command.power(), 1e-9, "离地前不开动力");
                highestBeforeForward = ship.y;
            }
            ship.step(command, 40);
        }
        assertEquals(AirshipPilot.Phase.LANDED, pilot.phase(), pilot.transitions().toString());
        assertFalse(pilot.unplannedTouchdown(), pilot.transitions().toString());
        assertTrue(highestBeforeForward >= 64 + AirshipPilot.CLEARANCE - 0.5, "先垂直离地约 8 格");
        assertTrue(Math.hypot(ship.x - 60, ship.z) < 3.5, "落在落点附近：" + ship.x + "," + ship.z);
        assertTrue(pilot.transitions().stream().anyMatch(line -> line.startsWith("approach → descent")),
                pilot.transitions().toString());
    }

    @Test
    void 飞艇高处快降_离地十二格内放慢_不在空中慢慢飘() {
        // 已经在落点正上方 40 格：先快降，近地放慢接地；接地时下沉不快，整段比每秒 1 格降下来快得多。
        Toy ship = new Toy(false);
        ship.y = 104;
        AirshipPilot pilot = new AirshipPilot(64);
        AirshipPilot.Course course = new AirshipPilot.Course(new Vec3(0, 64, 0), 104);
        double fastest = 0;
        double touchdownSpeed = 0;
        int ticks = 0;
        for (; ticks < 6000 && pilot.phase() != AirshipPilot.Phase.LANDED; ticks++) {
            PilotCommand command = pilot.tick(ship.sample(), course);
            if (pilot.phase() == AirshipPilot.Phase.DESCENT) fastest = Math.max(fastest, -ship.vy);
            double before = ship.vy;
            ship.step(command, 40);
            if (ship.y <= 64.001 && touchdownSpeed == 0) touchdownSpeed = -before;
        }
        assertEquals(AirshipPilot.Phase.LANDED, pilot.phase(), pilot.transitions().toString());
        assertTrue(fastest > 2.8, "高处快降：最快每秒 " + fastest + " 格");
        assertTrue(touchdownSpeed < 1.6, "近地放慢：接地时每秒 " + touchdownSpeed + " 格");
        assertTrue(ticks < 20 * 30, "四十格不到三十秒降完：" + ticks + " 刻");
    }

    @Test
    void 开着升力半分钟没离地就不再等() {
        Toy stuck = new Toy(false);
        AirshipPilot pilot = new AirshipPilot(64);
        AirshipPilot.Course course = new AirshipPilot.Course(new Vec3(60, 64, 0), 84);
        for (int i = 0; i < AirshipPilot.LIFTOFF_TICKS + 5; i++) {
            pilot.tick(stuck.sample(), course);
            stuck.tick++;
        }
        assertEquals(AirshipPilot.Phase.FAILED, pilot.phase());
        assertTrue(pilot.failure().contains("没离地"), pilot.failure());
    }

    @Test
    void 车开到目标附近停下() {
        Toy car = new Toy(true);
        GroundPilot pilot = new GroundPilot(true);
        Vec3 target = new Vec3(-30, 64, 40);
        for (int i = 0; i < 4000 && pilot.phase() != GroundPilot.Phase.STOPPED
                && pilot.phase() != GroundPilot.Phase.FAILED; i++) {
            PilotCommand command = pilot.tick(car.sample(), target);
            if (command.brake()) {
                car.vx *= 0.8;
                car.vz *= 0.8;
            }
            car.step(command, 60);
        }
        assertEquals(GroundPilot.Phase.STOPPED, pilot.phase(), pilot.transitions().toString());
        assertTrue(Math.hypot(car.x + 30, car.z - 40) < GroundPilot.ARRIVE + 2, car.x + "," + car.z);
    }

    @Test
    void 按着动力不动就是卡住() {
        Toy wall = new Toy(true);
        GroundPilot pilot = new GroundPilot(true);
        for (int i = 0; i < GroundPilot.STUCK_TICKS + 5; i++) {
            pilot.tick(wall.sample(), new Vec3(0, 64, 40));
            wall.tick++;
        }
        assertEquals(GroundPilot.Phase.FAILED, pilot.phase());
    }

    @Test
    void 键按四刻一组调_零清掉累计_没有的角色不按() {
        KeyMixer mixer = new KeyMixer(Map.of("power", 87, "lift", 32, "yaw_left", 65));
        int pressedTicks = 0;
        for (long tick = 0; tick < 80; tick++) {
            Set<Integer> held = mixer.tick(tick, new PilotCommand(0.5, 1, 0.7, true), false);
            assertTrue(held.contains(32), "满量一直按着");
            assertFalse(held.contains(65), "往右转的需求不按左键");
            if (held.contains(87)) pressedTicks++;
        }
        assertEquals(40, pressedTicks, "一半的动力按一半的刻数");
        assertEquals(Set.of(), mixer.tick(80, PilotCommand.IDLE, false));
        assertFalse(mixer.has("brake"));
    }
}
