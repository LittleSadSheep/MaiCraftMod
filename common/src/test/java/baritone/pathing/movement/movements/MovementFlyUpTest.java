// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement.movements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/**
 * 飞上去这一步的落脚处：竖着往上一格格看头顶，从高两格起找这一边头一个站得住的地方；
 * 头顶被挡、让不进、飞到上限还没有都没有这一步。开销比一块块垫方块往上搭省。
 */
class MovementFlyUpTest {

    /** 一个只有实心方块和禁入格的小世界：其余都是空气。 */
    private static final class World implements MovementFlyUp.Cells {
        final Set<BlockPos> solid = new HashSet<>();
        final Set<BlockPos> forbidden = new HashSet<>();

        @Override public boolean open(int x, int y, int z) {
            return !solid.contains(new BlockPos(x, y, z));
        }

        @Override public boolean floor(int x, int y, int z) {
            return solid.contains(new BlockPos(x, y, z));
        }

        @Override public boolean forbidden(int x, int y, int z) {
            return forbidden.contains(new BlockPos(x, y, z));
        }

        // 地面 y=63，东边一格是一根到 y 顶的柱子（顶面就是落脚处）。
        World cliffEast(int top) {
            solid.add(new BlockPos(0, 63, 0));
            for (int y = 63; y <= top; y++) solid.add(new BlockPos(1, y, 0));
            return this;
        }
    }

    @Test
    void 落到这一边头一个站得住的高度() {
        World world = new World().cliffEast(68);
        // 柱顶 y=68，站上去脚在 y=69：比起飞点 y=64 高 5 格。
        assertEquals(5, MovementFlyUp.rise(world, 0, 64, 0, 1, 0, 24));
    }

    @Test
    void 一格高的不飞_走上去就行() {
        World world = new World().cliffEast(64);
        assertEquals(-1, MovementFlyUp.rise(world, 0, 64, 0, 1, 0, 24));
    }

    @Test
    void 头顶被挡或超过上限就没有这一步() {
        World low = new World().cliffEast(68);
        low.solid.add(new BlockPos(0, 67, 0));
        assertEquals(-1, MovementFlyUp.rise(low, 0, 64, 0, 1, 0, 24), "头顶被挡");
        World high = new World().cliffEast(68);
        assertEquals(-1, MovementFlyUp.rise(high, 0, 64, 0, 1, 0, 4), "气只够飞 4 格");
    }

    @Test
    void 落脚处或飞的那一列让不进就不飞() {
        World world = new World().cliffEast(68);
        world.forbidden.add(new BlockPos(1, 69, 0));
        assertEquals(-1, MovementFlyUp.rise(world, 0, 64, 0, 1, 0, 24));
        World column = new World().cliffEast(68);
        column.forbidden.add(new BlockPos(0, 66, 0));
        assertEquals(-1, MovementFlyUp.rise(column, 0, 64, 0, 1, 0, 24));
    }

    @Test
    void 脚下悬空不起飞() {
        World world = new World().cliffEast(68);
        world.solid.remove(new BlockPos(0, 63, 0));
        assertEquals(-1, MovementFlyUp.rise(world, 0, 64, 0, 1, 0, 24));
    }

    @Test
    void 飞五格比垫五块方块省() {
        // 垫一块：跳一格加放方块的罚分（默认 20 刻）加跳的罚分，五块一百多刻；飞五格按每格 2.5 刻算不到一半。
        double fly = MovementFlyUp.cost(5, 2.5);
        assertTrue(fly < 5 * 20, "飞上去 " + fly + " 刻");
    }
}
