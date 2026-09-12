// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonObject;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;

/** One RPC may operate through its actual open native menu; the scope cannot authorise another machine. */
public final class ServerMenuAccess {
    private record Binding(ServerPlayer player, BlockPos position, BlockEntity entity, AbstractContainerMenu menu) {}
    private static final ThreadLocal<Binding> ACTIVE = new ThreadLocal<>();
    private ServerMenuAccess() {}

    public static <T> T execute(ServerPlayer player, JsonObject body, Supplier<T> operation) {
        if (!body.has("container_id")) return operation.get();
        if (!player.getServer().isSameThread()) throw ServerAccess.denied("wrong_thread", "Native menu binding requires the server thread");
        BlockPos position = ServerAccess.position(body.getAsJsonObject("position"));
        int id = ServerAccess.integer(body,"container_id",1,Integer.MAX_VALUE);
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu || menu.containerId != id || !menu.getCarried().isEmpty()
                || !player.serverLevel().isLoaded(position) || !menu.stillValid(player))
            throw ServerAccess.denied("menu_changed", "The requested native menu is no longer open and valid");
        BlockEntity entity = player.serverLevel().getBlockEntity(position);
        if (entity == null || !targets(menu, entity, body)) throw ServerAccess.denied("menu_target_mismatch", "The open menu belongs to another machine");
        Binding previous = ACTIVE.get(), binding = new Binding(player, position.immutable(), entity, menu);
        ACTIVE.set(binding);
        try {
            T result = operation.get();
            if (!permits(player, position)) throw new IllegalStateException("Native menu context changed while executing; reconcile the operation");
            menu.broadcastChanges();
            return result;
        } finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }

    static boolean permits(ServerPlayer player, BlockPos position) {
        Binding bound = ACTIVE.get();
        return bound != null && bound.player == player && bound.position.equals(position) && player.containerMenu == bound.menu
                && bound.menu != player.inventoryMenu && bound.menu.getCarried().isEmpty() && bound.menu.stillValid(player)
                && player.serverLevel().isLoaded(position) && player.serverLevel().getBlockEntity(position) == bound.entity;
    }

    private static boolean targets(AbstractContainerMenu menu, BlockEntity entity, JsonObject body) {
        for (var slot : menu.slots) {
            if (slot.container == entity) return true;
            if (slot.container instanceof net.minecraft.world.CompoundContainer compound
                    && entity instanceof net.minecraft.world.Container container && compound.contains(container)) return true;
        }
        if (NativeApi.is(menu, "mekanism.common.inventory.container.tile.MekanismTileContainer"))
            return NativeApi.call(menu, "mekanism.common.inventory.container.tile.MekanismTileContainer", "getTileEntity") == entity;
        if (NativeApi.is(menu, "appeng.menu.AEBaseMenu")) {
            Object target = NativeApi.call(menu, "appeng.menu.AEBaseMenu", "getTarget");
            Object expected = NativeApi.is(entity, "appeng.api.parts.IPartHost")
                    ? body.has("side") ? NativeApi.call(entity, "appeng.api.parts.IPartHost", "getPart", ServerAccess.side(body)) : null : entity;
            return expected != null && target == expected;
        }
        return false;
    }
}
