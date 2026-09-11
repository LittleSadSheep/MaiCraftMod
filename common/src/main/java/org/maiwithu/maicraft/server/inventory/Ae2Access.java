// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

public record Ae2Access(Object host, Object node, Object grid, Object inventory, Object energy, Object actionSource,
                        String membership) {
    public static final String NODE = "appeng.api.networking.IGridNode";
    public static final String GRID = "appeng.api.networking.IGrid";
    public static final String ITEM = "appeng.api.stacks.AEItemKey";
    public static final String STORAGE = "appeng.api.storage.MEStorage";
    public static final String TERMINAL = "appeng.api.storage.ITerminalHost";
    private static final Map<Object, String> GRID_IDS = new WeakHashMap<>();

    public static Ae2Access terminal(ServerPlayer player, BlockPos pos, Direction side, boolean mutate) {
        BlockEntity entity = ServerAccess.check(player, pos, mutate);
        if (!player.canInteractWithBlock(pos, 0)) throw ServerAccess.denied("out_of_range", "Terminal is outside interaction reach");
        Object host = entity;
        if (NativeApi.is(entity, "appeng.api.parts.IPartHost")) {
            host = NativeApi.call(entity, "appeng.api.parts.IPartHost", "getPart", side);
        }
        if (!NativeApi.is(host, TERMINAL) || !NativeApi.is(host, "appeng.api.networking.security.IActionHost")) {
            throw ServerAccess.denied("unsupported", "An accessible physical AE2 terminal is required");
        }
        Object node = NativeApi.call(host, "appeng.api.networking.security.IActionHost", "getActionableNode");
        Object link = NativeApi.call(host, TERMINAL, "getLinkStatus");
        if (node == null || !NativeApi.truth(NativeApi.call(node, NODE, "isActive"))
                || !NativeApi.truth(NativeApi.call(link, "appeng.api.storage.ILinkStatus", "connected"))) {
            throw ServerAccess.denied("network_offline", "AE2 terminal has no powered active network");
        }
        Object grid = NativeApi.call(node, NODE, "getGrid");
        Object source = NativeApi.call(null, "appeng.api.networking.security.IActionSource", "ofPlayer", player, host);
        return new Ae2Access(host, node, grid, NativeApi.call(host, TERMINAL, "getInventory"),
                NativeApi.call(grid, GRID, "getEnergyService"), source, membership(grid));
    }

    public static String membership(Object grid) {
        return GRID_IDS.computeIfAbsent(grid, ignored -> "ae2:" + UUID.randomUUID());
    }

    public Object cachedInventory() {
        Object service = NativeApi.call(grid, GRID, "getStorageService");
        return NativeApi.call(service, "appeng.api.networking.storage.IStorageService", "getCachedInventory");
    }

    public long powered(Object key, long amount, boolean insert, boolean simulate) {
        Object action = NativeApi.enumValue("appeng.api.config.Actionable", simulate ? "SIMULATE" : "MODULATE");
        return NativeApi.number(NativeApi.call(null, "appeng.api.storage.StorageHelper",
                insert ? "poweredInsert" : "poweredExtraction", energy, inventory, key, amount, actionSource, action));
    }
}
