// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 丢东西挑方向：面前是一格宽的走廊、旁边是开阔地，就朝开阔地丢；平地上照样朝面前丢，不为丢东西转一大圈。 */
class DropSpotTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // 替身世界：y=63 是石头地面，y=64、65 是空气；walls 里的格子在 64、65 两层都是石头。
    private static Map<BlockPos, BlockState> floor() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) world.put(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
        }
        return world;
    }

    private static void wall(Map<BlockPos, BlockState> world, int x, int z) {
        world.put(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState());
        world.put(new BlockPos(x, 65, z), Blocks.STONE.defaultBlockState());
    }

    private static BlockState read(Map<BlockPos, BlockState> world, BlockPos at) {
        if (Math.abs(at.getX()) > 10 || Math.abs(at.getZ()) > 10) return null;
        return world.getOrDefault(at, Blocks.AIR.defaultBlockState());
    }

    @Test
    void aOneWideCorridorAheadSendsTheThrowToTheOpenSide() {
        Map<BlockPos, BlockState> world = floor();
        // 往南（+z）是一格宽的走廊：x=-1 与 x=1 两侧是墙；往东（+x）在走廊口是开阔地。
        for (int z = 1; z <= 10; z++) {
            wall(world, -1, z);
            wall(world, 1, z);
        }
        Vec3 south = new Vec3(0, 0, 1);
        Vec3 chosen = DropSpot.direction(at -> read(world, at), new BlockPos(0, 64, 0), south);
        assertTrue(chosen.z < 0.9, "不朝走廊里丢：" + chosen);
        assertTrue(DropSpot.openness(at -> read(world, at), new BlockPos(0, 64, 0), chosen)
                > DropSpot.openness(at -> read(world, at), new BlockPos(0, 64, 0), south));
    }

    @Test
    void openGroundKeepsTheForwardDirection() {
        Map<BlockPos, BlockState> world = floor();
        Vec3 north = new Vec3(0, 0, -1);
        assertEquals(north, DropSpot.direction(at -> read(world, at), new BlockPos(0, 64, 0), north));
    }
}
