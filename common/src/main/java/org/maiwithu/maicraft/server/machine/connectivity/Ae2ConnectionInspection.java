// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import java.util.List;
import java.util.Map;

/** AE2 19: every physical edge and each multipart's internal pass-through is checked. */
final class Ae2ConnectionInspection {
    private static final String HOST = "appeng.api.networking.IInWorldGridNodeHost";
    private static final String NODE = "appeng.api.networking.IGridNode";
    private static final String CONNECTION = "appeng.api.networking.IGridConnection";
    private static final String PROVENANCE = "AE2.IGridNode.getInWorldConnections+IGridConnection.getOtherSide";

    private Ae2ConnectionInspection() {}

    static ConnectionEvidence edge(String medium, BlockEntity from, BlockEntity to) {
        if ((!medium.equals("items") && !medium.equals("fluids") && !medium.equals("energy")) || !NativeApi.present(HOST)) {
            return evidence("unsupported", false, false, "ae2_adapter_requires_grid_medium");
        }
        if (!NativeApi.is(from, HOST) || !NativeApi.is(to, HOST)) {
            return evidence("unsupported", false, false, "endpoint_does_not_expose_in_world_grid_node");
        }
        Direction direction = ServerConnectionInspection.direction(from.getBlockPos(), to.getBlockPos());
        Object a = node(from, direction), b = node(to, direction.getOpposite());
        if (a == null || b == null) return evidence("planned", false, false, "grid_port_missing_or_unexposed");
        Object connection = ((Map<?, ?>) NativeApi.call(a, NODE, "getInWorldConnections")).get(direction);
        Object reverse = ((Map<?, ?>) NativeApi.call(b, NODE, "getInWorldConnections")).get(direction.getOpposite());
        boolean explicit = connection != null && connection == reverse
                && NativeApi.truth(NativeApi.call(connection, CONNECTION, "isInWorld"))
                && NativeApi.call(connection, CONNECTION, "getOtherSide", a) == b
                && NativeApi.call(connection, CONNECTION, "getOtherSide", b) == a;
        if (!explicit) return evidence("planned", false, false, "explicit_grid_edge_absent");
        ConnectionEvidence result = readiness(a, b, "native_grid_edge_present");
        result.details().addProperty("used_channels", (Number) NativeApi.call(connection, CONNECTION, "getUsedChannels"));
        result.details().addProperty("network_resource_access_verified", false);
        return result;
    }

    static ConnectionEvidence transit(BlockEntity entity, Direction incoming, Direction outgoing) {
        if (!NativeApi.is(entity, HOST)) return evidence("unsupported", false, false, "intermediate_grid_host_unavailable");
        Object a = node(entity, incoming), b = node(entity, outgoing);
        if (a == null || b == null) return evidence("planned", false, false, "intermediate_grid_port_missing");
        if (a == b) return readiness(a, b, "same_native_node_pass_through");
        // Same grid alone could be joined elsewhere in the world; require the local internal edge.
        Object raw = NativeApi.call(a, NODE, "getConnections");
        if (!(raw instanceof List<?> connections) || connections.size() > 64) {
            return evidence("unknown", false, false, "intermediate_connection_list_outside_budget");
        }
        for (Object connection : connections) {
            if (!NativeApi.truth(NativeApi.call(connection, CONNECTION, "isInWorld"))
                    && NativeApi.call(connection, CONNECTION, "getOtherSide", a) == b) {
                return readiness(a, b, "explicit_local_internal_grid_edge");
            }
        }
        return evidence("unknown", false, false, "same_block_grid_nodes_have_no_direct_internal_edge");
    }

    private static Object node(BlockEntity entity, Direction side) { return NativeApi.call(entity, HOST, "getGridNode", side); }

    private static ConnectionEvidence readiness(Object a, Object b, String success) {
        Object grid = NativeApi.call(a, NODE, "getGrid");
        boolean same = grid != null && grid == NativeApi.call(b, NODE, "getGrid");
        boolean booted = both(a, b, "hasGridBooted"), powered = both(a, b, "isPowered");
        boolean channels = both(a, b, "meetsChannelRequirements"), active = both(a, b, "isActive");
        String reason = !same ? "grid_membership_split" : !booted ? "grid_booting" : !powered ? "grid_unpowered"
                : !channels ? "grid_channels_unavailable" : !active ? "grid_node_inactive" : success;
        boolean ready = same && booted && powered && channels && active;
        ConnectionEvidence result = evidence(ready ? "verified" : "planned", same, ready, reason);
        result.details().addProperty("same_grid", same);
        result.details().addProperty("booted", booted); result.details().addProperty("powered", powered);
        result.details().addProperty("channels_satisfied", channels);
        // Identity is scoped to this observation only, not a durable network or storage identifier.
        if (grid != null) result.details().addProperty("observation_grid_identity", Integer.toHexString(System.identityHashCode(grid)));
        return result;
    }

    private static boolean both(Object a, Object b, String method) {
        return NativeApi.truth(NativeApi.call(a, NODE, method)) && NativeApi.truth(NativeApi.call(b, NODE, method));
    }

    private static ConnectionEvidence evidence(String status, boolean connected, boolean operational, String reason) {
        return ConnectionEvidence.of(status, connected, operational, reason, PROVENANCE);
    }
}
