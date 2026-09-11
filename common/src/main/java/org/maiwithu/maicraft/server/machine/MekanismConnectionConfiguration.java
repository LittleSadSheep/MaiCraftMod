// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** The same connection callbacks as the native configurator, applied to an explicit semantic side/mode. */
final class MekanismConnectionConfiguration {
    private static final String TILE = "mekanism.common.tile.transmitter.TileEntityTransmitter";
    private static final String TRANSMITTER = "mekanism.common.content.network.transmitter.Transmitter";
    private static final String MODE = "mekanism.common.lib.transmitter.ConnectionType";
    private static final Set<String> MEDIA = Set.of("ITEM", "FLUID", "CHEMICAL", "ENERGY");
    private MekanismConnectionConfiguration() {}

    static JsonObject configure(ServerPlayer player, BlockEntity entity, JsonObject body) {
        if (!NativeApi.is(entity, TILE)) throw ServerAccess.denied("unsupported", "Target is not a Mekanism transmitter");
        Direction side = ServerAccess.side(body);
        Object requested;
        try { requested = NativeApi.enumValue(MODE, ServerAccess.text(body, "mode")); }
        catch (IllegalArgumentException invalid) { throw ServerAccess.denied("invalid_argument", "Connection mode must be normal, push, pull or none"); }
        Object transmitter = NativeApi.call(entity, TILE, "getTransmitter");
        Collection<?> media = (Collection<?>) NativeApi.call(transmitter, TRANSMITTER, "getSupportedTransmissionTypes");
        if (media.stream().noneMatch(value -> value instanceof Enum<?> type && MEDIA.contains(type.name()))) {
            throw ServerAccess.denied("unsupported", "This transmitter does not expose item, fluid, chemical or energy modes");
        }
        Object before = NativeApi.call(transmitter, TRANSMITTER, "getConnectionTypeRaw", side);
        if (before != requested) {
            requireConfigurator(player);
            for (Direction neighbor : Direction.values()) {
                if (!player.serverLevel().isLoaded(entity.getBlockPos().relative(neighbor))) {
                    throw ServerAccess.denied("unloaded", "Native connection refresh requires loaded adjacent blocks");
                }
            }
            NativeApi.call(transmitter, TRANSMITTER, "setConnectionTypeRaw", side, requested);
            NativeApi.call(transmitter, TRANSMITTER, "onModeChange", side);
            NativeApi.call(transmitter, TRANSMITTER, "refreshConnections");
            NativeApi.call(transmitter, TRANSMITTER, "notifyTileChange");
            NativeApi.call(entity, "mekanism.common.tile.base.TileEntityUpdateable", "markForSave");
            NativeApi.call(entity, "mekanism.common.tile.base.TileEntityUpdateable", "sendUpdatePacket");
        }
        Object raw = NativeApi.call(transmitter, TRANSMITTER, "getConnectionTypeRaw", side);
        Object effective = NativeApi.call(transmitter, TRANSMITTER, "getConnectionType", side);
        JsonObject result = new JsonObject();
        result.addProperty("side", side.getSerializedName());
        result.addProperty("requested_mode", name(requested));
        result.addProperty("configured_mode", name(raw));
        result.addProperty("mode", name(effective));
        result.addProperty("verified_configuration", raw == requested && effective == requested);
        result.addProperty("status", before == raw ? "no_change" : raw == requested && effective == requested ? "applied" : "partial");
        result.addProperty("flow_verified", false);
        return result;
    }

    static void inspect(BlockEntity entity, JsonObject observation) {
        if (!NativeApi.is(entity, TILE)) return;
        JsonObject state = new JsonObject();
        observation.getAsJsonObject("native").add("mekanism_transmitter", state);
        JsonArray sides = new JsonArray(); state.add("connections", sides);
        try {
            Object transmitter = NativeApi.call(entity, TILE, "getTransmitter");
            for (Direction side : Direction.values()) {
                JsonObject entry = new JsonObject(); entry.addProperty("side", side.getSerializedName());
                entry.addProperty("configured_mode", name(NativeApi.call(transmitter, TRANSMITTER, "getConnectionTypeRaw", side)));
                entry.addProperty("mode", name(NativeApi.call(transmitter, TRANSMITTER, "getConnectionType", side)));
                sides.add(entry);
            }
            state.addProperty("status", "observed");
        } catch (RuntimeException unavailable) {
            state.addProperty("status", "partial"); observation.getAsJsonArray("unknown").add("mekanism_transmitter_modes");
        }
    }

    static JsonObject configuration(BlockEntity entity, JsonObject body) {
        if (!NativeApi.is(entity, TILE)) return ServerMachineConfiguration.unknown("not_a_mekanism_transmitter");
        Direction side = ServerAccess.side(body);
        Object expected = NativeApi.enumValue(MODE, ServerAccess.text(body, "mode"));
        Object transmitter = NativeApi.call(entity, TILE, "getTransmitter");
        Object raw = NativeApi.call(transmitter, TRANSMITTER, "getConnectionTypeRaw", side);
        Object effective = NativeApi.call(transmitter, TRANSMITTER, "getConnectionType", side);
        JsonObject result = new JsonObject(); result.addProperty("side", side.getSerializedName());
        result.addProperty("configured_mode", name(raw)); result.addProperty("mode", name(effective));
        result.addProperty("verified_configuration", raw == expected && effective == expected); return result;
    }

    private static void requireConfigurator(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && NativeApi.is(stack.getItem(), "mekanism.common.item.ItemConfigurator")) return;
        }
        throw ServerAccess.denied("requires_tool", "A real mekanism:configurator is required to change transmitter connections");
    }

    private static String name(Object value) { return ((Enum<?>) value).name().toLowerCase(Locale.ROOT); }
}
