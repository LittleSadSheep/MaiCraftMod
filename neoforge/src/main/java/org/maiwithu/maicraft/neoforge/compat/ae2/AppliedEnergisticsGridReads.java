// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.Locale;
import java.util.Optional;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.pathing.IPathingService;
import appeng.api.stacks.AEKey;
import appeng.me.service.PathingService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import org.maiwithu.maicraft.server.compat.ae2.Ae2GridReads;

/**
 * 在服务端读 ME 网络的读写端：找到这一格上的网格节点（线缆格取中间的线缆、设备取任一面），
 * 从它所在那张网的寻路、能量、存储、合成服务里读汇总。存储只数有几种、一共多少，不列是什么。只翻译，不判断。
 */
public final class AppliedEnergisticsGridReads implements Ae2GridReads {

    @Override public Optional<GridSummary> summarize(ServerLevel level, BlockPos at) {
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, at);
        if (host == null) return Optional.empty();
        IGridNode node = nodeOf(host);
        if (node == null || node.getGrid() == null) return Optional.empty();
        IGrid grid = node.getGrid();
        IPathingService pathing = grid.getPathingService();
        String adHocProblem = pathing instanceof PathingService service && service.getAdHocNetworkError() != null
                ? service.getAdHocNetworkError().name().toLowerCase(Locale.ROOT) : "";
        long amount = 0;
        int types = 0;
        for (var entry : grid.getStorageService().getCachedInventory()) {
            AEKey key = entry.getKey();
            if (key == null) continue;
            types++;
            amount += entry.getLongValue();
        }
        int busy = 0;
        int cpus = 0;
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            cpus++;
            if (cpu.isBusy()) busy++;
        }
        return Optional.of(new GridSummary(Integer.toHexString(System.identityHashCode(grid)), grid.size(),
                controllerOf(pathing), pathing.isNetworkBooting(),
                pathing.getChannelMode().name().toLowerCase(Locale.ROOT), pathing.getUsedChannels(),
                pathing.getChannelMode().getAdHocNetworkChannels(), adHocProblem,
                grid.getEnergyService().isNetworkPowered(), grid.getEnergyService().getStoredPower(),
                grid.getEnergyService().getMaxStoredPower(), types, amount, cpus, busy));
    }

    // 线缆格的中间那根线缆是主节点；设备方块每一面多半是同一个节点，取第一面有的。
    private static IGridNode nodeOf(IInWorldGridNodeHost host) {
        IGridNode center = host.getGridNode(null);
        if (center != null) return center;
        for (Direction side : Direction.values()) {
            IGridNode node = host.getGridNode(side);
            if (node != null) return node;
        }
        return null;
    }

    private static String controllerOf(IPathingService pathing) {
        return switch (pathing.getControllerState()) {
            case NO_CONTROLLER -> "none";
            case CONTROLLER_ONLINE -> "online";
            case CONTROLLER_CONFLICT -> "conflict";
        };
    }
}
