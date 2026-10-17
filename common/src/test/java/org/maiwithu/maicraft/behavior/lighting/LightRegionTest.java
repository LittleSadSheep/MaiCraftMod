// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 照明的范围：radius 圆内取列；不给 radius 沿能站的格泛洪连通空间，只数已加载的，边界没加载先停下。 */
class LightRegionTest {

    private static BlockState STONE;

    @BeforeAll
    static void bootMinecraft() {
        // 方块注册表要等引导完才能碰：STONE 这些静态字段在这里才取。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        STONE = Blocks.STONE.defaultBlockState();
    }

    private static LightRegion.Flood flooded(LightingFakes.FakeWorld world, BlockPos start) {
        LightRegion.Flood flood = new LightRegion.Flood(world, start);
        for (int i = 0; i < 100 && !flood.tick(); i++) { }
        return flood;
    }

    /** 一格能站：脚下结实，脚与头是空气。 */
    private static void room(LightingFakes.FakeWorld world, int fromX, int toX, int fromZ, int toZ, int floorY) {
        world.floor(STONE, floorY, fromX, toX, fromZ, toZ);
    }

    @Test
    void 圆内取列_圆外不要() {
        List<BlockPos> columns = LightRegion.circleColumns(new BlockPos(0, 64, 0), 2);
        Set<BlockPos> seen = Set.copyOf(columns);
        assertTrue(seen.contains(new BlockPos(0, 64, 0)));
        assertTrue(seen.contains(new BlockPos(2, 64, 0)));
        assertTrue(seen.contains(new BlockPos(1, 64, 1)));
        assertFalse(seen.contains(new BlockPos(3, 64, 0)));
    }

    @Test
    void 一间屋连成一片() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        room(world, -2, 2, -2, 2, 63);
        LightRegion.Flood flood = flooded(world, new BlockPos(0, 64, 0));
        assertTrue(flood.complete());
        assertEquals(25, flood.columns().size());
        // 房间墙外没加载：如实记成边界，不冒充量完了整片天。
        assertTrue(flood.unloadedFrontier().contains(new BlockPos(3, 64, 0)));
    }

    @Test
    void 隔着墙的另一间屋不进来_走过一格台阶算连通() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        room(world, -3, -1, -1, 1, 63);
        // 一格宽的走廊接到第二间屋，走廊口高一级台阶。
        world.floor(STONE, 63, 0, 1, 0, 0);
        room(world, 2, 4, -1, 1, 63);
        LightRegion.Flood flood = flooded(world, new BlockPos(-2, 64, 0));
        List<BlockPos> columns = flood.columns();
        assertTrue(columns.contains(new BlockPos(3, 64, 0)), "第二间屋从走廊连得着");
        assertEquals(3 * 3 + 2 + 3 * 3, columns.size());
    }

    @Test
    void 没加载的边界停下来_不穿过去() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        room(world, -1, 0, -1, 0, 63);
        // (2, 63, 0) 是能站的格，但那一列没登记加载。
        world.put(new BlockPos(1, 63, 0), STONE);
        world.load(new BlockPos(1, 64, 1));
        LightRegion.Flood flood = flooded(world, new BlockPos(0, 64, 0));
        assertFalse(flood.columns().contains(new BlockPos(2, 64, 0)));
        assertTrue(flood.unloadedFrontier().contains(new BlockPos(1, 64, 0)),
                "往东一步的格没加载，记进边界");
    }

    @Test
    void 泛洪到上限就收住() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        room(world, -40, 40, -40, 40, 63);
        LightRegion.Flood flood = flooded(world, new BlockPos(0, 64, 0));
        assertTrue(flood.capped());
        assertTrue(flood.columns().size() <= LightRegion.MAX_FLOOD_COLUMNS);
    }

    @Test
    void 起点没加载_先记边界() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        LightRegion.Flood flood = new LightRegion.Flood(world, new BlockPos(0, 64, 0));
        assertTrue(flood.complete());
        assertTrue(flood.columns().isEmpty());
        assertTrue(flood.unloadedFrontier().contains(new BlockPos(0, 64, 0)));
    }

    @Test
    void 走完的方向用得到_竖向一格幅度() {
        // 竖向泛洪只在上下各一格：隔两格高的平台不连。
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        room(world, -1, 0, -1, 0, 63);
        world.floor(STONE, 66, 1, 1, 0, 0);
        world.floor(STONE, 66, 2, 3, 0, 0);
        LightRegion.Flood flood = flooded(world, new BlockPos(0, 64, 0));
        assertFalse(flood.columns().contains(new BlockPos(2, 67, 0)), "高两级的地面上不来");
    }

    @Test
    void 竖向台阶方向用得到() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        room(world, -1, 0, -1, 0, 63);
        world.floor(STONE, 64, 1, 2, 0, 0);
        LightRegion.Flood flood = flooded(world, new BlockPos(0, 64, 0));
        assertTrue(flood.columns().contains(new BlockPos(2, 65, 0)), "一级台阶走得上去");
    }
}
