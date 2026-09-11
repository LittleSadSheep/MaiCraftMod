// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;

/** Real server response shapes, including its actual response-budget compactor; no world connectivity is fabricated. */
public final class ProductionConnectionResponsesTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String RESOURCE = "items:minecraft:iron_ingot#" + "0123456789abcdef".repeat(4);
    private ProductionConnectionResponsesTest() {}

    public static void main(String[] args) throws Exception {
        singleAndStoppedPaths();
        ae2BoundaryRequiresAnInternalNodeProof();
        compactCoordinatesAndAggregateFlagsAreRecovered();
        malformedRepliesRemainUnknown();
        sharedEvidenceUsesConjunction();
        oldestObservationControlsExpiry();
        onlyExactWholePathSamplesCertifyResources();
        reportsAndAcceptedRepliesAreCopied();
        System.out.println("ProductionConnectionResponsesTest: 8 aggregation groups passed; native world acceptance is not exercised");
    }

    private static void singleAndStoppedPaths() {
        List<BlockPos> path = path(4);
        JsonObject reply = reply(path, "create", "kinetic", 100, true, false);
        var responses = new ProductionConnectionResponses(path, DIMENSION, "kinetic", "rpm");
        responses.accept(0, 3, "create", reply);
        JsonObject result = responses.finish(100);
        check(yes(result, "complete") && yes(result, "verified_connection") && !yes(result, "operational"), "stopped kinetic topology remains proven");
        check(result.get("status").getAsString().equals("planned"), "unpowered topology must stay planned");
        check(!yes(result, "flow_verified") && !yes(result, "production_verified"), "topology cannot prove flow or production");
    }

    private static void ae2BoundaryRequiresAnInternalNodeProof() {
        List<BlockPos> path = path(7);
        var proper = responses(path);
        proper.accept(0, 3, "ae2", reply(path.subList(0, 4), "ae2", "items", 100, true, true));
        proper.accept(2, 6, "ae2", reply(path.subList(2, 7), "ae2", "items", 110, true, true));
        JsonObject joined = proper.finish(110);
        check(yes(joined, "complete") && yes(joined, "verified_connection"), "two-vertex overlap proves every AE2 internal node");
        check(joined.getAsJsonArray("intermediate").size() == 5, "full path has five internal proofs");
        JsonObject shared = joined.getAsJsonArray("edges").get(2).getAsJsonObject();
        check(shared.getAsJsonArray("proofs").size() == 2 && shared.get("oldest_tick").getAsLong() == 100
                && shared.get("latest_tick").getAsLong() == 110, "shared edge retains both observations and ticks");
        var missing = responses(path);
        missing.accept(0, 3, "ae2", reply(path.subList(0, 4), "ae2", "items", 100, true, true));
        missing.accept(3, 6, "ae2", reply(path.subList(3, 7), "ae2", "items", 100, true, true));
        check(!yes(missing.finish(100), "complete") && !yes(missing.finish(100), "verified_connection"), "one-vertex overlap leaves an unproven multipart junction");
    }

    private static void compactCoordinatesAndAggregateFlagsAreRecovered() throws Exception {
        List<BlockPos> path = path(128);
        JsonObject raw = reply(path, "ae2", "items", 100, true, true);
        for (String name : List.of("edges", "intermediate")) for (var item : raw.getAsJsonArray(name)) {
            JsonObject row = item.getAsJsonObject(); row.addProperty("same_grid", true); row.addProperty("booted", true);
            row.addProperty("powered", true); row.addProperty("channels_satisfied", true); row.addProperty("observation_grid_identity", "12345678");
        }
        Class<?> budget = Class.forName("org.maiwithu.maicraft.server.machine.connectivity.ConnectionResponseBudget");
        var fit = budget.getDeclaredMethod("fit", JsonObject.class); fit.setAccessible(true);
        JsonObject compact = (JsonObject) fit.invoke(null, raw);
        check(yes(compact, "detail_truncated") && !compact.getAsJsonArray("edges").get(0).getAsJsonObject().has("from"), "actual server compactor produces index-bound edges");
        var responses = responses(path); responses.accept(0, 127, "ae2", compact);
        check(yes(responses.finish(100), "verified_connection"), "real compacted coordinates reconstruct the exact path");
        for (String name : List.of("edges", "intermediate")) for (var item : compact.getAsJsonArray(name)) {
            item.getAsJsonObject().remove("verified_connection"); item.getAsJsonObject().remove("operational");
        }
        var omitted = responses(path); omitted.accept(0, 127, "ae2", compact);
        JsonObject result = omitted.finish(100);
        check(yes(result, "verified_connection") && yes(result, "detail_truncated"), "true aggregate restores only omitted positive flags");
        check(result.getAsJsonArray("segments").get(0).getAsJsonObject().has("evidence_provenance"), "compacted provenance survives aggregation");
        compact.getAsJsonArray("edges").get(0).getAsJsonObject().addProperty("verified_connection", false);
        var conflict = responses(path); conflict.accept(0, 127, "ae2", compact);
        check(!yes(conflict.finish(100), "verified_connection"), "explicit false is never overwritten by a true aggregate");
    }

    private static void malformedRepliesRemainUnknown() {
        List<BlockPos> path = path(5);
        List<Consumer<JsonObject>> mutations = List.of(
                r -> r.addProperty("dimension", "minecraft:the_nether"), r -> r.addProperty("system", "create"),
                r -> r.addProperty("medium", "energy"), r -> r.addProperty("tick", 100.5), r -> r.addProperty("tick", -1),
                r -> r.addProperty("complete", false), r -> r.remove("path"),
                r -> r.getAsJsonArray("path").get(2).getAsJsonObject().addProperty("x", 99),
                r -> r.getAsJsonArray("edges").get(1).getAsJsonObject().addProperty("index", 0),
                r -> r.getAsJsonArray("edges").get(0).getAsJsonObject().addProperty("index", 0.5),
                r -> r.getAsJsonArray("edges").get(0).getAsJsonObject().addProperty("from_index", 99),
                r -> r.getAsJsonArray("edges").get(0).getAsJsonObject().getAsJsonObject("to").addProperty("x", 4),
                r -> r.getAsJsonArray("intermediate").remove(0),
                r -> r.getAsJsonArray("intermediate").get(0).getAsJsonObject().addProperty("index", 2),
                r -> { var rows = r.getAsJsonArray("edges"); var a = rows.get(0); rows.set(0, rows.get(1)); rows.set(1, a); },
                r -> r.getAsJsonArray("edges").get(0).getAsJsonObject().remove("verified_connection"));
        for (Consumer<JsonObject> mutation : mutations) {
            JsonObject reply = reply(path, "ae2", "items", 100, true, true); mutation.accept(reply);
            var responses = responses(path); responses.accept(0, 4, "ae2", reply); JsonObject result = responses.finish(100);
            check(!yes(result, "complete") && !yes(result, "verified_connection") && result.get("status").getAsString().equals("unknown"), "malformed native evidence must fail closed: " + reply);
        }
    }

    private static void sharedEvidenceUsesConjunction() {
        List<BlockPos> path = path(7); var responses = responses(path);
        responses.accept(0, 3, "mekanism", reply(path.subList(0, 4), "mekanism", "items", 100, true, true));
        JsonObject later = reply(path.subList(2, 7), "mekanism", "items", 110, false, false);
        responses.accept(2, 6, "mekanism", later); JsonObject result = responses.finish(110);
        check(yes(result, "complete") && !yes(result, "verified_connection"), "an observed later disconnection defeats an earlier positive edge");
        check(!yes(result.getAsJsonArray("edges").get(2).getAsJsonObject(), "verified_connection"), "shared edge is the conjunction of both observations");
        check(result.getAsJsonArray("segments").get(1).getAsJsonObject().get("maicraft_request_id").getAsString().equals("fixture-110"), "actual business receipt ID is retained");
        for (String status : List.of("unknown", "unsupported")) {
            JsonObject negative = reply(path, "mekanism", "items", 100, false, false); negative.addProperty("status", status);
            for (var edge : negative.getAsJsonArray("edges")) {
                edge.getAsJsonObject().addProperty("status", status);
                edge.getAsJsonObject().addProperty("native_support", !status.equals("unsupported"));
            }
            var rejected = responses(path); rejected.accept(0, 6, "mekanism", negative);
            check(!yes(rejected.finish(100), "verified_connection") && rejected.finish(100).get("status").getAsString().equals(status),
                    "unknown and unsupported native adapters remain nonaffirmative");
        }
    }

    private static void oldestObservationControlsExpiry() {
        List<BlockPos> path = path(7); var responses = responses(path);
        responses.accept(0, 3, "ae2", reply(path.subList(0, 4), "ae2", "items", 100, true, true));
        responses.accept(2, 6, "ae2", reply(path.subList(2, 7), "ae2", "items", 1300, true, true));
        JsonObject boundary = responses.finish(1300);
        check(yes(boundary, "verified_connection") && boundary.get("tick").getAsLong() == 100
                && boundary.get("observation_span_ticks").getAsLong() == 1200 && yes(boundary, "non_atomic"), "TTL boundary uses the oldest segment without washing its timestamp");
        check(!yes(responses.finish(1301), "verified_connection") && responses.finish(1301).get("reason").getAsString().equals("refresh_required"), "age 1201 requires refresh");
        check(!yes(responses.finish(1299), "verified_connection"), "future segment tick is rejected");
        var longSpan = responses(path);
        longSpan.accept(0, 3, "ae2", reply(path.subList(0, 4), "ae2", "items", 100, true, true));
        longSpan.accept(2, 6, "ae2", reply(path.subList(2, 7), "ae2", "items", 1301, true, true));
        check(!yes(longSpan.finish(1301), "verified_connection"), "a span longer than TTL cannot be refreshed by its last part");
    }

    private static void onlyExactWholePathSamplesCertifyResources() {
        List<BlockPos> path = path(5); JsonObject full = reply(path, "mekanism", "items", 100, true, true); sample(full, RESOURCE);
        var exact = responses(path); exact.accept(0, 4, "mekanism", full);
        check(exact.finish(100).get("resource_compatibility").getAsString().equals("verified"), "a whole-path exact native sample may certify resource compatibility");
        sample(full, RESOURCE + "different-component"); var wrong = responses(path); wrong.accept(0, 4, "mekanism", full);
        check(wrong.finish(100).get("resource_compatibility").getAsString().equals("unknown"), "same registry family cannot replace an opaque component identity");
        sample(full, RESOURCE); full.getAsJsonObject("item_route").addProperty("status", "unknown");
        var unknown = responses(path); unknown.accept(0, 4, "mekanism", full);
        check(unknown.finish(100).get("resource_compatibility").getAsString().equals("unknown"), "a partial sample status cannot certify compatibility");
        var segmented = responses(path);
        for (int start : new int[]{0, 1}) {
            int end = start == 0 ? 2 : 4; JsonObject part = reply(path.subList(start, end + 1), "mekanism", "items", 100, true, true);
            sample(part, RESOURCE); segmented.accept(start, end, "mekanism", part);
        }
        check(yes(segmented.finish(100), "verified_connection") && segmented.finish(100).get("resource_compatibility").getAsString().equals("unknown"), "matching segment samples never certify the whole route");
        var partial = responses(path); partial.accept(0, 2, "mekanism", reply(path.subList(0, 3), "mekanism", "items", 100, true, true));
        check(!yes(partial.finish(100), "complete"), "a single partial path is not whole-path evidence");
    }

    private static void reportsAndAcceptedRepliesAreCopied() {
        List<BlockPos> path = path(3); JsonObject reply = reply(path, "mekanism", "items", 100, true, true);
        var responses = responses(path); responses.accept(0, 2, "mekanism", reply); reply.getAsJsonArray("edges").remove(0);
        JsonObject report = responses.finish(100); report.getAsJsonArray("edges").remove(0);
        check(responses.finish(100).getAsJsonArray("edges").size() == 2, "callers cannot mutate stored proof by changing an input or report");
    }

    private static ProductionConnectionResponses responses(List<BlockPos> path) { return new ProductionConnectionResponses(path, DIMENSION, "items", RESOURCE); }
    private static List<BlockPos> path(int count) { List<BlockPos> result = new ArrayList<>(); for (int i = 0; i < count; i++) result.add(new BlockPos(i, 64, 0)); return result; }
    private static JsonObject reply(List<BlockPos> path, String system, String medium, long tick, boolean connected, boolean operational) {
        JsonObject result = new JsonObject(); result.addProperty("schema", "maicraft.connection_inspection.v1");
        result.addProperty("dimension", DIMENSION); result.addProperty("system", system); result.addProperty("medium", medium);
        result.addProperty("tick", tick); result.addProperty("status", operational ? "verified" : "planned");
        result.addProperty("complete", true); result.addProperty("verified_connection", connected); result.addProperty("operational", operational);
        result.addProperty("resource_compatibility", "unknown"); result.addProperty("detail_truncated", false);
        result.addProperty("maicraft_request_id", "fixture-" + tick);
        JsonArray positions = new JsonArray(), edges = new JsonArray(), intermediate = new JsonArray();
        path.forEach(position -> positions.add(ProductionConnectionResponseRows.point(position)));
        for (int i = 0; i < path.size() - 1; i++) {
            JsonObject row = row(i, connected, operational); row.add("from", ProductionConnectionResponseRows.point(path.get(i)));
            row.add("to", ProductionConnectionResponseRows.point(path.get(i + 1))); edges.add(row);
        }
        if (system.equals("ae2")) for (int i = 1; i < path.size() - 1; i++) intermediate.add(row(i, connected, operational));
        result.add("path", positions); result.add("edges", edges); result.add("intermediate", intermediate); return result;
    }
    private static JsonObject row(int index, boolean connected, boolean operational) {
        JsonObject row = new JsonObject(); row.addProperty("index", index); row.addProperty("status", operational ? "verified" : "planned");
        row.addProperty("native_support", true); row.addProperty("verified_connection", connected); row.addProperty("operational", operational);
        row.addProperty("reason", "native_directional_connection_observed");
        row.addProperty("provenance", "AE2.IGridNode.getInWorldConnections+IGridConnection.getOtherSide"); row.addProperty("flow_verified", false); return row;
    }
    private static void sample(JsonObject reply, String id) {
        reply.addProperty("resource_compatibility", "verified"); JsonObject sample = new JsonObject();
        sample.addProperty("status", "verified"); sample.addProperty("sample_resource_id", id); sample.addProperty("sample_amount", 1);
        sample.addProperty("sample_identity_omitted", true); sample.addProperty("flow_verified", false); reply.add("item_route", sample);
    }
    private static boolean yes(JsonObject value, String key) { return value.has(key) && value.get(key).getAsBoolean(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
