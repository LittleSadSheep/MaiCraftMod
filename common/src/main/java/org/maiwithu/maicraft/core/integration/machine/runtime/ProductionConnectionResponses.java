// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionConnectionResponseRows.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Local conjunction of individually authorized native observations; never an atomic world snapshot. */
final class ProductionConnectionResponses {
    private static final long MAX_AGE = 1200;
    private final List<BlockPos> path;
    private final String dimension, medium, exactResourceId;
    private final JsonObject[] edges, intermediate;
    private final JsonArray segments = new JsonArray();
    private String system, failure, status = "verified";
    private boolean connected = true, operational = true, truncated;
    private long oldest = Long.MAX_VALUE, latest = -1;
    private int accepted;
    private JsonObject single;

    ProductionConnectionResponses(List<BlockPos> fullPath, String dimension, String medium, String exactResourceId) {
        require(fullPath != null && fullPath.size() >= 2, "connection_path_requires_endpoints");
        path = fullPath.stream().map(BlockPos::immutable).toList();
        this.dimension = Objects.requireNonNull(dimension); this.medium = Objects.requireNonNull(medium);
        this.exactResourceId = exactResourceId;
        edges = new JsonObject[path.size() - 1]; intermediate = new JsonObject[path.size()];
    }

    void accept(int start, int end, String expectedSystem, JsonObject reply) {
        if (segments.size() >= (long) path.size() * 2) { failure("segment_budget_exceeded"); return; }
        JsonObject summary = new JsonObject(); summary.addProperty("start", start); summary.addProperty("end", end);
        summary.addProperty("system", expectedSystem); segments.add(summary);
        if (reply != null) for (String key : new String[]{"requestId", "request_id", "maicraft_request_id"}) {
            if (text(reply, key) != null) summary.addProperty(key, text(reply, key));
        }
        if (reply != null && bool(reply, "detail_truncated") != null) {
            summary.addProperty("detail_truncated", flag(reply, "detail_truncated"));
            truncated |= flag(reply, "detail_truncated");
        }
        try {
            require(start >= 0 && end > start && end < path.size() && end - start + 1 <= 128, "invalid_segment_range");
            require(expectedSystem != null && Set.of("create", "ae2", "mekanism").contains(expectedSystem), "invalid_system");
            require(system == null || system.equals(expectedSystem), "mixed_connection_systems");
            List<BlockPos> subpath = path.subList(start, end + 1);
            Segment segment = decode(subpath, dimension, medium, expectedSystem, Objects.requireNonNull(reply));
            system = expectedSystem; accepted++;
            oldest = Math.min(oldest, segment.tick()); latest = Math.max(latest, segment.tick());
            connected &= segment.connected(); operational &= segment.operational(); truncated |= segment.truncated();
            if (rank(segment.status()) > rank(status)) status = segment.status();
            summary.addProperty("tick", segment.tick()); summary.addProperty("status", segment.status());
            summary.addProperty("detail_truncated", segment.truncated());
            summary.addProperty("verified_connection", segment.connected()); summary.addProperty("operational", segment.operational());
            if (reply.has("evidence_provenance")) summary.add("evidence_provenance", reply.get("evidence_provenance").deepCopy());
            if (text(reply, "resource_compatibility") != null) summary.addProperty("resource_compatibility", text(reply, "resource_compatibility"));
            for (int index = 0; index < segment.edges().size(); index++) {
                JsonObject row = segment.edges().get(index); int global = start + index;
                row.addProperty("index", global); row.remove("from_index"); row.remove("to_index");
                edges[global] = merge(edges[global], row, segment.tick(), segments.size() - 1);
            }
            for (int index = 0; index < segment.intermediate().size(); index++) {
                JsonObject row = segment.intermediate().get(index); int global = start + index + 1;
                row.addProperty("index", global);
                intermediate[global] = merge(intermediate[global], row, segment.tick(), segments.size() - 1);
            }
            single = accepted == 1 && start == 0 && end == path.size() - 1 ? reply.deepCopy() : null;
        } catch (RuntimeException invalid) {
            String reason = invalid.getMessage() == null ? "malformed_segment" : invalid.getMessage();
            summary.addProperty("status", "unknown"); summary.addProperty("reason", reason);
            failure(reason);
        }
    }

    void failure(String code) { if (failure == null) failure = code; }

    /** Only ticks from a fully decoded, context-matching native segment advance this clock. */
    long latestTick() { return latest; }

    JsonObject finish(long now) {
        boolean covered = accepted > 0;
        for (JsonObject edge : edges) covered &= edge != null;
        if ("ae2".equals(system)) for (int i = 1; i < intermediate.length - 1; i++) covered &= intermediate[i] != null;
        boolean stale = latest >= 0 && (now < latest || now < 0 || now - oldest > MAX_AGE || latest - oldest > MAX_AGE);
        boolean complete = covered && failure == null && !stale;
        boolean verified = complete && connected && positiveRows(edges) && positiveRows(intermediate);
        boolean running = verified && operational;
        String finalStatus = !complete ? "unknown" : status;
        if (verified && !running && finalStatus.equals("verified")) finalStatus = "planned";
        if (!verified && finalStatus.equals("verified")) finalStatus = "unknown";
        JsonObject result = new JsonObject();
        result.addProperty("schema", "maicraft.connection_inspection.v1"); result.addProperty("dimension", dimension);
        result.addProperty("medium", medium); if (system != null) result.addProperty("system", system);
        result.addProperty("status", finalStatus); result.addProperty("complete", complete);
        result.addProperty("verified_connection", verified); result.addProperty("operational", running);
        result.addProperty("detail_truncated", truncated); result.addProperty("segmented", accepted > 1);
        result.addProperty("non_atomic", accepted > 1 || latest >= 0 && oldest != latest);
        result.addProperty("flow_verified", false); result.addProperty("production_verified", false);
        result.addProperty("resource_compatibility", "unknown");
        result.addProperty("scope", "explicit_path_native_topology");
        result.addProperty("provenance", "native_connection_segments_conjunction");
        String reason = stale ? "refresh_required" : failure != null ? failure : !covered ? "missing_path_or_ae2_junction_evidence"
                : verified ? running ? "complete_native_path_observed" : "connected_path_not_operational" : "native_path_not_verified";
        result.addProperty("reason", reason); if (failure != null) result.addProperty("failure", failure);
        if (latest >= 0) {
            result.addProperty("tick", oldest); result.addProperty("oldest_tick", oldest); result.addProperty("latest_tick", latest);
            result.addProperty("observation_span_ticks", latest - oldest);
        } else { result.add("tick", JsonNull.INSTANCE); result.add("oldest_tick", JsonNull.INSTANCE); result.add("latest_tick", JsonNull.INSTANCE); }
        JsonArray inspected = new JsonArray(), edgeRows = new JsonArray(), junctionRows = new JsonArray();
        path.forEach(position -> inspected.add(point(position)));
        for (JsonObject edge : edges) if (edge != null) edgeRows.add(edge.deepCopy());
        for (JsonObject junction : intermediate) if (junction != null) junctionRows.add(junction.deepCopy());
        result.add("path", inspected); result.add("edges", edgeRows); result.add("intermediate", junctionRows);
        result.add("segments", segments.deepCopy());
        if (complete && verified && accepted == 1 && single != null) {
            JsonObject route = single.has("item_route") && single.get("item_route").isJsonObject()
                    ? single.getAsJsonObject("item_route").deepCopy() : null;
            if (route != null) {
                route.addProperty("flow_verified", false); route.addProperty("production_verified", false); result.add("item_route", route);
            }
            String returned = route == null ? null : text(route, "sample_resource_id");
            if (returned == null) returned = text(single, "resource_id");
            if (exactResourceId != null && exactResourceId.equals(returned) && "verified".equals(text(single, "resource_compatibility"))
                    && (route == null || "verified".equals(text(route, "status")))) {
                result.addProperty("resource_compatibility", "verified"); result.addProperty("resource_id", returned);
            }
        }
        return result;
    }

    private static boolean positiveRows(JsonObject[] rows) {
        for (JsonObject row : rows) if (row != null && !Boolean.TRUE.equals(bool(row, "verified_connection"))) return false;
        return true;
    }

    private static JsonObject merge(JsonObject previous, JsonObject observation, long tick, int segment) {
        observation.addProperty("tick", tick); observation.addProperty("segment_index", segment);
        if (previous == null) {
            JsonObject row = observation.deepCopy(); JsonArray proofs = new JsonArray(); proofs.add(observation.deepCopy());
            row.add("proofs", proofs); row.addProperty("oldest_tick", tick); row.addProperty("latest_tick", tick); return row;
        }
        previous.getAsJsonArray("proofs").add(observation.deepCopy());
        previous.addProperty("verified_connection", flag(previous, "verified_connection") && flag(observation, "verified_connection"));
        previous.addProperty("operational", flag(previous, "operational") && flag(observation, "operational"));
        previous.addProperty("native_support", flag(previous, "native_support") && flag(observation, "native_support"));
        previous.addProperty("detail_truncated", flag(previous, "detail_truncated") || flag(observation, "detail_truncated"));
        if (rank(text(observation, "status")) > rank(text(previous, "status"))) previous.addProperty("status", text(observation, "status"));
        long oldest = Math.min(integer(previous, "oldest_tick"), tick), latest = Math.max(integer(previous, "latest_tick"), tick);
        previous.addProperty("tick", oldest); previous.addProperty("oldest_tick", oldest); previous.addProperty("latest_tick", latest);
        if (!Objects.equals(text(previous, "reason"), text(observation, "reason"))) previous.addProperty("reason", "multiple_native_observations_see_proofs");
        return previous;
    }
}
