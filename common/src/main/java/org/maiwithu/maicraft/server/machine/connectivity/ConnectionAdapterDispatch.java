// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import java.util.List;

/** 服务端按当前边的原生输送设备选适配器；端点属于 Create 不代表物品在动力轴里移动。 */
final class ConnectionAdapterDispatch {
    static final String HOPPER = "vanilla_hopper_items";
    private ConnectionAdapterDispatch() {}

    static String adapter(String requested, String medium, BlockEntity from, BlockEntity to) {
        if (!medium.equals("items")) return requested;
        // 传输管的 PULL 也能抽漏斗库存；保留实际管道机制的优先级，不能误要求漏斗主动朝管道推出。
        if (mekanismTransport(from) || mekanismTransport(to)) return "mekanism";
        if (HopperConnectionInspection.isHopper(from) || HopperConnectionInspection.isHopper(to)) return HOPPER;
        if (gridHost(from) && gridHost(to)) return "ae2";
        return requested;
    }

    static ConnectionEvidence transit(BlockEntity entity, String incoming, String outgoing,
                                      Direction entry, Direction exit) {
        if (incoming.equals("ae2") && outgoing.equals("ae2"))
            return Ae2ConnectionInspection.transit(entity, entry, exit);
        if (incoming.equals(HOPPER) && outgoing.equals(HOPPER)) return HopperConnectionInspection.transit(entity);
        // 两个方向各有端口并不能证明设备内部贯通，例如加工机的输入仓和输出仓不是同一个库存。
        return ConnectionEvidence.of("unknown", false, false, "mixed_transport_internal_pass_through_unproven",
                "native_transport_adapter_boundary");
    }

    static boolean needsTransit(String incoming, String outgoing) {
        return incoming.equals("ae2") || outgoing.equals("ae2") || incoming.equals(HOPPER) || outgoing.equals(HOPPER);
    }

    static ConnectionEvidence wireTransit(BlockEntity entity, String incoming, String outgoing,
                                          ConnectionEvidence left, ConnectionEvidence right) {
        // 只有真正的 Mek transmitter 才复用两侧已核验的原生边；处理机、普通库存和未单独核验的 sorter 不作内部贯通推断。
        if (!incoming.equals("mekanism") || !outgoing.equals("mekanism")
                || !NativeApi.is(entity, "mekanism.common.tile.transmitter.TileEntityTransmitter"))
            return ConnectionEvidence.of("unknown", false, false, "internal_native_edge_projection_unavailable", "native_adapter_boundary");
        var summary = ConnectionEvidence.summarize(List.of(left, right));
        var result = ConnectionEvidence.of(summary.get("status").getAsString(), summary.get("verified_connection").getAsBoolean(),
                summary.get("operational").getAsBoolean(), "junction_covered_by_adjacent_native_transmitter_edges",
                "Mekanism.native_directional_edges_at_shared_path_node");
        result.details().addProperty("proof_scope", "existing_adjacent_native_edge_conjunction");
        return result;
    }

    static ConnectionEvidence requireTransit(ConnectionEvidence edge, ConnectionEvidence transit) {
        edge.details().add("internal_transit", transit.json());
        if (transit.connected() && transit.operational()) return edge;
        var summary = ConnectionEvidence.summarize(List.of(edge, transit));
        return new ConnectionEvidence(summary.get("status").getAsString(), edge.connected() && transit.connected(),
                edge.operational() && transit.operational(), transit.reason(), transit.provenance(), edge.details());
    }

    private static boolean mekanismTransport(BlockEntity entity) {
        return NativeApi.is(entity, "mekanism.common.tile.transmitter.TileEntityTransmitter")
                || NativeApi.is(entity, "mekanism.common.tile.TileEntityLogisticalSorter");
    }
    private static boolean gridHost(BlockEntity entity) { return NativeApi.is(entity, "appeng.api.networking.IInWorldGridNodeHost"); }
}
