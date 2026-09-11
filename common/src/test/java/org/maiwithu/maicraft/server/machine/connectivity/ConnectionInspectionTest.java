// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/** Pure boundary tests: a plausible layout or incomplete native evidence cannot certify a path. */
public final class ConnectionInspectionTest {
    private ConnectionInspectionTest() {}

    public static void main(String[] args) {
        missingAndUnboundedPathsAreRejected();
        gapsLoopsAndWrongFacesAreRejected();
        gearsPermitOnlyLocalExplicitDiagonalSteps();
        anyUnverifiedEdgeKeepsWholePathUnverified();
        topologyNeverProvesFlowOrProduction();
        largeReportsRetainEveryVerdictInsideReceiptBudget();
        System.out.println("ConnectionInspectionTest: 6 policy groups passed");
    }

    private static void missingAndUnboundedPathsAreRejected() {
        rejects("{system:'mekanism',medium:'items'}");
        rejects("{system:'mekanism',medium:'items',path:[]}");
        rejects("{system:'mekanism',medium:'items',path:[{x:0,y:0,z:0}]}");
        rejects("{system:'guess',medium:'items',path:[{x:0,y:0,z:0},{x:1,y:0,z:0}]}");
        rejects("{system:'mekanism',medium:'items',path:[{x:0.1,y:0,z:0},{x:1,y:0,z:0}]}");
        rejects("{system:'mekanism',medium:'items',path:[{x:0,y:0,z:0},{x:1e40,y:0,z:0}]}");
        rejects("{system:'mekanism',medium:'items',item_color:{anything:true},path:[{x:0,y:0,z:0},{x:1,y:0,z:0}]}");
        JsonObject request = new JsonObject(); request.addProperty("system", "mekanism"); request.addProperty("medium", "items");
        JsonArray path = new JsonArray(); request.add("path", path);
        for (int i = 0; i < 129; i++) path.add(new ConnectionPath.Point(i, 0, 0).json());
        boolean rejected = false;
        try { ConnectionPath.parse(request); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "oversized path must be segmented explicitly");
    }

    private static void gapsLoopsAndWrongFacesAreRejected() {
        rejects("{system:'mekanism',medium:'items',path:[{x:0,y:0,z:0},{x:2,y:0,z:0}]}");
        rejects("{system:'ae2',medium:'items',path:[{x:0,y:0,z:0},{x:1,y:0,z:0},{x:0,y:0,z:0}]}");
        rejects("{system:'mekanism',medium:'items',from_face:'west',path:[{x:0,y:0,z:0},{x:1,y:0,z:0}]}");
        rejects("{system:'mekanism',medium:'items',to_face:'east',path:[{x:0,y:0,z:0},{x:1,y:0,z:0}]}");
        ConnectionPath valid = parse("{system:'mekanism',medium:'items',from_face:'east',to_face:'west',"
                + "path:[{x:0,y:0,z:0},{x:1,y:0,z:0}]}");
        check(valid.positions().size() == 2, "endpoint faces are oriented outward toward the path");
    }

    private static void gearsPermitOnlyLocalExplicitDiagonalSteps() {
        parse("{system:'create',medium:'kinetic',path:[{x:0,y:0,z:0},{x:1,y:1,z:0}]}");
        rejects("{system:'create',medium:'kinetic',path:[{x:0,y:0,z:0},{x:1,y:1,z:1}]}");
        rejects("{system:'create',medium:'kinetic',path:[{x:0,y:0,z:0},{x:2,y:0,z:0}]}");
        rejects("{system:'mekanism',medium:'items',path:[{x:0,y:0,z:0},{x:1,y:1,z:0}]}");
        rejects("{system:'ae2',medium:'energy',path:[{x:0,y:0,z:0},{x:1,y:1,z:0}]}");
    }

    private static void anyUnverifiedEdgeKeepsWholePathUnverified() {
        ConnectionEvidence verified = ConnectionEvidence.of("verified", true, true, "explicit_native_edge", "test");
        for (String status : List.of("planned", "unknown", "unsupported")) {
            ConnectionEvidence gap = ConnectionEvidence.of(status, false, false, "same_grid_without_edge", "test");
            JsonObject report = ConnectionEvidence.summarize(List.of(verified, gap, verified));
            check(!report.get("verified_connection").getAsBoolean(), "a single " + status + " edge must block certification");
            check(report.get("status").getAsString().equals(status), "aggregate must preserve the worst status");
            check(!report.get("operational").getAsBoolean(), "an incomplete route cannot be operational");
        }
        check(!ConnectionEvidence.summarize(List.of()).get("verified_connection").getAsBoolean(), "empty evidence is not proof");
        ConnectionEvidence inconsistent = ConnectionEvidence.of("verified", false, false, "missing_edge", "test");
        check(ConnectionEvidence.summarize(List.of(inconsistent)).get("status").getAsString().equals("unknown"),
                "a backend status label cannot override missing topology evidence");
    }

    private static void topologyNeverProvesFlowOrProduction() {
        ConnectionEvidence nativeEdge = ConnectionEvidence.of("verified", true, true, "native_edge", "test");
        JsonObject report = ConnectionEvidence.summarize(List.of(nativeEdge, nativeEdge));
        check(report.get("verified_connection").getAsBoolean(), "complete native edges can certify topology");
        check(report.get("resource_compatibility").getAsString().equals("unknown"), "topology says nothing about item filters");
        check(!report.get("flow_verified").getAsBoolean(), "topology cannot certify real movement");
        check(!report.get("production_verified").getAsBoolean(), "topology cannot certify sustained output");
        ConnectionEvidence unpowered = ConnectionEvidence.of("planned", true, false, "grid_unpowered", "test");
        JsonObject idle = ConnectionEvidence.summarize(List.of(nativeEdge, unpowered));
        check(idle.get("verified_connection").getAsBoolean(), "connected but unpowered topology remains distinguishable");
        check(!idle.get("operational").getAsBoolean(), "native power failure blocks readiness");
    }

    private static ConnectionPath parse(String text) { return ConnectionPath.parse(JsonParser.parseString(text).getAsJsonObject()); }

    private static void largeReportsRetainEveryVerdictInsideReceiptBudget() {
        JsonObject report = new JsonObject(); JsonArray edges = new JsonArray(), intermediate = new JsonArray(), path = new JsonArray();
        report.add("edges", edges); report.add("intermediate", intermediate); report.add("path", path);
        for (int i = 0; i < 128; i++) {
            path.add(new ConnectionPath.Point(29_999_999 - i, 2048, -29_999_999).json());
            if (i == 127) continue;
            ConnectionEvidence edge = ConnectionEvidence.of("unknown", false, false,
                    "same_block_grid_nodes_have_no_direct_internal_edge", "AE2.IGridNode.getInWorldConnections+IGridConnection.getOtherSide");
            JsonObject row = edge.json(); row.addProperty("index", i);
            row.add("from", path.get(i).deepCopy()); row.add("to", path.get(i).deepCopy());
            row.addProperty("native_detail", "x".repeat(300)); edges.add(row);
            if (i > 0) { JsonObject middle = edge.json(); middle.addProperty("index", i); intermediate.add(middle); }
        }
        JsonObject route = new JsonObject(); route.addProperty("sample_identity", "x".repeat(100_000));
        route.addProperty("sample_resource_id", "items:minecraft:iron_ingot#exact-component-hash"); report.add("item_route", route);
        ConnectionResponseBudget.fit(report);
        check(report.toString().length() <= ConnectionResponseBudget.MAX_CHARS, "native report must fit inside receipt budget");
        check(report.get("detail_truncated").getAsBoolean(), "omitted diagnostics must be explicit");
        check(edges.size() == 127 && intermediate.size() == 126, "budgeting must retain every edge and internal-node verdict");
        for (var edge : edges) {
            check(edge.getAsJsonObject().has("status") && edge.getAsJsonObject().has("reason")
                    && edge.getAsJsonObject().has("native_support"), "all actionable verdicts survive compaction");
        }
        check(route.has("sample_resource_id"), "exact component identity key survives large component omission");
    }

    private static void rejects(String text) {
        boolean rejected = false;
        try { parse(text); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid path unexpectedly accepted: " + text);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
