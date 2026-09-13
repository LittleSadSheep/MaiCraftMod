// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;

/** Installed Create 6.0.10 RotationPropagator gear ratios and directional gearbox propagation. */
final class KineticTransmissionRatios {
    record Mesh(BlockPos from, BlockPos to, String fromFamily, String toFamily, Direction.Axis fromAxis, Direction.Axis toAxis, double multiplier) {}
    record Result(Double multiplier, List<Mesh> meshes) {}
    private record Node(BlockPos at, String family, Direction.Axis axis, Map<String, String> state) {}
    private record Edge(BlockPos to, double multiplier, Direction face) {}
    private KineticTransmissionRatios() {}

    static Result calculate(Plan plan) {
        Map<BlockPos, Node> nodes = new LinkedHashMap<>();
        nodes.put(plan.source().position(), node(plan.source())); nodes.put(plan.target().position(), node(plan.target()));
        for (Placement p : plan.placements()) {
            Direction.Axis axis = p.properties().containsKey("axis") ? Direction.Axis.valueOf(p.properties().get("axis").toUpperCase(java.util.Locale.ROOT)) : Direction.Axis.Y;
            if (axis == null || nodes.putIfAbsent(p.position(), new Node(p.position(), p.blockId().substring(p.blockId().indexOf(':') + 1), axis, p.properties())) != null)
                return new Result(null, List.of());
        }
        Map<BlockPos, List<Edge>> edges = new LinkedHashMap<>(); nodes.keySet().forEach(at -> edges.put(at, new ArrayList<>()));
        List<Mesh> meshes = new ArrayList<>();
        for (Node a : nodes.values()) for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            Node b = nodes.get(a.at.offset(dx, dy, dz)); if (b == null || a.at.asLong() >= b.at.asLong()) continue;
            Direction face = KineticRouteGeometry.between(a.at, b.at);
            double modifier = face != null && shaft(a, face, plan) && shaft(b, face.getOpposite(), plan) ? 1 : encased(a, b, face) ? 1 : 0;
            if (modifier == 0 && meshAllowed(a, plan) && meshAllowed(b, plan)) {
                modifier = mesh(a.family, a.axis, b.family, b.axis, b.at.subtract(a.at));
                if (modifier != 0) meshes.add(new Mesh(a.at, b.at, a.family, b.family, a.axis, b.axis, modifier));
            }
            if (modifier != 0) edge(edges, a.at, b.at, modifier, face);
        }
        for (ChainLink link : plan.chainLinks()) {
            Node a = nodes.get(link.from()), b = nodes.get(link.to());
            if (a == null || b == null || !a.family.equals("chain_conveyor") || !b.family.equals("chain_conveyor")) return new Result(null, meshes);
            edge(edges, link.from(), link.to(), 1, null);
        }
        Map<BlockPos, Double> ratios = new HashMap<>(); Map<BlockPos, Direction> entries = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(); ratios.put(plan.source().position(), 1d); queue.add(plan.source().position());
        while (!queue.isEmpty()) {
            BlockPos at = queue.removeFirst(); Node from = nodes.get(at);
            for (Edge edge : edges.get(at)) {
                Node to = nodes.get(edge.to); Direction incoming = edge.face == null ? null : edge.face.getOpposite();
                if (!ratios.containsKey(to.at) && incoming != null) entries.put(to.at, incoming);
                double expected = ratios.get(at) * modifier(from, entries.get(at), edge.face) * edge.multiplier / modifier(to, entries.get(to.at), incoming);
                if (!Double.isFinite(expected) || expected == 0) return new Result(null, meshes);
                Double known = ratios.putIfAbsent(to.at, expected);
                if (known == null) queue.add(to.at);
                else if (Math.abs(known - expected) > 1e-9) return new Result(null, meshes);
            }
        }
        return new Result(ratios.size() == nodes.size() ? ratios.get(plan.target().position()) : null, List.copyOf(meshes));
    }
    static JsonObject json(Plan plan) {
        Result result = calculate(plan); JsonObject out = new JsonObject();
        out.addProperty("basis", "installed_create_6.0.10_rotation_geometry"); out.addProperty("native_connection_verified", false);
        out.addProperty("ratio_known", result.multiplier != null);
        if (result.multiplier != null) {
            out.addProperty("target_rpm_per_source_rpm", result.multiplier);
            out.addProperty("rotation_direction", result.multiplier < 0 ? "opposite" : "same");
        }
        out.addProperty("source_boundary", "ordinary shaft/cog/conveyor port RPM; an existing directional shaft requires its native outlet modifier");
        JsonArray rows = new JsonArray();
        for (Mesh mesh : result.meshes) {
            JsonObject row = new JsonObject(); row.add("from", position(mesh.from)); row.add("to", position(mesh.to));
            row.addProperty("from_family", mesh.fromFamily); row.addProperty("to_family", mesh.toFamily);
            row.addProperty("from_axis", mesh.fromAxis.getName()); row.addProperty("to_axis", mesh.toAxis.getName());
            row.addProperty("multiplier", mesh.multiplier); rows.add(row);
        }
        out.add("gear_meshes", rows); return out;
    }
    static double mesh(String from, Direction.Axis a, String to, Direction.Axis b, BlockPos delta) {
        int x = delta.getX(), y = delta.getY(), z = delta.getZ();
        if (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) != 1) return 0;
        boolean smallA = from.equals("cogwheel"), smallB = to.equals("cogwheel"), largeA = from.equals("large_cogwheel"), largeB = to.equals("large_cogwheel");
        if (smallA && smallB && a == b && a.choose(x, y, z) == 0 && Math.abs(x) + Math.abs(y) + Math.abs(z) == 1) return -1;
        if ((largeA && smallB || smallA && largeB) && a == b && a.choose(x, y, z) == 0) {
            for (Direction.Axis axis : Direction.Axis.values()) if (axis != a && Math.abs(axis.choose(x, y, z)) != 1) return 0;
            return largeA ? -2 : -.5;
        }
        if (largeA && largeB && a != b) {
            for (Direction.Axis axis : Direction.Axis.values()) if (Math.abs(axis.choose(x, y, z)) != (axis == a || axis == b ? 1 : 0)) return 0;
            return (a.choose(x, y, z) > 0) == (b.choose(x, y, z) > 0) ? 1 : -1;
        }
        return 0;
    }
    static double gearbox(Direction sourceFacing, Direction output) {
        if (sourceFacing.getAxis() == output.getAxis()) return sourceFacing == output ? 1 : -1;
        return sourceFacing.getAxisDirection() == output.getAxisDirection() ? -1 : 1;
    }
    private static boolean shaft(Node node, Direction face, Plan plan) {
        if (node.at.equals(plan.source().position())) return face == plan.sourceFace();
        if (node.at.equals(plan.target().position())) return face == plan.targetFace();
        if (node.family.equals("chain_conveyor")) return face == Direction.DOWN;
        return node.family.equals("gearbox") ? face.getAxis() != node.axis : face.getAxis() == node.axis;
    }
    private static boolean meshAllowed(Node node, Plan plan) {
        return !node.at.equals(plan.source().position()) && !node.at.equals(plan.target().position())
                || node.at.equals(plan.source().position()) && plan.sourceFace() == null
                || node.at.equals(plan.target().position()) && plan.targetFace() == null;
    }
    private static boolean encased(Node a, Node b, Direction face) {
        if (face == null || face.getAxis() == Direction.Axis.Y || !a.family.equals("encased_chain_drive") || !b.family.equals(a.family)) return false;
        String along = Boolean.toString(face.getAxis() == Direction.Axis.X);
        return a.axis == Direction.Axis.Y && b.axis == Direction.Axis.Y && along.equals(a.state.get("axis_along_first")) && along.equals(b.state.get("axis_along_first"));
    }
    private static double modifier(Node node, Direction incoming, Direction outgoing) {
        return node.family.equals("gearbox") && incoming != null && outgoing != null ? gearbox(incoming, outgoing) : 1;
    }
    private static void edge(Map<BlockPos, List<Edge>> graph, BlockPos a, BlockPos b, double modifier, Direction face) {
        graph.get(a).add(new Edge(b, modifier, face)); graph.get(b).add(new Edge(a, 1 / modifier, face == null ? null : face.getOpposite()));
    }
    private static Node node(Endpoint endpoint) { return new Node(endpoint.position(), endpoint.family(), endpoint.axis(), Map.of()); }
    private static JsonArray position(BlockPos at) { JsonArray out = new JsonArray(); out.add(at.getX()); out.add(at.getY()); out.add(at.getZ()); return out; }
}
