// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.Direction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下界门框的几何与验活：整圈门格、缺哪几格、框齐可点、门面出现；
 * 残缺的框从一块黑曜石也认得出来。
 */
class NetherPortalFrameTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockPos ORIGIN = new BlockPos(10, 64, 10);

    /** 一扇齐整的门：标准 2×3 内框的十块黑曜石全摆上。 */
    private static Map<Long, BlockState> fullFrame() {
        NetherPortalFrame frame = new NetherPortalFrame(ORIGIN,
                Direction.Axis.X, 2, 3);
        Map<Long, BlockState> world = new HashMap<>();
        for (BlockPos cell : frame.frame()) {
            world.put(cell.asLong(), Blocks.OBSIDIAN.defaultBlockState());
        }
        for (BlockPos cell : frame.interior()) {
            world.put(cell.asLong(), Blocks.AIR.defaultBlockState());
        }
        return world;
    }

    private static Function<BlockPos, Optional<BlockState>> readOf(Map<Long, BlockState> world) {
        return cell -> Optional.ofNullable(world.get(cell.asLong()));
    }

    @Test
    void completeFrameIsReadyAndLightsUpToActive() {
        NetherPortalFrame frame = new NetherPortalFrame(ORIGIN, Direction.Axis.X, 2, 3);
        Map<Long, BlockState> world = fullFrame();
        assertTrue(frame.ready(readOf(world)), "框齐内侧空，可以点火");
        assertEquals(0, frame.missing(readOf(world)).size());

        for (BlockPos cell : frame.interior()) {
            world.put(cell.asLong(), Blocks.NETHER_PORTAL.defaultBlockState());
        }
        assertTrue(frame.active(readOf(world)), "内侧长满门方块就是活了");
    }

    @Test
    void missingCellsNameExactlyWhatIsBroken() {
        NetherPortalFrame frame = new NetherPortalFrame(ORIGIN, Direction.Axis.X, 2, 3);
        Map<Long, BlockState> world = fullFrame();
        BlockPos broken = frame.cell(0, -1);
        world.remove(broken.asLong());
        assertEquals(List.of(broken), frame.missing(readOf(world)), "缺的就是那一格");
        assertFalse(frame.ready(readOf(world)));
    }

    @Test
    void sketchRecognizesABrokenFrameFromOneObsidian() {
        NetherPortalFrame frame = new NetherPortalFrame(ORIGIN, Direction.Axis.X, 2, 3);
        Map<Long, BlockState> world = fullFrame();
        // 拆掉两块：框残了，但从剩下的黑曜石仍认得出是这扇框。
        BlockPos brokenTop = frame.cell(1, 3);
        BlockPos brokenSide = frame.cell(-1, 1);
        world.remove(brokenTop.asLong());
        world.remove(brokenSide.asLong());

        Optional<NetherPortalFrame> sketched = NetherPortalFrame.sketchAround(readOf(world), frame.cell(0, -1));
        assertTrue(sketched.isPresent(), "残缺的框也认得出来");
        assertEquals(frame.origin(), sketched.get().origin());
        assertEquals(2, sketched.get().missing(readOf(world)).size(), "缺两格说两格");
    }

    @Test
    void observeOnlyAcceptsAReadyFrame() {
        NetherPortalFrame frame = new NetherPortalFrame(ORIGIN, Direction.Axis.X, 2, 3);
        Map<Long, BlockState> world = fullFrame();
        BlockPos seed = frame.cell(0, 0);
        assertTrue(NetherPortalFrame.observe(readOf(world), seed, Direction.Axis.X).isPresent(),
                "齐框认得出");
        world.remove(frame.cell(2, -1).asLong());
        assertFalse(NetherPortalFrame.observe(readOf(world), seed, Direction.Axis.X).isPresent(),
                "缺格的框从 observe 认不出（那是补框勘察的事）");
    }
}
