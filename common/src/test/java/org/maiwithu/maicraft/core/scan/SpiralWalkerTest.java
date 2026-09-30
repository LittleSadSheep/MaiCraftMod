// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.scan;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/** 锁定螺旋步进的转向次序、边长增长与落点合成；搜索三任务共享同一实现。 */
public final class SpiralWalkerTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        walksGrowingSquareSpiral();
        offsetsExposeGridCellsForScopeChecks();
        gridScalesOffsetsAndKeepsCallerHeight();
        rejectsNonPositiveGrid();
        System.out.println("SpiralWalkerTest: passed");
    }

    private static void walksGrowingSquareSpiral() {
        var spiral = new SpiralWalker(new BlockPos(100, 64, 200), 1);
        int[][] expected = {
                {1, 0}, {1, 1}, {0, 1}, {-1, 1},          // 边长 1：东、南（+z）转向后 west 边翻倍
                {-1, 0}, {-1, -1},                         // 南边走完，边长增为 2
                {0, -1}, {1, -1}, {2, -1},                 // 北边边长 2
                {2, 0}, {2, 1}, {2, 2},                    // 东边边长 2 走完转南，南边增为 3
        };
        for (int[] step : expected) {
            BlockPos point = spiral.next(64);
            check(point.getX() == 100 + step[0] && point.getZ() == 200 + step[1],
                    "第 " + step[0] + "," + step[1] + " 步应落在方形螺旋的对应格");
        }
    }

    private static void offsetsExposeGridCellsForScopeChecks() {
        var spiral = new SpiralWalker(BlockPos.ZERO, 16);
        spiral.next(64);
        check(spiral.offsetX() == 1 && spiral.offsetZ() == 0, "首步格偏移应为 +1,0");
        BlockPos point = spiral.next(70);
        check(point.getX() == 16 && point.getZ() == 16 && point.getY() == 70,
                "第二步落点为 1,1 格乘网格，高度用调用方传入值");
    }

    private static void gridScalesOffsetsAndKeepsCallerHeight() {
        var spiral = new SpiralWalker(new BlockPos(-5, 0, 9), 3);
        for (int i = 0; i < 5; i++) spiral.next(0);
        BlockPos point = spiral.next(-12);
        check(point.getX() == -5 + spiral.offsetX() * 3
                && point.getZ() == 9 + spiral.offsetZ() * 3
                && point.getY() == -12, "落点恒等于原点加偏移乘网格，高度跟随调用方");
    }

    private static void rejectsNonPositiveGrid() {
        try {
            new SpiralWalker(BlockPos.ZERO, 0);
            throw new AssertionError("网格必须为正数");
        } catch (IllegalArgumentException expected) { }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
