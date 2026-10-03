// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/** 弯岸先补成连续岸边和后排站台；整形消耗的岩浆源不能算进留给门框的余量。 */
final class PortalCastingTerrain {
    static final int MINIMUM_REMAINING_SOURCES = 15;
    record Preparation(List<BlockPos> fill, int sourcesReplaced, int remainingLowerBound,
                       boolean reserveRequired, boolean reserveObserved) {
        Map<String, Object> facts() {
            return Map.of("platform_fill", fill.stream().map(NetherPortalCastingLayout::position).toList(),
                    "lava_sources_replaced_by_platform", sourcesReplaced,
                    "minimum_remaining_sources", MINIMUM_REMAINING_SOURCES,
                    "remaining_surface_sources_lower_bound", remainingLowerBound,
                    "reserve_required", reserveRequired, "reserve_observed", reserveObserved,
                    "budget_scope", "observed connected surface sources after planned filling; subsequent native reactions remain unverified");
        }
    }
    private PortalCastingTerrain() {}

    static List<BlockPos> platform(NetherPortalCastingLayout layout) {
        var cells = new ArrayList<BlockPos>();
        for (int behind = 1; behind <= 2; behind++) for (int across = -1; across <= 2; across++)
            cells.add(layout.cell(across, 0, behind));
        return List.copyOf(cells);
    }

    /** 站台上方留出身体和向上倒桶的视线；模具本身保留给后续搭建步骤，不重复拆除。 */
    static List<BlockPos> clearance(NetherPortalCastingLayout layout) {
        var cells = new ArrayList<BlockPos>();
        for (BlockPos floor : platform(layout)) for (int up = 1; up <= 3; up++) {
            BlockPos at = floor.above(up);
            if (!layout.mold().contains(at)) cells.add(at);
        }
        return List.copyOf(cells);
    }

    static boolean sturdy(ClientLevel world, BlockPos at) {
        var state = PortalPreparationSite.read(world, at);
        return state != null && state.getFluidState().isEmpty() && state.isFaceSturdy(world, at, Direction.UP);
    }
    static List<BlockPos> missingPlatform(ClientLevel world, NetherPortalCastingLayout layout) {
        return platform(layout).stream().filter(at -> !sturdy(world, at)).toList();
    }

    static Preparation inspect(ClientLevel world, NetherPortalCastingLayout layout, BlockPos searchOrigin, int radius) {
        var fill = missingPlatform(world, layout);
        int replaced = (int) fill.stream().filter(at -> source(world, at)).count();
        boolean newBank = false;
        for (int across = -1; across <= 2; across++) newBank |= !sturdy(world, layout.cell(across, 0, 1));
        boolean reserve = newBank || replaced > 0;
        // 完整岸边后补一块不消耗岩浆的站台，不额外改变原来能尝试的模板；改造弯岸则保留用户要求的大于十四格余量。
        int remaining = reserve ? remainingSources(world, layout.origin(), searchOrigin, radius, Set.copyOf(fill)) : 0;
        return new Preparation(fill, replaced, remaining, reserve, !reserve || remaining >= MINIMUM_REMAINING_SOURCES);
    }

    private static int remainingSources(ClientLevel world, BlockPos seed, BlockPos origin, int radius, Set<BlockPos> filled) {
        var pending = new ArrayDeque<BlockPos>(); var visited = new HashSet<BlockPos>(); pending.add(seed);
        int remaining = 0;
        while (!pending.isEmpty()) {
            BlockPos at = pending.removeFirst();
            if (!visited.add(at) || at.distSqr(origin) > (double) radius * radius || !source(world, at)) continue;
            if (!filled.contains(at) && ++remaining >= MINIMUM_REMAINING_SOURCES) return remaining;
            // 只数同一表层池中已加载的真实源格；不会把流水、未知深度或将被填掉的格子当成可装桶余量。
            for (Direction side : Direction.Plane.HORIZONTAL) pending.add(at.relative(side));
        }
        return remaining;
    }
    private static boolean source(ClientLevel world, BlockPos at) {
        var state = PortalPreparationSite.read(world, at);
        return state != null && state.is(Blocks.LAVA) && state.getFluidState().isSource();
    }
}
