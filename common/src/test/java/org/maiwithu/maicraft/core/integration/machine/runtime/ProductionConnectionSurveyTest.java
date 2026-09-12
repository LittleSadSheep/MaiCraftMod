// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionConnectionFixture.*;

public final class ProductionConnectionSurveyTest {
    private ProductionConnectionSurveyTest() {}
    public static void main(String[] args) {
        aLongPathKeepsEveryEdgeAndBoundaryNode();
        aPendingRequestKeepsItsIdentityAndConsumesBeforeWorldFailure();
        opaqueBindingsRemainSeparateFromRegistryIdentifiers();
        diagonalPathsUseActualDistanceAndDerivedFaces();
        malformedPathsAndMissingAdaptersDoNotIssueRequests();
        longSurveysExposeStaleEvidence();
        nativeRepliesCanLeadTheClientClock();
        ProductionConnectionOrderingTest.main(args);
        System.out.println("ProductionConnectionSurveyTest: 7 orchestration groups passed; native game connections are not exercised");
    }

    private static void aLongPathKeepsEveryEdgeAndBoundaryNode() {
        List<BlockPos> path = line(-100, 100);
        var fixture = new ProductionConnectionFixture(path, "items", "ae2"); fixture.navDelay = 2;
        JsonObject report = fixture.settle();
        check(fixture.sent.size() > 1, "A long path bypassed segmentation");
        check(report.get("verified_connection").getAsBoolean(), "Complete segment proofs did not establish topology");
        check(report.getAsJsonArray("edges").size() == path.size() - 1, "Authored edges were skipped or duplicated");
        check(report.getAsJsonArray("intermediate").size() == path.size() - 2, "An AE2 boundary node lost internal evidence");
        check(report.get("resource_compatibility").getAsString().equals("unknown"), "Local samples became end-to-end resource proof");
        for (int i = 1; i < fixture.sent.size(); i++) {
            var before = fixture.sent.get(i - 1).getAsJsonArray("path"); var after = fixture.sent.get(i).getAsJsonArray("path");
            check(before.get(before.size() - 2).equals(after.get(0)) && before.get(before.size() - 1).equals(after.get(1)),
                    "Segments did not share the edge required for intermediate proof");
            check(after.size() <= 128, "Native path size limit exceeded");
        }
    }

    private static void aPendingRequestKeepsItsIdentityAndConsumesBeforeWorldFailure() {
        var fixture = new ProductionConnectionFixture(line(0, 2), "items", "mekanism");
        var link = fixture.plan.manifest().links().getFirst();
        check(fixture.survey.tick(link) == null && fixture.pending != null, "Fixture did not open an async request");
        JsonObject sent = fixture.pending.deepCopy();
        boolean resetRejected = false;
        try { fixture.survey.reset(); } catch (IllegalStateException expected) { resetRejected = true; }
        check(resetRejected, "Reset abandoned the shared request slot");
        fixture.exact = "different opaque key"; fixture.registry = "minecraft:gold_ingot"; fixture.dimension = "minecraft:the_nether";
        JsonObject result = fixture.survey.tick(link);
        check(fixture.pending == null && fixture.sent.size() == 1 && fixture.sent.getFirst().equals(sent), "World change replaced or abandoned the original request");
        check(!result.get("verified_connection").getAsBoolean() && result.get("status").getAsString().equals("unknown"), "Cross-world reply certified a path");
        fixture.survey.reset();
    }

    private static void opaqueBindingsRemainSeparateFromRegistryIdentifiers() {
        var fixture = new ProductionConnectionFixture(line(0, 10), "items", "mekanism");
        var link = fixture.plan.manifest().links().getFirst(); fixture.survey.tick(link);
        String exact = fixture.exact, registry = fixture.registry;
        fixture.registry = "minecraft:gold_ingot"; fixture.bindingVerified = false;
        fixture.settle();
        for (JsonObject query : fixture.sent) {
            check(query.get("resource").getAsString().equals(registry), "Registry selector was parsed from the opaque key or changed midway");
            check(query.get("resource_id").getAsString().equals(exact), "Exact component key was lost");
        }
        var unknown = new ProductionConnectionFixture(line(0, 2), "items", "mekanism"); unknown.bindingVerified = false;
        JsonObject result = unknown.settle();
        check(!unknown.sent.getFirst().has("resource") && !unknown.sent.getFirst().has("resource_id"), "Unknown binding leaked an authored key as a registry ID");
        check(result.get("resource_compatibility").getAsString().equals("unknown"), "Unbound sample became desired-resource proof");
    }

    private static void diagonalPathsUseActualDistanceAndDerivedFaces() {
        List<BlockPos> path = new ArrayList<>(); path.add(new BlockPos(-1, 0, 0));
        for (int i = 0; i < 30; i++) path.add(new BlockPos(i, i, 0)); path.add(new BlockPos(30, 29, 0));
        var fixture = new ProductionConnectionFixture(path, "kinetic", null);
        check(fixture.settle().get("verified_connection").getAsBoolean(), "Native diagonal shape was not preserved");
        for (JsonObject query : fixture.sent) {
            var points = query.getAsJsonArray("path"); BlockPos first = position(points.get(0).getAsJsonObject()), second = position(points.get(1).getAsJsonObject());
            String from = ProductionConnectionPath.face(first, second);
            check(from == null ? !query.has("from_face") : query.get("from_face").getAsString().equals(from), "Segment borrowed a face from another endpoint");
        }
    }

    private static void malformedPathsAndMissingAdaptersDoNotIssueRequests() {
        var gap = new ProductionConnectionFixture(List.of(BlockPos.ZERO, new BlockPos(2, 0, 0)), "items", "mekanism");
        check(!gap.settle().get("verified_connection").getAsBoolean() && gap.sent.isEmpty(), "A missing path cell was repaired by invention");
        var loop = new ProductionConnectionFixture(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0), BlockPos.ZERO), "items", "mekanism");
        check(!loop.settle().get("verified_connection").getAsBoolean() && loop.sent.isEmpty(), "A repeated path position bypassed native policy");
        var unsupported = new ProductionConnectionFixture(line(0, 2), "items", null);
        check(!unsupported.settle().get("verified_connection").getAsBoolean() && unsupported.sent.isEmpty(), "An unobserved adapter was invented");
    }

    private static void longSurveysExposeStaleEvidence() {
        var fixture = new ProductionConnectionFixture(line(-100, 100), "items", "mekanism"); fixture.replyInterval = 30;
        JsonObject result = fixture.settle();
        check(!result.get("verified_connection").getAsBoolean(), "A newer segment washed the first segment's expired proof");
        check(result.get("tick").getAsLong() < result.get("latest_tick").getAsLong(), "Non-atomic observations lost their oldest timestamp");
        check(result.get("resource_compatibility").getAsString().equals("unknown"), "Stale topology gained resource compatibility");
    }

    private static void nativeRepliesCanLeadTheClientClock() {
        var fixture = new ProductionConnectionFixture(line(0, 2), "items", "mekanism"); fixture.serverLead = 3;
        JsonObject result = fixture.settle();
        check(result.get("verified_connection").getAsBoolean(), "A valid native reply was rejected because the client clock lagged");
        check(result.get("tick").getAsLong() == fixture.tick + 3, "Clock alignment rewrote the native observation's timestamp");
        var longPath = new ProductionConnectionFixture(line(-100, 100), "items", "mekanism");
        longPath.serverLead = 3; longPath.replyInterval = 30;
        check(!longPath.settle().get("verified_connection").getAsBoolean(), "Clock alignment bypassed the oldest sample's TTL");
    }
}
