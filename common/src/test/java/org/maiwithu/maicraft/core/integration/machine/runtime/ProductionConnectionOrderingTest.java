// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionConnectionFixture.*;

/** Spatial scheduling changes visitation only; native request bodies, identity, coverage and clocks stay exact. */
public final class ProductionConnectionOrderingTest {
    public static void main(String[] args) {
        reverseVisitsKeepOriginalQueriesAndAdapter();
        missingAuthoredAdapterKeepsTheOriginProbe();
        nearestLinkUsesCurrentBodyButNeverReplacesPending();
        onlyNewContextMatchingNativeTicksRenewTheDeadline();
        System.out.println("ProductionConnectionOrderingTest: spatial visitation and honest progress passed");
    }

    private static void reverseVisitsKeepOriginalQueriesAndAdapter() {
        var forward = new ProductionConnectionFixture(line(0, 20), "items", "mekanism"); forward.settle();
        var reverse = new ProductionConnectionFixture(line(0, 20), "items", "mekanism");
        var feet = new AtomicReference<>(new Vec3(20.5, .5, .5));
        var survey = new ProductionConnectionSurvey(reverse.plan, reverse, reverse::binding, () -> reverse.dimension,
                () -> reverse.tick, position -> position.getX() <= 4 ? "mekanism" : "ae2", feet::get);
        var link = reverse.plan.manifest().links().getFirst();
        check(survey.tick(link) == null && reverse.pending != null, "reverse survey must retain one pending operation");
        JsonObject pending = reverse.pending.deepCopy(); feet.set(new Vec3(.5, .5, .5));
        survey.tick(link);
        check(reverse.sent.getFirst().equals(pending), "body movement cannot rewrite an in-flight endpoint query");
        JsonObject report = settle(reverse, survey, link);
        check(reverse.sent.equals(forward.sent.reversed()), "reverse visits must preserve each complete original query body and face ordering");
        check(reverse.sent.stream().allMatch(query -> query.get("system").getAsString().equals("mekanism")),
                "the adapter must come from the authored first segment, not the nearer AE2-class endpoint");
        check(report.get("verified_connection").getAsBoolean() && report.getAsJsonArray("edges").size() == 20,
                "reverse responses must cover every original edge");
        check(report.get("tick").getAsLong() < report.get("latest_tick").getAsLong(), "reordering cannot manufacture a fresh aggregate timestamp");
        var ae = new ProductionConnectionFixture(line(0, 20), "items", "ae2");
        var aeSurvey = new ProductionConnectionSurvey(ae.plan, ae, ae::binding, () -> ae.dimension, () -> ae.tick,
                ignored -> "ae2", () -> new Vec3(20, 0, 0));
        JsonObject aeReport = settle(ae, aeSurvey, ae.plan.manifest().links().getFirst());
        check(aeReport.get("verified_connection").getAsBoolean() && aeReport.getAsJsonArray("intermediate").size() == 19,
                "reverse ordering must preserve shared-edge AE2 junction evidence");
    }

    private static void missingAuthoredAdapterKeepsTheOriginProbe() {
        var f = new ProductionConnectionFixture(line(0, 20), "items", "mekanism");
        var survey = new ProductionConnectionSurvey(f.plan, f, f::binding, () -> f.dimension, () -> f.tick,
                position -> f.observed == null ? null : "mekanism", () -> new Vec3(20, 0, 0));
        settle(f, survey, f.plan.manifest().links().getFirst());
        check(position(f.sent.getFirst().getAsJsonArray("path").get(0).getAsJsonObject()).equals(BlockPos.ZERO),
                "an unavailable authored adapter must be observed at the original first segment before requests");
    }

    private static void nearestLinkUsesCurrentBodyButNeverReplacesPending() {
        var f = new ProductionConnectionFixture(line(0, 20), "items", "mekanism");
        JsonObject combined = f.plan.authoredJson();
        for (String kind : List.of("nodes", "ports", "links")) {
            var originals = combined.getAsJsonArray(kind).deepCopy();
            for (var raw : originals) {
                JsonObject copy = raw.getAsJsonObject();
                for (String key : List.of("id", "node", "from", "to"))
                    if (copy.has(key)) copy.addProperty(key, copy.get(key).getAsString() + "_far");
                if (copy.has("offset")) copy.getAsJsonArray("offset").set(0,
                        new com.google.gson.JsonPrimitive(copy.getAsJsonArray("offset").get(0).getAsInt() + 40));
                if (copy.has("path")) for (var point : copy.getAsJsonArray("path")) point.getAsJsonArray().set(0,
                        new com.google.gson.JsonPrimitive(point.getAsJsonArray().get(0).getAsInt() + 40));
                combined.getAsJsonArray(kind).add(copy);
            }
        }
        var plan = new ProductionRunPlan(BlockPos.ZERO, WORLD, combined);
        var feet = new AtomicReference<>(new Vec3(60, 0, 0));
        var survey = new ProductionConnectionSurvey(plan, f, f::binding, () -> f.dimension, () -> f.tick, ignored -> "mekanism", feet::get);
        var links = plan.manifest().links(); var selected = survey.nearestLink(links);
        check(selected.id().equals("route_far"), "selection must use the current body's nearest segment endpoint");
        survey.tick(selected); feet.set(Vec3.ZERO);
        check(survey.nearestLink(links).equals(selected), "pending request must pin its selected link after the body moves");
        settle(f, survey, selected);
        check(survey.nearestLink(links).id().equals("route"), "a settled boundary may choose a nearer next link");
    }

    private static void onlyNewContextMatchingNativeTicksRenewTheDeadline() {
        for (String failure : List.of("world", "path", "malformed", "stale")) {
            var f = new ProductionConnectionFixture(line(0, 2), "items", "mekanism"); f.tick = 2000;
            f.replyEdit = reply -> {
                if (failure.equals("world")) reply.addProperty("dimension", "minecraft:the_nether");
                if (failure.equals("path")) reply.getAsJsonArray("path").get(0).getAsJsonObject().addProperty("x", 99);
                if (failure.equals("malformed")) reply.remove("tick");
                if (failure.equals("stale")) reply.addProperty("tick", 5);
                return reply;
            };
            check(!f.settle().get("verified_connection").getAsBoolean() && f.progressUpdates == 0,
                    "invalid or stale native replies cannot renew the task deadline: " + failure);
        }
        var old = new ProductionConnectionFixture(line(0, 5), "items", "mekanism"); old.tick = 2000;
        old.replyEdit = reply -> { reply.addProperty("tick", old.sent.size() == 1 ? 2000 : 1999); return reply; };
        check(old.settle().get("verified_connection").getAsBoolean() && old.progressUpdates == 1,
                "a later reply with an older tick may retain valid evidence but cannot claim new progress");
    }

    private static JsonObject settle(ProductionConnectionFixture f, ProductionConnectionSurvey survey,
                                     org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Link link) {
        for (int i = 0; i < 20000; i++) { f.tick++; JsonObject result = survey.tick(link); if (result != null) return result; }
        throw new AssertionError("spatial survey did not settle");
    }
}
