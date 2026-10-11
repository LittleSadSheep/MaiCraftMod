// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 空中的路与落点：绕过墙、没看过的格不当空气、只留拐弯处；落点要站得住、上面留净空，目标远就朝它飞一段。 */
class FlightRoutesTest {

    private static final AABB BOUNDS = new AABB(-20, 60, -20, 40, 100, 40);
    private static final Predicate<BlockPos> ALL_LOADED = pos -> true;

    @BeforeAll
    static void 引导方块注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 隔着一堵墙从上面飞过去() {
        Grid grid = new Grid();
        for (int y = 64; y <= 70; y++) {
            for (int z = -5; z <= 5; z++) grid.put(new BlockPos(5, y, z), Blocks.STONE.defaultBlockState());
        }
        Optional<List<BlockPos>> route = FlightRoutes.find(grid, ALL_LOADED, new BlockPos(0, 65, 0),
                new BlockPos(10, 65, 0), BOUNDS, 50_000);
        assertTrue(route.isPresent());
        assertEquals(new BlockPos(0, 65, 0), route.get().getFirst());
        assertEquals(new BlockPos(10, 65, 0), route.get().getLast());
        assertTrue(route.get().stream().noneMatch(cell -> cell.getX() == 5 && cell.getY() <= 70 && Math.abs(cell.getZ()) <= 5),
                "不穿墙：" + route.get());
    }

    @Test
    void 没看过的格不当空气() {
        Predicate<BlockPos> onlyNear = pos -> pos.getX() < 5;
        assertFalse(FlightRoutes.find(new Grid(), onlyNear, new BlockPos(0, 65, 0), new BlockPos(10, 65, 0),
                BOUNDS, 50_000).isPresent());
    }

    @Test
    void 直着的一段只留首尾() {
        List<BlockPos> path = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0),
                new BlockPos(2, 65, 0), new BlockPos(2, 66, 0));
        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0), new BlockPos(2, 66, 0)),
                FlightRoutes.corners(path));
    }

    @Test
    void 落点要站得住上面留净空() {
        Grid grid = new Grid();
        grid.put(new BlockPos(0, 79, 0), Blocks.STONE.defaultBlockState());
        // 头顶往上第二格压着一块：脚上面要空出三格时地面那格不当落点，落到压着的那块顶上。
        grid.put(new BlockPos(0, 83, 0), Blocks.STONE.defaultBlockState());
        assertEquals(Optional.of(new BlockPos(0, 84, 0)), FlightLandings.near(grid, ALL_LOADED, new BlockPos(0, 80, 0), 0, 3));
        assertEquals(Optional.of(new BlockPos(0, 80, 0)), FlightLandings.near(grid, ALL_LOADED, new BlockPos(0, 80, 0), 0, 2));
    }

    @Test
    void 目标太远就朝它的方向飞一段找落点() {
        Grid grid = new Grid();
        for (int x = 0; x <= 200; x++) grid.put(new BlockPos(x, 63, 0), Blocks.STONE.defaultBlockState());
        Optional<BlockPos> stop = FlightLandings.ahead(grid, ALL_LOADED, new BlockPos(0, 64, 0), new BlockPos(200, 64, 0), 64, 2);
        assertTrue(stop.isPresent());
        assertTrue(stop.get().getX() >= 32 && stop.get().getX() <= 64, stop.get().toString());
    }

    /** 小片世界替身：按坐标记方块，没记的当空气。 */
    static final class Grid implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos.immutable(), Blocks.AIR.defaultBlockState());
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override public int getHeight() {
            return 384;
        }

        @Override public int getMinBuildHeight() {
            return -64;
        }
    }
}
