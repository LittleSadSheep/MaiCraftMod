// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

/** 倒岩浆前保守圈出可能接触身体的流路；不把这份避险范围当作刷石产物或真实流动顺序的模拟。 */
public final class LavaPlacementSafety {
    private static final int MAX_VISITED = 512;
    private record Flow(BlockPos pos, int remaining) {}
    private LavaPlacementSafety() {}

    public static boolean mayReachBody(Level world, BlockPos source, AABB body) {
        // 岩浆不向上流；站在源格之上的完整台沿能避开这次水平流动，身体边缘仍由实际包围盒判断。
        if (body.minY >= source.getY() + 1.0) return false;
        if (!world.isLoaded(source)) return true;
        // 原版岩浆从八级流量逐步衰减：普通维度每格减二，超热维度减一；落下后重新取得满流量。
        int range = world.dimensionType().ultraWarm() ? 7 : 3;
        int lowest = Mth.floor(body.minY);
        var pending = new ArrayDeque<Flow>();
        Map<Long, Integer> seen = new HashMap<>();
        pending.add(new Flow(source.immutable(), range)); seen.put(source.asLong(), range);
        int visited = 0;
        while (!pending.isEmpty()) {
            if (++visited > MAX_VISITED) return true; // 无法在预算内证明隔开时换站位，不冒险倒桶。
            Flow flow = pending.removeFirst();
            if (body.intersects(new AABB(flow.pos()))) return true;
            if (flow.pos().getY() > lowest && !enqueue(world, pending, seen, flow.pos().below(), range)) return true;
            if (flow.remaining() > 0) for (Direction side : Direction.Plane.HORIZONTAL) {
                if (!enqueue(world, pending, seen, flow.pos().relative(side), flow.remaining() - 1)) return true;
            }
        }
        return false;
    }

    // 同时考虑水平与向下的可能分支，忽略水冷却等有利反应；围挡必须真实存在才能作为身体保护。
    private static boolean enqueue(Level world, ArrayDeque<Flow> pending, Map<Long, Integer> seen,
                                   BlockPos pos, int remaining) {
        if (seen.getOrDefault(pos.asLong(), -1) >= remaining) return true;
        if (!world.isLoaded(pos)) return false;
        var state = world.getBlockState(pos);
        if (!state.canBeReplaced(Fluids.LAVA) && state.getFluidState().isEmpty()
                && !state.getCollisionShape(world, pos).isEmpty()) return true;
        seen.put(pos.asLong(), remaining); pending.addLast(new Flow(pos.immutable(), remaining));
        return true;
    }
}
