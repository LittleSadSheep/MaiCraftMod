// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** 姿态与接地：朝向按玩家朝向算、俯仰横滚拆得开；底面压着实心地面算接地，探不到的格说不清。 */
class VehicleAttitudeTest {

    private static SableStructures.Pose pose(double x, double y, double z, double w, Vec3 position) {
        return new SableStructures.Pose(position, new double[]{x, y, z, w}, new Vec3(0, 0, 0), new Vec3(1, 1, 1));
    }

    @Test
    void 朝向与俯仰横滚() {
        assertEquals(0, VehicleAttitude.angles(pose(0, 0, 0, 1, Vec3.ZERO)).heading(), 1e-6, "+z 朝南");
        double half = Math.sqrt(0.5);
        // 绕竖轴转 90°：自己的 +z 转到世界 +x（东），玩家朝向 270。
        assertEquals(270, VehicleAttitude.angles(pose(0, half, 0, half, Vec3.ZERO)).heading(), 1e-6);
        double tenDegrees = Math.toRadians(10) / 2;
        VehicleAttitude.Angles pitched = VehicleAttitude.angles(pose(Math.sin(tenDegrees), 0, 0, Math.cos(tenDegrees), Vec3.ZERO));
        assertEquals(10, pitched.pitch(), 1e-6);
        assertEquals(0, pitched.roll(), 1e-6);
        VehicleAttitude.Angles rolled = VehicleAttitude.angles(pose(0, 0, Math.sin(tenDegrees), Math.cos(tenDegrees), Vec3.ZERO));
        assertEquals(10, rolled.roll(), 1e-6);
        assertEquals(0, rolled.pitch(), 1e-6);
    }

    @Test
    void 底面压着地面算接地() {
        // 一格船底在地块 (0,0,0)，位姿放到世界 y=65：底面在 65，正下方 y=64 那格是满格实心。
        List<AABB> hull = List.of(new AABB(0, 0, 0, 1, 1, 1), new AABB(0, 1, 0, 1, 2, 1));
        SableStructures.Pose resting = pose(0, 0, 0, 1, new Vec3(10, 65, 10));
        assertEquals(VehicleAttitude.GROUNDED, VehicleAttitude.contact(resting, hull,
                cell -> cell.getY() == 64 ? 1.0 : -1.0));
        SableStructures.Pose flying = pose(0, 0, 0, 1, new Vec3(10, 70, 10));
        assertEquals(VehicleAttitude.AIRBORNE, VehicleAttitude.contact(flying, hull,
                cell -> cell.getY() == 64 ? 1.0 : -1.0));
        assertEquals(VehicleAttitude.UNKNOWN, VehicleAttitude.contact(flying, hull, cell -> null));
    }

    @Test
    void 底下是水就是泡在水里() {
        List<AABB> hull = List.of(new AABB(0, 0, 0, 1, 1, 1));
        SableStructures.Pose floating = pose(0, 0, 0, 1, new Vec3(10, 62.5, 10));
        assertEquals(true, VehicleAttitude.onWater(floating, hull, cell -> cell.equals(new BlockPos(10, 62, 10))));
    }
}
