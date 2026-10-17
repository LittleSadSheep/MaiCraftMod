// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** 静态受力：升力偏向一头就往另一头倒；关着动力不算推进；升力比重量；配重放在轻的那头才让它倒得慢。 */
class ForceBalanceTest {

    /** 质量 100、重心在原点、重力 10 往下；气球升力 1000（刚好托住）作用在 (0,5,1)，偏向 +z。 */
    private static StructureForceReads.Forces forces(List<StructureForceReads.Push> extra) {
        Vec3 point = new Vec3(0, 5, 1);
        Vec3 lift = new Vec3(0, 1000, 0);
        List<StructureForceReads.Push> pushes = new ArrayList<>(List.of(
                new StructureForceReads.Push("sable:balloon_lift", lift, point.cross(lift), 3)));
        pushes.addAll(extra);
        return new StructureForceReads.Forces(100, Vec3.ZERO, new Vec3(1000, 1000, 1000), new Vec3(0, -10, 0), Vec3.ZERO,
                Vec3.ZERO, true, pushes);
    }

    @Test
    void 升力偏向一头就往另一头倒() {
        ForceBalance.Case unpowered = ForceBalance.of(forces(List.of()), false);
        assertFalse(unpowered.steady());
        assertTrue(unpowered.tiltX() < 0, "绕 x 轴转负的：顶部往 -z 那头倒");
        assertTrue(unpowered.tendency().contains("-z"), unpowered.tendency());
        assertEquals(1.0, ForceBalance.liftRatio(forces(List.of())), 1e-9);
    }

    @Test
    void 关着动力不算推进() {
        Vec3 thrust = new Vec3(0, 0, 200);
        StructureForceReads.Forces powered = forces(List.of(new StructureForceReads.Push("sable:propulsion", thrust,
                new Vec3(0, -2, 0).cross(thrust), 1)));
        assertTrue(ForceBalance.powered(powered));
        assertTrue(ForceBalance.of(powered, true).tiltX() != ForceBalance.of(powered, false).tiltX());
        assertEquals(ForceBalance.of(forces(List.of()), false).tiltX(), ForceBalance.of(powered, false).tiltX(), 1e-9);
    }

    @Test
    void 配重放在轻的那头() {
        List<BlockPos> cells = List.of(new BlockPos(0, 0, 3), new BlockPos(0, 0, -4), new BlockPos(3, 0, 0));
        List<ForceBalance.Ballast> picks = ForceBalance.ballast(forces(List.of()), cells, cell -> 30, 5);
        assertFalse(picks.isEmpty());
        assertEquals(new BlockPos(0, 0, 3), picks.getFirst().local(), "升力在 +z，往 -z 倒：配重放 +z 那头");
        assertTrue(picks.getFirst().after() < picks.getFirst().before());
        assertTrue(picks.stream().noneMatch(pick -> pick.local().equals(new BlockPos(0, 0, -4))), "放 -z 只会更歪");
    }
}
