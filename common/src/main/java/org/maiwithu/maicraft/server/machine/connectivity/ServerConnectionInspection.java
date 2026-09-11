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

/** Read-only game-thread handler for the optional machine.connections v1 operation. */
public final class ServerConnectionInspection {
    private ServerConnectionInspection() {}

    public static JsonObject inspect(ServerPlayer player, JsonObject body) {
        ConnectionPath path;
        try { path = ConnectionPath.parse(body); }
        catch (IllegalArgumentException invalid) { throw ServerAccess.denied("invalid_path", invalid.getMessage()); }
        List<BlockPos> positions = new ArrayList<>();
        List<BlockEntity> entities = new ArrayList<>();
        // Validate the entire declared scope before invoking optional native APIs; never load chunks.
        for (ConnectionPath.Point point : path.positions()) {
            BlockPos position = new BlockPos(point.x(), point.y(), point.z());
            positions.add(position);
            entities.add(ServerAccess.check(player, position, false));
        }
        List<ConnectionEvidence> evidence = new ArrayList<>();
        JsonArray edges = new JsonArray();
        for (int index = 0; index < positions.size() - 1; index++) {
            final int i = index;
            ConnectionEvidence edge = nativeRead(() -> switch (path.system()) {
                case "create" -> CreateConnectionInspection.edge(path.medium(), entities.get(i), entities.get(i + 1));
                case "ae2" -> Ae2ConnectionInspection.edge(path.medium(), entities.get(i), entities.get(i + 1));
                case "mekanism" -> MekanismConnectionInspection.edge(path.medium(), entities.get(i), entities.get(i + 1),
                        positions.get(i), positions.get(i + 1), i == 0, i == positions.size() - 2);
                default -> throw new IllegalStateException();
            });
            JsonObject item = edge.json();
            item.addProperty("index", i);
            item.add("from", path.positions().get(i).json());
            item.add("to", path.positions().get(i + 1).json());
            edges.add(item);
            evidence.add(edge);
        }
        JsonArray intermediate = new JsonArray();
        if (path.system().equals("ae2")) {
            for (int index = 1; index < entities.size() - 1; index++) {
                final int i = index;
                ConnectionEvidence node = nativeRead(() -> Ae2ConnectionInspection.transit(entities.get(i),
                        direction(positions.get(i), positions.get(i - 1)), direction(positions.get(i), positions.get(i + 1))));
                JsonObject item = node.json(); item.addProperty("index", i);
                intermediate.add(item); evidence.add(node);
            }
        }
        JsonObject result = ConnectionEvidence.summarize(evidence);
        result.addProperty("schema", "maicraft.connection_inspection.v1");
        result.addProperty("system", path.system()); result.addProperty("medium", path.medium());
        result.addProperty("dimension", player.serverLevel().dimension().location().toString());
        result.addProperty("tick", player.serverLevel().getGameTime());
        result.addProperty("complete", true);
        JsonArray inspectedPath = new JsonArray();
        path.positions().forEach(point -> inspectedPath.add(point.json()));
        result.add("path", inspectedPath);
        result.add("edges", edges); result.add("intermediate", intermediate);
        if (path.system().equals("mekanism") && path.medium().equals("items")) {
            JsonObject route = MekanismConnectionInspection.itemRoute(player, positions, entities, body);
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
