// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;

/** 从试按效果认角色：最明显的那一样；平着挪定车头，反着是倒车；车头定了才分俯仰横滚；太小是看不出动。 */
class TestedRolesTest {

    private static VehicleDriver.Effect effect(double x, double y, double z, double turnX, double turnY, double turnZ) {
        return new VehicleDriver.Effect(x, y, z, turnX, turnY, turnZ);
    }

    @Test
    void 绕竖轴转是偏航_车是转向() {
        assertEquals("yaw_left", TestedRoles.of(effect(0.1, 0, 0.2, 0, 8, 0), "airship", "").role());
        assertEquals("yaw_right", TestedRoles.of(effect(0, 0, 0, 0, -5, 0), "boat", "").role());
        assertEquals("steer_left", TestedRoles.of(effect(0, 0, 0, 0, 6, 0), "car", "+z").role());
    }

    @Test
    void 升降与平着挪() {
        assertEquals("lift", TestedRoles.of(effect(0.1, 1.5, 0, 0, 0, 0), "airship", "").role());
        assertEquals("descend", TestedRoles.of(effect(0, -0.8, 0, 0, 0, 0), "airship", "").role());
        TestedRoles.Verdict first = TestedRoles.of(effect(0.2, 0, -2.4, 0, 1, 0), "airship", "");
        assertEquals("power", first.role());
        assertEquals("-z", first.forward(), "车头没定时按这一条定");
        assertEquals("reverse", TestedRoles.of(effect(0, 0, 1.2, 0, 0, 0), "airship", "-z").role());
        assertEquals("none", TestedRoles.of(effect(1.2, 0, 0, 0, 0, 0), "airship", "-z").role(), "往侧面挪认不出");
    }

    @Test
    void 车头定了才分俯仰横滚() {
        // 车头 +z：绕 x 轴转负的是抬头，绕 z 轴转正的是向右横滚。
        assertEquals("pitch_up", TestedRoles.of(effect(0, 0, 0, -6, 0, 0), "airship", "+z").role());
        assertEquals("pitch_down", TestedRoles.of(effect(0, 0, 0, 6, 0, 0), "airship", "+z").role());
        assertEquals("roll_right", TestedRoles.of(effect(0, 0, 0, 0, 0, 6), "airship", "+z").role());
        assertEquals("none", TestedRoles.of(effect(0, 0, 0, 6, 0, 0), "airship", "").role());
    }

    @Test
    void 太小是看不出动() {
        assertEquals("none", TestedRoles.of(effect(0.1, 0.1, 0.1, 1, 1, 1), "airship", "").role());
    }
}
