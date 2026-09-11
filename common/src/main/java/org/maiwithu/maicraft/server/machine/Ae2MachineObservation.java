// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.Ae2Access;

final class Ae2MachineObservation {
    private Ae2MachineObservation() {}

    static void inspect(BlockEntity entity, JsonObject observation) {
        String inWorld = "appeng.api.networking.IInWorldGridNodeHost";
        if (!NativeApi.is(entity, inWorld)) return;
        JsonArray nodes = new JsonArray();
        JsonObject state = new JsonObject();
        state.add("nodes", nodes);
        observation.getAsJsonObject("native").add("ae2", state);
        try {
            for (Direction side : Direction.values()) {
                Object node = NativeApi.call(entity, inWorld, "getGridNode", side);
                if (node == null) continue;
                JsonObject entry = new JsonObject();
                entry.addProperty("side", side.getSerializedName());
                for (String method : new String[]{"isActive", "isPowered", "meetsChannelRequirements", "getUsedChannels", "getMaxChannels"}) {
                    CreateMachineAdapter.scalar(entry, method, NativeApi.call(node, Ae2Access.NODE, method));
                }
                Object grid = NativeApi.call(node, Ae2Access.NODE, "getGrid");
                entry.addProperty("membership", Ae2Access.membership(grid));
                JsonArray connected = new JsonArray();
                for (Object face : (Iterable<?>) NativeApi.call(node, Ae2Access.NODE, "getConnectedSides")) connected.add(face.toString());
                entry.add("connected_sides", connected);
                nodes.add(entry);
            }
            state.addProperty("status", "observed");
            state.addProperty("network_inventory", "requires_accessible_terminal_query");
        } catch (RuntimeException unavailable) {
            state.addProperty("status", "partial");
            observation.getAsJsonArray("unknown").add("ae2:" + unavailable.getClass().getSimpleName());
        }
    }
}
