// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.util.HashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 三格和两格来自通行断面；回放已挖空部分、异种矿物截断、缺地板与到层位后转水平。 */
public final class ProspectTunnelPlanTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BlockPos origin = new BlockPos(8, 80, 8);
        for (Direction heading : Direction.Plane.HORIZONTAL) {
            var down = new ProspectTunnelPlan(origin, heading, 16, 1.8, 128);
            var flat = new ProspectTunnelPlan(origin, heading, 80, 1.8, 128);
            check(down.clearance(1).size() == 3 && flat.clearance(1).size() == 2, "headroom follows the swept player body");
            check(down.length() == 64 && down.foot(64).getY() == 16, "descending passage ends at the target layer");
            check(!down.contains(down.foot(64).below()) && !flat.contains(origin.below()), "floors are outside excavation authority");
            var tall = new ProspectTunnelPlan(origin, heading, 16, 2.8, 128);
            check(tall.clearance(1).size() == 4, "headroom is derived from the body rather than fixed action counts");
            var world = new HashMap<BlockPos, BlockState>();
            for (int step = 1; step <= 3; step++) down.clearance(step).forEach(at -> world.put(at, Blocks.STONE.defaultBlockState()));
            // 先挖通上部原生线段，较深处被铁矿截住；剩余动作只选未清的格，不重放前三次点击。
            for (int step = 1; step <= 2; step++) world.put(down.clearance(step).getFirst(), Blocks.AIR.defaultBlockState());
            BlockPos interruption = down.clearance(3).getFirst(); world.put(interruption, Blocks.IRON_ORE.defaultBlockState());
            var missing = down.obstacles(world::get, 3);
            check(missing.size() == 7 && missing.contains(interruption), "native material interruption remains in the live frontier");
            check(down.walkableEnd(at -> world.getOrDefault(at, Blocks.STONE.defaultBlockState()).isAir(), at -> true) == null,
                    "clearing one row alone does not make stairs walkable");
            for (int step = 1; step <= 2; step++) down.clearance(step).forEach(at -> world.put(at, Blocks.AIR.defaultBlockState()));
            check(down.walkableEnd(at -> world.getOrDefault(at, Blocks.STONE.defaultBlockState()).isAir(), at -> true).equals(down.foot(2)),
                    "walking stops at the first genuinely incomplete cross section");
            check(down.walkableEnd(at -> true, at -> !at.equals(down.foot(1).below())) == null, "missing support cannot be counted as a completed staircase");
        }
        System.out.println("ProspectTunnelPlanTest: geometry and live frontier passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
