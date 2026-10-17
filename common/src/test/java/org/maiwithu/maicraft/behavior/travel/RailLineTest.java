// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 铁轨线路的判断：四种铁轨按轨形接口连通（南北、东西、拐弯、坡道），丁字路口的第三根不算连着，
 * 平轨接斜下一格的平轨这种幻影接缝也不算连着；能不能送到头按动量估：通电的动力轨满速、
 * 没通电的是刹车、下坡回动量、上坡费动量、人推只够滑几格。
 */
class RailLineTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 摆一片现场：一格一个方块状态，没摆的格算没加载。 */
    private static RailLine.ReadsCells cells(Map<BlockPos, BlockState> blocks) {
        return pos -> Optional.ofNullable(blocks.get(pos));
    }

    private static void rail(Map<BlockPos, BlockState> blocks, int x, int y, int z, RailShape shape) {
        blocks.put(new BlockPos(x, y, z), Blocks.RAIL.defaultBlockState()
                .setValue(BlockStateProperties.RAIL_SHAPE, shape));
    }

    private static void powered(Map<BlockPos, BlockState> blocks, int x, int y, int z, RailShape shape, boolean on) {
        blocks.put(new BlockPos(x, y, z), Blocks.POWERED_RAIL.defaultBlockState()
                .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, shape)
                .setValue(BlockStateProperties.POWERED, on));
    }

    private static void detector(Map<BlockPos, BlockState> blocks, int x, int y, int z, RailShape shape) {
        blocks.put(new BlockPos(x, y, z), Blocks.DETECTOR_RAIL.defaultBlockState()
                .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, shape));
    }

    private static void activator(Map<BlockPos, BlockState> blocks, int x, int y, int z, RailShape shape) {
        blocks.put(new BlockPos(x, y, z), Blocks.ACTIVATOR_RAIL.defaultBlockState()
                .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, shape));
    }

    // 南北向直线：逐格接口相接，一条线连得通。
    @Test
    void 南北直线连得通() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int z = 0; z <= 5; z++) rail(blocks, 0, 64, z, RailShape.NORTH_SOUTH);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(0, 64, 5), 1024);
        assertTrue(route.isPresent());
        assertEquals(6, route.get().size());
    }

    // 东西向直线加探测、激活铁轨都算铁轨，一样连得通；北头拐个弯。
    @Test
    void 东西直线加探测与激活铁轨连得通() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        rail(blocks, 0, 64, 0, RailShape.EAST_WEST);
        detector(blocks, 1, 64, 0, RailShape.EAST_WEST);
        activator(blocks, 2, 64, 0, RailShape.EAST_WEST);
        rail(blocks, 3, 64, 0, RailShape.EAST_WEST);
        rail(blocks, 4, 64, 0, RailShape.NORTH_WEST);
        rail(blocks, 4, 64, -1, RailShape.NORTH_SOUTH);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(4, 64, -1), 1024);
        assertTrue(route.isPresent());
        assertEquals(6, route.get().size());
    }

    // 拐弯：南北向经一个西南弯接上东西向。
    @Test
    void 拐弯连起两个方向() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int z = 0; z >= -2; z--) rail(blocks, 0, 64, z, RailShape.NORTH_SOUTH);
        rail(blocks, 0, 64, -3, RailShape.SOUTH_WEST);
        for (int x = -1; x >= -3; x--) rail(blocks, x, 64, -3, RailShape.EAST_WEST);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(-3, 64, -3), 1024);
        assertTrue(route.isPresent());
        assertEquals(7, route.get().size());
    }

    // 丁字路口：横向铁轨的轨形接口不含北边那根，第三根物理相邻的不算连着。
    @Test
    void 丁字路口第三根不连() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        rail(blocks, 0, 64, 0, RailShape.EAST_WEST);
        rail(blocks, 1, 64, 0, RailShape.EAST_WEST);
        rail(blocks, 2, 64, 0, RailShape.EAST_WEST);
        rail(blocks, 1, 64, -1, RailShape.NORTH_SOUTH);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(1, 64, -1), 1024);
        assertTrue(route.isEmpty(), "丁字路口的第三根铁轨不该连着");
    }

    // 坡道：上坡是坡道接口指向上格（坡顶接同轴平轨），下坡是低一格那块坡的高端正好落在坡顶那格；反向走就是下坡。
    @Test
    void 坡道上下都连得通() {
        // 上坡：起点是一块朝东上升的坡，接口在东边上格，坡顶接一块东西向平轨。
        Map<BlockPos, BlockState> up = new HashMap<>();
        rail(up, 0, 64, 0, RailShape.ASCENDING_EAST);
        rail(up, 1, 65, 0, RailShape.EAST_WEST);
        assertTrue(RailLine.route(cells(up), new BlockPos(0, 64, 0), new BlockPos(1, 65, 0), 1024).isPresent());
        // 下坡：平台上的平轨滑到低一格朝西上升的坡上（高端正好落在平台那格）。
        Map<BlockPos, BlockState> down = new HashMap<>();
        rail(down, -1, 64, 0, RailShape.EAST_WEST);
        rail(down, 0, 64, 0, RailShape.EAST_WEST);
        rail(down, 1, 63, 0, RailShape.ASCENDING_WEST);
        assertTrue(RailLine.route(cells(down), new BlockPos(-1, 64, 0), new BlockPos(1, 63, 0), 1024).isPresent());
        // 坡顶接一根南北向的轨：水平方向对不上，不连。
        Map<BlockPos, BlockState> wrongWay = new HashMap<>(down);
        rail(wrongWay, 1, 65, 0, RailShape.NORTH_SOUTH);
        assertTrue(RailLine.route(cells(wrongWay), new BlockPos(0, 64, 0), new BlockPos(1, 65, 0), 1024).isEmpty());
    }

    // 平轨接斜下一格的平轨：轴向对得上但轨形接口互不指向，原版走不过去，不当连通。
    @Test
    void 平轨接斜下一格的平轨不连通() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        rail(blocks, 0, 64, 0, RailShape.NORTH_SOUTH);
        rail(blocks, 1, 63, 0, RailShape.EAST_WEST);
        assertFalse(RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(1, 63, 0), 1024).isPresent(),
                "南北平轨不该连到斜下一格的东西平轨");
        assertFalse(RailLine.route(cells(blocks), new BlockPos(1, 63, 0), new BlockPos(0, 64, 0), 1024).isPresent(),
                "斜下一格的东西平轨也不该连回南北平轨");
    }

    // 起点是通电的动力轨：满速起步，之后几十格平路滑得到头。
    @Test
    void 通电起步长平路送得到头() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        powered(blocks, 0, 64, 0, RailShape.EAST_WEST, true);
        for (int x = 1; x <= 60; x++) rail(blocks, x, 64, 0, RailShape.EAST_WEST);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(60, 64, 0), 1024);
        assertTrue(route.isPresent());
        RailLine.Delivery delivery = RailLine.delivery(route.get(), cells(blocks));
        assertInstanceOf(RailLine.Delivery.Deliverable.class, delivery);
    }

    // 起点没电也没下坡：只靠人推的那几格，平路滑不远，送不到头。
    @Test
    void 没电平路起步滑不远() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = 0; x <= 30; x++) rail(blocks, x, 64, 0, RailShape.EAST_WEST);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(30, 64, 0), 1024);
        assertTrue(route.isPresent());
        RailLine.Delivery delivery = RailLine.delivery(route.get(), cells(blocks));
        RailLine.Delivery.Blocked blocked = assertInstanceOf(RailLine.Delivery.Blocked.class, delivery);
        assertTrue(blocked.reason().contains("滑不到头"), blocked.reason());
    }

    // 人推起步接一格通电的动力轨：推那几格够到动力轨，之后满速送到头。
    @Test
    void 推车起步接上动力轨送得到头() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = 0; x <= 3; x++) rail(blocks, x, 64, 0, RailShape.EAST_WEST);
        powered(blocks, 4, 64, 0, RailShape.EAST_WEST, true);
        for (int x = 5; x <= 40; x++) rail(blocks, x, 64, 0, RailShape.EAST_WEST);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(40, 64, 0), 1024);
        assertTrue(route.isPresent());
        assertInstanceOf(RailLine.Delivery.Deliverable.class, RailLine.delivery(route.get(), cells(blocks)));
    }

    // 线路中间有一格没通电的动力轨：车到那里会被刹停，送不到头。
    @Test
    void 中途没电动力轨刹停送不到() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        powered(blocks, 0, 64, 0, RailShape.EAST_WEST, true);
        for (int x = 1; x <= 10; x++) rail(blocks, x, 64, 0, RailShape.EAST_WEST);
        powered(blocks, 11, 64, 0, RailShape.EAST_WEST, false);
        for (int x = 12; x <= 20; x++) rail(blocks, x, 64, 0, RailShape.EAST_WEST);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(20, 64, 0), 1024);
        assertTrue(route.isPresent());
        RailLine.Delivery delivery = RailLine.delivery(route.get(), cells(blocks));
        RailLine.Delivery.Blocked blocked = assertInstanceOf(RailLine.Delivery.Blocked.class, delivery);
        assertTrue(blocked.reason().contains("刹停"), blocked.reason());
    }

    // 终点正好是一格没通电的动力轨：车本来就要停在那里，算送到头。
    @Test
    void 终点是没电动力轨算送到() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        powered(blocks, 0, 64, 0, RailShape.EAST_WEST, true);
        for (int x = 1; x <= 10; x++) rail(blocks, x, 64, 0, RailShape.EAST_WEST);
        powered(blocks, 11, 64, 0, RailShape.EAST_WEST, false);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(11, 64, 0), 1024);
        assertTrue(route.isPresent());
        assertInstanceOf(RailLine.Delivery.Deliverable.class, RailLine.delivery(route.get(), cells(blocks)));
    }

    // 一路下坡：没电也能越滑越快送到头；坡道低端与坡道同层，滑完接一块平轨。
    @Test
    void 一路下坡送得到头() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        rail(blocks, 0, 70, 0, RailShape.EAST_WEST);
        for (int x = 1; x <= 20; x++) rail(blocks, x, 70 - x, 0, RailShape.ASCENDING_WEST);
        rail(blocks, 21, 50, 0, RailShape.EAST_WEST);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 70, 0), new BlockPos(21, 50, 0), 1024);
        assertTrue(route.isPresent());
        assertInstanceOf(RailLine.Delivery.Deliverable.class, RailLine.delivery(route.get(), cells(blocks)));
    }

    // 上坡比平路费动量：没电的短上坡爬不上去。
    @Test
    void 没电爬坡爬不上去() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        rail(blocks, 0, 64, 0, RailShape.ASCENDING_EAST);
        for (int x = 1; x <= 20; x++) rail(blocks, x, 65, 0, RailShape.EAST_WEST);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(20, 65, 0), 1024);
        assertTrue(route.isPresent());
        assertInstanceOf(RailLine.Delivery.Blocked.class, RailLine.delivery(route.get(), cells(blocks)));
    }

    // 途中线路没加载：不下"送不到"的结论，说清是没看清。
    @Test
    void 未加载段说清没看清() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        powered(blocks, 0, 64, 0, RailShape.EAST_WEST, true);
        for (int x = 1; x <= 5; x++) rail(blocks, x, 64, 0, RailShape.EAST_WEST);
        var route = RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(5, 64, 0), 1024);
        assertTrue(route.isPresent());
        RailLine.Delivery delivery = RailLine.delivery(route.get(),
                pos -> pos.getX() > 3 ? Optional.empty() : cells(blocks).stateAt(pos));
        RailLine.Delivery.Blocked blocked = assertInstanceOf(RailLine.Delivery.Blocked.class, delivery);
        assertTrue(blocked.reason().contains("没加载"), blocked.reason());
    }

    // 起点或终点不在铁轨上：找不出线路。
    @Test
    void 终点没有铁轨找不出线路() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int z = 0; z <= 3; z++) rail(blocks, 0, 64, z, RailShape.NORTH_SOUTH);
        assertFalse(RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(5, 64, 5), 1024).isPresent());
    }

    // 不是铁轨的方块摆在线路中间：接不上。
    @Test
    void 线路中间断口接不上() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        rail(blocks, 0, 64, 0, RailShape.NORTH_SOUTH);
        rail(blocks, 0, 64, 1, RailShape.NORTH_SOUTH);
        rail(blocks, 0, 64, 3, RailShape.NORTH_SOUTH);
        assertFalse(RailLine.route(cells(blocks), new BlockPos(0, 64, 0), new BlockPos(0, 64, 3), 1024).isPresent());
    }
}
