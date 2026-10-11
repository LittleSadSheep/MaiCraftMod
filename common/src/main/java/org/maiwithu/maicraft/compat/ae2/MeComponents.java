// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 在客户端看出哪些 ME 设备连成一张网，纯函数：线缆往哪几面连着是 AE2 画出来的，沿着它走；
 * 设备方块（控制器、驱动器、接口……）和贴着的设备直接相连，和贴着的线缆要看那根线缆有没有朝它连。
 * 这是"看得见的连通"：网络真正的成员与频道以服务端为准，这里只用来分台、判断两端是不是连在一起。
 */
final class MeComponents {

    /** 一张网最多数这么多格：再大的网也够分台用，不让一次查看在客户端走太久。 */
    static final int MAX_CELLS = 8192;

    /** 一片连通的 ME 设备：编号取最小的那一格，同一片每次算出来都一样。 */
    record Component(String id, Set<BlockPos> cells, boolean truncated) {
    }

    private MeComponents() {
    }

    /** 从这一格出发数它连着的整片；这一格不是 ME 设备给空。 */
    static Optional<Component> of(BlockPos start, Function<BlockPos, Optional<Ae2Grid.GridCell>> cellAt) {
        if (cellAt.apply(start).isEmpty()) return Optional.empty();
        Set<BlockPos> seen = new LinkedHashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(start.immutable());
        seen.add(start.immutable());
        boolean truncated = false;
        while (!frontier.isEmpty()) {
            if (seen.size() >= MAX_CELLS) {
                truncated = true;
                break;
            }
            BlockPos at = frontier.poll();
            Ae2Grid.GridCell here = cellAt.apply(at).orElse(null);
            if (here == null) continue;
            for (Direction side : Direction.values()) {
                BlockPos next = at.relative(side);
                if (seen.contains(next)) continue;
                Optional<Ae2Grid.GridCell> there = cellAt.apply(next);
                if (there.isPresent() && linked(here, side, there.get())) {
                    seen.add(next.immutable());
                    frontier.add(next.immutable());
                }
            }
        }
        BlockPos least = seen.stream().min(MeComponents::compare).orElse(start);
        return Optional.of(new Component("me@" + least.getX() + "," + least.getY() + "," + least.getZ(), seen,
                truncated));
    }

    /** 这一格往 side 那一面和那一格连不连：线缆看自己画出来的连接，两块设备贴着就连。 */
    static boolean linked(Ae2Grid.GridCell here, Direction side, Ae2Grid.GridCell there) {
        if (here.cableBus()) return here.links().contains(side);
        if (there.cableBus()) return there.links().contains(side.getOpposite());
        return true;
    }

    private static int compare(BlockPos a, BlockPos b) {
        if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
        if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
        return Integer.compare(a.getZ(), b.getZ());
    }
}
