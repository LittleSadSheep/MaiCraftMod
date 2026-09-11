// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Strict wire decoding: compressed indices remain bound to the exact request path. */
final class ProductionConnectionResponseRows {
    record Segment(long tick, boolean connected, boolean operational, boolean truncated, String status,
                   List<JsonObject> edges, List<JsonObject> intermediate) {}
    private ProductionConnectionResponseRows() {}

    static Segment decode(List<BlockPos> path, String dimension, String medium, String system, JsonObject reply) {
        require("maicraft.connection_inspection.v1".equals(text(reply, "schema")), "wrong_schema");
        require(dimension.equals(text(reply, "dimension")) && medium.equals(text(reply, "medium"))
                && system.equals(text(reply, "system")), "wrong_connection_context");
        require(Boolean.TRUE.equals(bool(reply, "complete")), "incomplete_segment");
        long tick = integer(reply, "tick"); require(tick >= 0, "invalid_tick");
        boolean connected = flag(reply, "verified_connection"), operational = flag(reply, "operational");
        require(!operational || connected, "contradictory_segment_flags");
        String status = status(reply);
        require(!connected || !Set.of("unknown", "unsupported").contains(status), "contradictory_segment_status");
        boolean truncated = Boolean.TRUE.equals(bool(reply, "detail_truncated"));
        if (reply.has("detail_truncated")) require(bool(reply, "detail_truncated") != null, "invalid_truncation_flag");
        JsonArray positions = array(reply, "path"); require(positions.size() == path.size(), "wrong_segment_path_size");
        for (int i = 0; i < path.size(); i++) require(path.get(i).equals(position(positions.get(i))), "wrong_segment_path");
        JsonArray rawEdges = array(reply, "edges"); require(rawEdges.size() == path.size() - 1, "wrong_edge_count");
        List<JsonObject> edges = new ArrayList<>();
        for (int i = 0; i < rawEdges.size(); i++) {
            JsonObject row = object(rawEdges.get(i)); require(integer(row, "index") == i, "wrong_edge_index");
            boolean coordinates = row.has("from") || row.has("to");
            if (coordinates) require(path.get(i).equals(position(row.get("from")))
                    && path.get(i + 1).equals(position(row.get("to"))), "wrong_edge_coordinates");
            boolean indices = row.has("from_index") || row.has("to_index");
            if (indices) require(integer(row, "from_index") == i && integer(row, "to_index") == i + 1, "wrong_edge_indices");
            require(coordinates || indices && truncated, "unbound_edge_coordinates");
            JsonObject decoded = row(row, connected, operational, truncated);
            decoded.add("from", point(path.get(i))); decoded.add("to", point(path.get(i + 1)));
            edges.add(decoded);
        }
        JsonArray rawIntermediate = array(reply, "intermediate");
        require(rawIntermediate.size() == (system.equals("ae2") ? path.size() - 2 : 0), "wrong_intermediate_count");
        List<JsonObject> intermediate = new ArrayList<>();
        for (int i = 0; i < rawIntermediate.size(); i++) {
            JsonObject row = object(rawIntermediate.get(i)); require(integer(row, "index") == i + 1, "wrong_intermediate_index");
            JsonObject decoded = row(row, connected, operational, truncated);
            if (row.has("position")) require(path.get(i + 1).equals(position(row.get("position"))), "wrong_intermediate_position");
            decoded.add("position", point(path.get(i + 1))); intermediate.add(decoded);
        }
        return new Segment(tick, connected, operational, truncated, status, edges, intermediate);
    }

    private static JsonObject row(JsonObject raw, boolean connected, boolean operational, boolean truncated) {
        JsonObject row = raw.deepCopy(); String status = status(raw); boolean support = flag(raw, "native_support");
        require(text(raw, "reason") != null && !text(raw, "reason").isBlank(), "missing_native_reason");
        boolean proof = rowFlag(raw, "verified_connection", connected, truncated);
        boolean running = rowFlag(raw, "operational", operational, truncated);
        require(!connected || proof, "segment_true_but_edge_false");
        require(!operational || running, "segment_running_but_edge_false");
        require(!proof || support && !Set.of("unknown", "unsupported").contains(status), "contradictory_native_proof");
        require(!running || proof, "edge_running_without_connection");
        row.addProperty("verified_connection", proof); row.addProperty("operational", running);
        row.addProperty("detail_truncated", truncated);
        if (!raw.has("verified_connection") || !raw.has("operational")) row.addProperty("flags_omitted_in_wire", true);
        row.addProperty("flow_verified", false); row.addProperty("production_verified", false);
        row.remove("proofs"); row.remove("oldest_tick"); row.remove("latest_tick");
        return row;
    }

    private static boolean rowFlag(JsonObject row, String key, boolean aggregate, boolean truncated) {
        if (row.has(key)) return flag(row, key);
        require(truncated, "missing_uncompressed_edge_flag");
        return aggregate; // A false aggregate never creates positive evidence for an omitted individual flag.
    }
    static String status(JsonObject value) {
        String status = text(value, "status");
        require(status != null && Set.of("verified", "planned", "unknown", "unsupported").contains(status), "invalid_status");
        return status;
    }
    static int rank(String status) { return switch (status) { case "verified" -> 0; case "planned" -> 1; case "unknown" -> 2; default -> 3; }; }
    static boolean flag(JsonObject value, String key) { Boolean result = bool(value, key); require(result != null, "invalid_" + key); return result; }
    static Boolean bool(JsonObject value, String key) {
        JsonElement raw = value == null ? null : value.get(key);
        return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean() ? raw.getAsBoolean() : null;
    }
    static String text(JsonObject value, String key) {
        JsonElement raw = value == null ? null : value.get(key);
        return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString() ? raw.getAsString() : null;
    }
    static long integer(JsonObject value, String key) {
        JsonElement raw = value.get(key);
        require(raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isNumber(), "invalid_" + key);
        try { return raw.getAsBigDecimal().longValueExact(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid_" + key); }
    }
    static JsonArray array(JsonObject value, String key) {
        require(value.has(key) && value.get(key).isJsonArray(), "missing_" + key); return value.getAsJsonArray(key);
    }
    static JsonObject object(JsonElement value) { require(value != null && value.isJsonObject(), "invalid_object"); return value.getAsJsonObject(); }
    static BlockPos position(JsonElement value) {
        JsonObject point = object(value);
        return new BlockPos(Math.toIntExact(integer(point, "x")), Math.toIntExact(integer(point, "y")), Math.toIntExact(integer(point, "z")));
    }
    static JsonObject point(BlockPos position) {
        JsonObject value = new JsonObject(); value.addProperty("x", position.getX());
        value.addProperty("y", position.getY()); value.addProperty("z", position.getZ()); return value;
    }
    static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
