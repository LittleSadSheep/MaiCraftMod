// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.server.ClientRequestReceipt;
import org.maiwithu.maicraft.client.server.ServerAssistClient;

final class ChainConveyorRead {
    private ClientRequestReceipt receipt;
    private BlockPos at;
    private boolean renewed;
    JsonObject snapshot(BlockPos position, String dimension) {
        if (receipt == null) {
            if (!ServerAssistClient.serverSupported("machine.snapshot")) throw new IllegalArgumentException("chain_conveyor_server_snapshot_required");
            JsonObject body = new JsonObject(); JsonArray positions = new JsonArray(); positions.add(point(position));
            body.add("positions", positions); body.add("faces", new JsonArray());
            at = position.immutable(); receipt = ServerAssistClient.submit("machine.snapshot", body, false); return null;
        }
        if (!at.equals(position)) throw new IllegalStateException("chain_conveyor_read_target_changed");
        var state = receipt.snapshot();
        if (state.status() == ClientRequestReceipt.Status.PENDING || state.status() == ClientRequestReceipt.Status.QUEUED) return null;
        if (!renewed && state.code().equals("session_expired") && ServerAssistClient.takeExpiredReadForRefresh(state.requestId())) {
            receipt = null; renewed = true; return null;
        }
        if (state.status() != ClientRequestReceipt.Status.SUCCEEDED) throw new IllegalArgumentException("chain_conveyor_read_" + state.code());
        JsonObject result = state.result(); receipt = null; renewed = false;
        if (!dimension.equals(result.get("dimension").getAsString()) || result.get("truncated").getAsBoolean())
            throw new IllegalArgumentException("chain_conveyor_snapshot_scope_changed");
        for (var raw : result.getAsJsonArray("observations")) {
            var observed = raw.getAsJsonObject();
            if (!point(position).equals(observed.get("position")) || !ChainConveyorBridge.BLOCK_ID.equals(observed.get("block_id").getAsString())
                    || !"server_native".equals(observed.get("provenance").getAsString())) continue;
            JsonObject chain = observed.getAsJsonObject("native").getAsJsonObject("create").getAsJsonObject("chain_conveyor");
            if (chain == null || chain.get("truncated").getAsBoolean()) throw new IllegalArgumentException("chain_conveyor_native_link_page_missing");
            return observed;
        }
        throw new IllegalArgumentException("chain_conveyor_native_endpoint_missing");
    }
    static boolean hasLink(JsonObject observed, BlockPos peer) {
        var chain = observed.getAsJsonObject("native").getAsJsonObject("create").getAsJsonObject("chain_conveyor");
        for (var raw : chain.getAsJsonArray("connections")) {
            var link = raw.getAsJsonObject();
            if (point(peer).equals(link.get("position")) && link.get("native_link_registered").getAsBoolean()) return true;
        }
        return false;
    }
    static boolean peerVerified(JsonObject observed, BlockPos peer) {
        var chain = observed.getAsJsonObject("native").getAsJsonObject("create").getAsJsonObject("chain_conveyor");
        for (var raw : chain.getAsJsonArray("connections")) {
            var link = raw.getAsJsonObject();
            if (point(peer).equals(link.get("position")) && link.has("bidirectional") && link.get("bidirectional").getAsBoolean()
                    && link.has("peer_read_status") && link.get("peer_read_status").getAsString().equals("observed")) return true;
        }
        return false;
    }
    static JsonObject clientSnapshot(net.minecraft.world.level.Level world, BlockPos at) {
        JsonObject result = new JsonObject(); result.add("position", point(at)); result.addProperty("provenance", "client_synchronized");
        JsonArray connections = new JsonArray();
        ChainConveyorBridge.connections(world, at).stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong))
                .limit(128).forEach(offset -> connections.add(point(at.offset(offset))));
        result.add("connections", connections); return result;
    }
    static ChainConveyorBridge.Limits limits(JsonObject observed) {
        var chain = observed.getAsJsonObject("native").getAsJsonObject("create").getAsJsonObject("chain_conveyor");
        return new ChainConveyorBridge.Limits(chain.get("maximum_length").getAsInt(), chain.get("maximum_connections").getAsInt());
    }
    static JsonObject point(BlockPos position) {
        var result = new JsonObject(); result.addProperty("x", position.getX()); result.addProperty("y", position.getY()); result.addProperty("z", position.getZ()); return result;
    }
    void cancel() { if (receipt != null) ServerAssistClient.cancel(receipt.id()); receipt = null; }
}
