// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import java.util.ArrayList;
import java.util.List;

/** Create 6: query propagation rules and existing memberships without creating a network. */
final class CreateConnectionInspection {
    private static final String KINETIC = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
    private static final String ROTATE = "com.simibubi.create.content.kinetics.base.IRotate";
    private static final String PROPAGATOR = "com.simibubi.create.content.kinetics.RotationPropagator";
    private static final String PROVENANCE = "Create.RotationPropagator.isConnected+KineticBlockEntity.network";

    private CreateConnectionInspection() {}

    static ConnectionEvidence edge(String medium, BlockEntity from, BlockEntity to) {
        if (!medium.equals("kinetic") || !NativeApi.present(KINETIC)) {
            return evidence("unsupported", false, false, "create_adapter_requires_kinetic_api");
        }
        if (!NativeApi.is(from, KINETIC) || !NativeApi.is(to, KINETIC)) {
            return evidence("planned", false, false, "expected_kinetic_block_missing");
        }
        // isConnected itself assumes the caller already selected a propagation neighbor.
        // In particular, aligned shafts at arbitrary distances must never be accepted.
        boolean neighbor = candidate(from, to) || candidate(to, from);
        boolean connected = neighbor && (NativeApi.truth(NativeApi.call(null, PROPAGATOR, "isConnected", from, to))
                || NativeApi.truth(NativeApi.call(null, PROPAGATOR, "isConnected", to, from)));
        if (!connected) return evidence("planned", false, false, "native_rotation_edge_absent");
        Object fromNetwork = NativeApi.field(from, KINETIC, "network");
        Object toNetwork = NativeApi.field(to, KINETIC, "network");
        boolean sameNetwork = fromNetwork != null && fromNetwork.equals(toNetwork);
        boolean pending = NativeApi.truth(NativeApi.field(from, KINETIC, "networkDirty"))
                || NativeApi.truth(NativeApi.field(to, KINETIC, "networkDirty"))
                || NativeApi.truth(NativeApi.call(from, KINETIC, "needsSpeedUpdate"))
                || NativeApi.truth(NativeApi.call(to, KINETIC, "needsSpeedUpdate"));
        boolean stressed = NativeApi.truth(NativeApi.call(from, KINETIC, "isOverStressed"))
                || NativeApi.truth(NativeApi.call(to, KINETIC, "isOverStressed"));
        float fromSpeed = ((Number) NativeApi.call(from, KINETIC, "getSpeed")).floatValue();
        float toSpeed = ((Number) NativeApi.call(to, KINETIC, "getSpeed")).floatValue();
        boolean running = Float.isFinite(fromSpeed) && Float.isFinite(toSpeed) && fromSpeed != 0 && toSpeed != 0;
        String reason = pending ? "kinetic_network_update_pending" : !sameNetwork ? "kinetic_network_membership_missing_or_split"
                : stressed ? "kinetic_network_overstressed" : !running ? "kinetic_source_not_running" : "native_rotation_path_present";
        boolean verified = sameNetwork && !pending;
        ConnectionEvidence result = evidence(verified && !stressed && running ? "verified" : "planned",
                verified, verified && !stressed && running, reason);
        result.details().addProperty("native_edge", true);
        result.details().addProperty("same_network", sameNetwork);
        if (fromNetwork != null) result.details().addProperty("from_network", fromNetwork.toString());
        if (toNetwork != null) result.details().addProperty("to_network", toNetwork.toString());
        if (Float.isFinite(fromSpeed)) result.details().addProperty("from_rpm", fromSpeed);
        if (Float.isFinite(toSpeed)) result.details().addProperty("to_rpm", toSpeed);
        result.details().addProperty("overstressed", stressed);
        result.details().addProperty("rotation_is_production_evidence", false);
        return result;
    }

    private static boolean candidate(BlockEntity from, BlockEntity to) {
        List<BlockPos> neighbors = new ArrayList<>();
        for (Direction direction : Direction.values()) neighbors.add(from.getBlockPos().relative(direction));
        Object block = from.getBlockState().getBlock();
        if (!NativeApi.is(block, ROTATE)) return false;
        Object expanded = NativeApi.call(from, KINETIC, "addPropagationLocations", block, from.getBlockState(), neighbors);
        return expanded instanceof List<?> positions && positions.contains(to.getBlockPos());
    }

    private static ConnectionEvidence evidence(String status, boolean connected, boolean operational, String reason) {
        return ConnectionEvidence.of(status, connected, operational, reason, PROVENANCE);
    }
}
