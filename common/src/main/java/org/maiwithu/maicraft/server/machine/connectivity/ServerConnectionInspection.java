// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 在服务端游戏线程逐边读取连接；先核玩家范围与权限，再按实际输送机制分派，期间不转动物资。 */
public final class ServerConnectionInspection {
    private ServerConnectionInspection() {}

    public static JsonObject inspect(ServerPlayer player, JsonObject body) {
        ConnectionPath path;
        try { path = ConnectionPath.parse(body); }
        catch (IllegalArgumentException invalid) { throw ServerAccess.denied("invalid_path", invalid.getMessage()); }
        List<BlockPos> positions = new ArrayList<>();
        List<BlockEntity> entities = new ArrayList<>();
        // 整条声明路径先通过玩家权限和已加载检查，之后才读原生端口，不为预检加载新区块。
        for (ConnectionPath.Point point : path.positions()) {
            BlockPos position = new BlockPos(point.x(), point.y(), point.z());
            positions.add(position);
            entities.add(ServerAccess.check(player, position, false));
        }
        List<ConnectionEvidence> evidence = new ArrayList<>();
        List<ConnectionEvidence> junctions = new ArrayList<>();
        List<String> adapters = new ArrayList<>();
        JsonArray edges = new JsonArray();
        for (int index = 0; index < positions.size() - 1; index++) {
            final int i = index;
            String adapter = ConnectionAdapterDispatch.adapter(path.system(), path.medium(), entities.get(i), entities.get(i + 1));
            adapters.add(adapter);
            // 请求的 system 仍用于协议配对；实际 items 边可由漏斗或传输管驱动，不能只看端点模组。
            ConnectionEvidence edge = nativeRead(() -> switch (adapter) {
                case ConnectionAdapterDispatch.HOPPER -> HopperConnectionInspection.edge(player, entities.get(i), entities.get(i + 1), body);
                case "create" -> CreateConnectionInspection.edge(path.medium(), entities.get(i), entities.get(i + 1), body.has("link_kind"));
                case "ae2" -> Ae2ConnectionInspection.edge(path.medium(), entities.get(i), entities.get(i + 1));
                case "mekanism" -> MekanismConnectionInspection.edge(path.medium(), entities.get(i), entities.get(i + 1),
                        positions.get(i), positions.get(i + 1), i == 0, i == positions.size() - 2);
                default -> throw new IllegalStateException();
            });
            if (i > 0) {
                ConnectionEvidence transit;
                if (ConnectionAdapterDispatch.needsTransit(adapters.get(i - 1), adapter)) {
                    transit = nativeRead(() -> ConnectionAdapterDispatch.transit(entities.get(i),
                            adapters.get(i - 1), adapter, direction(positions.get(i), positions.get(i - 1)),
                            direction(positions.get(i), positions.get(i + 1))));
                    edge = ConnectionAdapterDispatch.requireTransit(edge, transit);
                } else {
                    // 管道原生边已经验证共同中转实体；只保存已有证明，不因客户端 system 提示再调用 AE2 内部接口。
                    transit = ConnectionAdapterDispatch.wireTransit(entities.get(i), adapters.get(i - 1), adapter, evidence.get(i - 1), edge);
                }
                junctions.add(transit);
            }
            JsonObject item = edge.json();
            item.addProperty("adapter", adapter);
            item.addProperty("index", i);
            item.add("from", path.positions().get(i).json());
            item.add("to", path.positions().get(i + 1).json());
            edges.add(item);
            evidence.add(edge);
        }
        JsonArray intermediate = new JsonArray();
        // v1 解码器要求 ae2 提示携带等长节点行；这里只序列化此前按真实适配器得到的结果，不追加平台检查。
        if (path.system().equals("ae2")) {
            for (int index = 0; index < junctions.size(); index++) {
                JsonObject item = junctions.get(index).json(); item.addProperty("index", index + 1);
                intermediate.add(item);
            }
            // 兼容行若仍未知，整条回执也必须保留未知，不能出现总项为真而节点行不通过的矛盾证据。
            evidence.addAll(junctions);
        }
        JsonObject result = ConnectionEvidence.summarize(evidence);
        result.addProperty("schema", "maicraft.connection_inspection.v1");
        result.addProperty("system", path.system()); result.addProperty("medium", path.medium());
        if (body.has("link_kind")) result.add("link_kind", body.get("link_kind").deepCopy());
        result.addProperty("dimension", player.serverLevel().dimension().location().toString());
        result.addProperty("tick", player.serverLevel().getGameTime());
        result.addProperty("complete", true);
        JsonArray inspectedPath = new JsonArray();
        path.positions().forEach(point -> inspectedPath.add(point.json()));
        result.add("path", inspectedPath);
        result.add("edges", edges); result.add("intermediate", intermediate);
        if (path.medium().equals("items") && (adapters.stream().allMatch("mekanism"::equals)
                || adapters.stream().allMatch(ConnectionAdapterDispatch.HOPPER::equals))) {
            // 准入汇总必须覆盖每一条边且绑定同一个组件身份；此处仍不会把它升级为真实流量或生产完成。
            JsonObject route = adapters.getFirst().equals(ConnectionAdapterDispatch.HOPPER)
                    ? HopperItemRouteEvidence.summarize(evidence.subList(0, adapters.size()))
                    : MekanismConnectionInspection.itemRoute(player, positions, entities, body);
            result.add("item_route", route);
            if (result.get("verified_connection").getAsBoolean()) {
                result.addProperty("resource_compatibility", route.get("status").getAsString());
            }
        }
        return ConnectionResponseBudget.fit(result);
    }

    static ConnectionEvidence nativeRead(Supplier<ConnectionEvidence> read) {
        try { return read.get(); }
        catch (NativeApi.Unavailable missing) {
            return ConnectionEvidence.of("unsupported", false, false, "native_api_unavailable", "optional_public_api");
        } catch (RuntimeException | LinkageError failure) {
            return ConnectionEvidence.of("unknown", false, false, "native_read_failed", "optional_public_api");
        }
    }

    static Direction direction(BlockPos from, BlockPos to) {
        for (Direction direction : Direction.values()) if (from.relative(direction).equals(to)) return direction;
        return null;
    }
}
