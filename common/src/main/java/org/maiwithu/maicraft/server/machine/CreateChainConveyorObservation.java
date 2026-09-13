// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.core.integration.create.transmission.ChainConveyorBridge;
import org.maiwithu.maicraft.network.ServerOperationException;

/** The local endpoint is already authorized; optional peer reads retain ordinary native access checks. */
final class CreateChainConveyorObservation {
    private CreateChainConveyorObservation() {}
    static void inspect(ServerPlayer player, BlockEntity entity, JsonObject create) {
        if (!ChainConveyorBridge.isConveyor(entity)) return;
        var limits = ChainConveyorBridge.limits(player.serverLevel());
        var connections = ChainConveyorBridge.connections(entity);
        JsonObject result = new JsonObject(); create.add("chain_conveyor", result);
        result.addProperty("maximum_length", limits.maximumLength()); result.addProperty("maximum_connections", limits.maximumConnections());
        result.addProperty("connection_count", connections.size()); result.addProperty("truncated", connections.size() > 128);
        result.addProperty("provenance", "Create.ChainConveyorBlockEntity.connections");
        JsonArray links = new JsonArray(); result.add("connections", links);
        for (BlockPos offset : connections.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).limit(128).toList()) {
            BlockPos peer = entity.getBlockPos().offset(offset);
            JsonObject link = new JsonObject(); link.add("offset", point(offset)); link.add("position", point(peer));
            link.addProperty("native_link_registered", true); link.addProperty("chain_cost", ChainConveyorBridge.linkCost(offset));
            link.addProperty("peer_loaded", player.serverLevel().isLoaded(peer));
            if (player.serverLevel().isLoaded(peer)) {
                try {
                    BlockEntity observedPeer = ServerAccess.check(player, peer, false);
                    boolean reciprocal = ChainConveyorBridge.isConveyor(observedPeer)
                            && ChainConveyorBridge.connections(observedPeer).contains(entity.getBlockPos().subtract(peer));
                    link.addProperty("peer_read_status", "observed"); link.addProperty("bidirectional", reciprocal);
                } catch (ServerOperationException denied) { link.addProperty("peer_read_status", denied.code()); }
            } else link.addProperty("peer_read_status", "unloaded");
            links.add(link);
        }
        result.addProperty("flow_verified", false); result.addProperty("production_verified", false);
    }
    private static JsonObject point(BlockPos value) {
        JsonObject result = new JsonObject(); result.addProperty("x", value.getX()); result.addProperty("y", value.getY()); result.addProperty("z", value.getZ()); return result;
    }
}
