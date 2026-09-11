// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Mekanism 10.7: actual transmitter edges and directional, sided acceptor attachments. */
final class MekanismConnectionInspection {
    private static final String TILE = "mekanism.common.tile.transmitter.TileEntityTransmitter";
    private static final String TRANSMITTER = "mekanism.common.content.network.transmitter.Transmitter";
    private static final String NETWORK = "mekanism.common.lib.transmitter.DynamicNetwork";
    private static final String CONNECTION = "mekanism.common.lib.transmitter.ConnectionType";
    private static final String TYPE = "mekanism.common.lib.transmitter.TransmissionType";
    private static final String ITEMS = "mekanism.common.content.network.transmitter.LogisticalTransporterBase";
    private static final String PROVENANCE = "Mekanism.Transmitter.currentTransmitterConnections+getAcceptor+DynamicNetwork";

    private MekanismConnectionInspection() {}

    static ConnectionEvidence edge(String medium, BlockEntity from, BlockEntity to, BlockPos fromPos, BlockPos toPos,
                                   boolean first, boolean last) {
        if (medium.equals("kinetic") || !NativeApi.present(TILE)) {
            return evidence("unsupported", false, false, "mekanism_transmission_api_unavailable_for_medium");
        }
        Object a = transmitter(from), b = transmitter(to);
        Direction direction = ServerConnectionInspection.direction(fromPos, toPos);
        if (medium.equals("items") && MekSorterConnectionInspection.is(to) && a == null && first) {
            return MekSorterConnectionInspection.home(from, to, fromPos);
        }
        if (medium.equals("items") && MekSorterConnectionInspection.is(from) && b != null) {
            return MekSorterConnectionInspection.output(from, b, toPos);
        }
        if (a == null && b == null) return evidence("unsupported", false, false, "edge_has_no_native_transmitter");
        if (a != null && !supports(a, medium) || b != null && !supports(b, medium)) {
            return evidence("planned", false, false, "transmitter_medium_mismatch");
        }
        if (a != null && b != null) return between(a, b, from, to, direction);
        if (a == null && !first || b == null && !last) {
            return evidence("unsupported", false, false, "non_transmitter_inside_declared_transport_path");
        }
        Object pipe = a == null ? b : a;
        BlockPos pipePos = a == null ? toPos : fromPos;
        BlockPos machinePos = a == null ? fromPos : toPos;
        Direction pipeSide = a == null ? direction.getOpposite() : direction;
        boolean source = a == null;
        Object network = network(pipe, pipePos);
        if (network == null) return evidence("planned", false, false, "transmitter_network_membership_missing");
        Object mode = NativeApi.call(pipe, TRANSMITTER, "getConnectionType", pipeSide);
        boolean directionAllowed = NativeApi.truth(NativeApi.call(mode, CONNECTION, source ? "canAccept" : "canSendTo"));
        boolean canConnect = NativeApi.truth(NativeApi.call(pipe, TRANSMITTER, "canConnect", pipeSide));
        Object acceptor = NativeApi.call(pipe, TRANSMITTER, "getAcceptor", pipeSide);
        if (!canConnect || !directionAllowed || acceptor == null) {
            ConnectionEvidence result = evidence("planned", false, false, !canConnect ? "transmitter_side_disabled"
                    : !directionAllowed ? "endpoint_direction_disabled" : "sided_resource_handler_not_attached");
            result.details().addProperty("connection_type", String.valueOf(mode));
            result.details().addProperty("endpoint_role", source ? "source" : "destination");
            return result;
        }
        // The network caches outbound acceptors only; a PULL source must not be rejected for absence there.
        if (!source && NativeApi.call(network, NETWORK, "getCachedAcceptor", machinePos.asLong(), pipeSide.getOpposite()) != acceptor) {
            return evidence("unknown", false, false, "destination_acceptor_cache_not_current");
        }
        boolean autonomousSource = !source || "PULL".equals(String.valueOf(mode));
        ConnectionEvidence result = evidence(autonomousSource ? "verified" : "planned", true, autonomousSource,
                autonomousSource ? "native_directional_acceptor_attached" : "source_requires_proven_active_ejector_or_pull_mode");
        result.details().addProperty("connection_type", String.valueOf(mode));
        result.details().addProperty("endpoint_role", source ? "source" : "destination");
        result.details().addProperty("machine_face", pipeSide.getOpposite().getSerializedName());
        result.details().addProperty("sided_handler_attached", true);
        result.details().addProperty("specific_resource_acceptance_verified", false);
        result.details().addProperty("network_uuid", String.valueOf(NativeApi.call(network, NETWORK, "getUUID")));
        return result;
    }

    private static ConnectionEvidence between(Object a, Object b, BlockEntity from, BlockEntity to, Direction side) {
        Object leftNetwork = network(a, from.getBlockPos()), rightNetwork = network(b, to.getBlockPos());
        boolean sameNetwork = leftNetwork != null && leftNetwork == rightNetwork;
        boolean mapped = mapped(a, side) && mapped(b, side.getOpposite());
        boolean enabled = NativeApi.truth(NativeApi.call(a, TRANSMITTER, "canConnectMutual", side, to))
                && NativeApi.truth(NativeApi.call(b, TRANSMITTER, "canConnectMutual", side.getOpposite(), from));
        boolean compatible = NativeApi.truth(NativeApi.call(a, TRANSMITTER, "isValidTransmitter", to, side))
                && NativeApi.truth(NativeApi.call(b, TRANSMITTER, "isValidTransmitter", from, side.getOpposite()));
        boolean directional = true;
        if (NativeApi.is(a, ITEMS) && NativeApi.is(b, ITEMS)) {
            directional = NativeApi.truth(NativeApi.call(a, ITEMS, "canEmitTo", side))
                    && NativeApi.truth(NativeApi.call(b, ITEMS, "canReceiveFrom", side.getOpposite()));
        }
        boolean verified = sameNetwork && mapped && enabled && compatible && directional;
        String reason = !mapped ? "native_transmitter_edge_absent" : !enabled ? "transmitter_connection_disabled"
                : !compatible ? "native_transmitters_incompatible" : !directional ? "item_route_direction_disabled"
                : !sameNetwork ? "transmitter_network_membership_missing_or_split" : "native_transmitter_edge_present";
        ConnectionEvidence result = evidence(verified ? "verified" : "planned", verified, verified, reason);
        result.details().addProperty("same_network", sameNetwork);
        result.details().addProperty("both_connection_bits", mapped);
        result.details().addProperty("native_compatibility", compatible);
        if (leftNetwork != null) result.details().addProperty("from_network", String.valueOf(NativeApi.call(leftNetwork, NETWORK, "getUUID")));
        if (rightNetwork != null) result.details().addProperty("to_network", String.valueOf(NativeApi.call(rightNetwork, NETWORK, "getUUID")));
        return result;
    }

    static JsonObject itemRoute(ServerPlayer player, List<BlockPos> positions, List<BlockEntity> entities, JsonObject body) {
        try {
            List<Object> transmitters = new ArrayList<>();
            for (BlockEntity entity : entities) transmitters.add(transmitter(entity));
            JsonObject sorter = MekSorterConnectionInspection.route(player, positions, entities, transmitters, body);
            if (sorter != null) return sorter;
            return MekItemRouteEvidence.inspect(player, positions, transmitters, body);
        } catch (org.maiwithu.maicraft.network.ServerOperationException rejected) {
            throw rejected;
        } catch (NativeApi.Unavailable unavailable) {
            return evidence("unsupported", false, false, "item_route_api_unavailable").json();
        } catch (RuntimeException | LinkageError failure) {
            return evidence("unknown", false, false, "item_route_native_read_failed").json();
        }
    }

    private static Object transmitter(BlockEntity entity) {
        return NativeApi.is(entity, TILE) ? NativeApi.call(entity, TILE, "getTransmitter") : null;
    }

    private static Object network(Object transmitter, BlockPos position) {
        if (!NativeApi.truth(NativeApi.call(transmitter, TRANSMITTER, "isValid"))) return null;
        Object network = NativeApi.call(transmitter, TRANSMITTER, "getTransmitterNetwork");
        return network != null && NativeApi.call(network, NETWORK, "getTransmitter", position) == transmitter ? network : null;
    }

    private static boolean mapped(Object transmitter, Direction side) {
        Object bits = NativeApi.field(transmitter, TRANSMITTER, "currentTransmitterConnections");
        return NativeApi.truth(NativeApi.call(null, TRANSMITTER, "connectionMapContainsSide", bits, side));
    }

    private static boolean supports(Object transmitter, String medium) {
        String expected = switch (medium) { case "items" -> "ITEM"; case "fluids" -> "FLUID";
            case "chemicals" -> "CHEMICAL"; case "energy" -> "ENERGY"; default -> ""; };
        Object nativeType = NativeApi.enumValue(TYPE, expected);
        return ((Set<?>) NativeApi.call(transmitter, TRANSMITTER, "getSupportedTransmissionTypes")).contains(nativeType);
    }

    private static ConnectionEvidence evidence(String status, boolean connected, boolean operational, String reason) {
        return ConnectionEvidence.of(status, connected, operational, reason, PROVENANCE);
    }
}
