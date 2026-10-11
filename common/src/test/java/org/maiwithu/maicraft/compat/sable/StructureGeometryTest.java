// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 物理结构的几何：位姿换算来回对得上、逐格读出碰撞与座位、甲板上能站的格、动没动。 */
class StructureGeometryTest {

    /** 绕竖直轴转 90°：四元数 (0, sin45°, 0, cos45°)。 */
    private static final double HALF = Math.sqrt(0.5);
    private static final SableStructures.Pose TURNED = new SableStructures.Pose(new Vec3(100, 70, 100),
            new double[]{0, HALF, 0, HALF}, new Vec3(10000, 64, 10000), new Vec3(1, 1, 1));
    private static final SableStructures.Pose STILL = new SableStructures.Pose(new Vec3(100, 70, 100),
            new double[]{0, 0, 0, 1}, new Vec3(10000, 64, 10000), new Vec3(1, 1, 1));

    @BeforeAll
    static void 引导方块注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 位姿换算来回对得上() {
        Vec3 local = new Vec3(10003, 65, 10000);
        Vec3 world = TURNED.toWorld(local);
        assertEquals(100, world.x, 1e-6);
        assertEquals(97, world.z, 1e-6, "绕竖直轴转 90° 后 +x 方向朝 -z");
        Vec3 back = TURNED.toLocal(world);
        assertEquals(local.x, back.x, 1e-6);
        assertEquals(local.z, back.z, 1e-6);
    }

    @Test
    void 逐格读出碰撞座位与控制件() {
        Map<BlockPos, BlockState> plot = new HashMap<>();
        for (int x = 0; x < 3; x++) plot.put(new BlockPos(10000 + x, 64, 10000), Blocks.OAK_PLANKS.defaultBlockState());
        plot.put(new BlockPos(10001, 65, 10000), Blocks.OAK_STAIRS.defaultBlockState());
        SableStructures.Structure ship = structure(STILL, STILL, plot);
        StructureGeometry.Scan scan = StructureGeometry.scan(ship, local -> plot.getOrDefault(local, Blocks.AIR.defaultBlockState()));
        assertTrue(scan.complete());
        assertEquals(4, scan.blocks());
        assertFalse(scan.boxes().isEmpty());
        AABB first = StructureGeometry.toWorld(ship.pose(), scan.boxes().getFirst());
        assertTrue(first.minY >= 69.99, "地块里 y=64 那层换到世界在 y=70：" + first);
    }

    @Test
    void 甲板上能站的格_有地板上面两格空着() {
        Map<BlockPos, BlockState> plot = new HashMap<>();
        for (int x = 0; x < 3; x++) plot.put(new BlockPos(10000 + x, 64, 10000), Blocks.OAK_PLANKS.defaultBlockState());
        // 中间那格头顶压着一块：不算能站。
        plot.put(new BlockPos(10001, 66, 10000), Blocks.OAK_PLANKS.defaultBlockState());
        SableStructures.Structure ship = structure(STILL, STILL, plot);
        List<StructureGeometry.Deck> deck = StructureGeometry.deck(ship,
                local -> plot.getOrDefault(local, Blocks.AIR.defaultBlockState()));
        List<BlockPos> cells = deck.stream().map(StructureGeometry.Deck::local).toList();
        assertTrue(cells.contains(new BlockPos(10000, 65, 10000)), cells.toString());
        assertFalse(cells.contains(new BlockPos(10001, 65, 10000)), cells.toString());
        assertEquals(71, deck.getFirst().world().y, 1e-6, "站上去脚底在世界 y=71");
    }

    @Test
    void 挪了或转了就是在动() {
        SableStructures.Pose moved = new SableStructures.Pose(new Vec3(100.5, 70, 100), new double[]{0, 0, 0, 1},
                new Vec3(10000, 64, 10000), new Vec3(1, 1, 1));
        assertTrue(StructureGeometry.moving(structure(moved, STILL, Map.of())));
        assertTrue(StructureGeometry.moving(structure(TURNED, STILL, Map.of())));
        assertFalse(StructureGeometry.moving(structure(STILL, STILL, Map.of())));
    }

    private static SableStructures.Structure structure(SableStructures.Pose pose, SableStructures.Pose last,
            Map<BlockPos, BlockState> plot) {
        return new SableStructures.Structure("ship", "", new AABB(98, 68, 98, 104, 74, 102), pose, last,
                new BlockPos(10000, 64, 10000), new BlockPos(10002, 66, 10000), false);
    }
}
