// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionDesignCompiler;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidence;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidence.ObservationFreshness;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidence.ObservationKind;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidence.FreshnessStatus;

/** Resolves native facts incrementally, using the exact anchor and operation receipts of this task. */
final class ProductionPreparation {
    private static final long MAX_STAGE_TICKS = 3_600;
    private final LocalPlayer player;
    private final ProductionRunPlan plan;
    private final ProductionWork work;
    private final ProductionNativeEvidence evidence;
    private ProductionReadSchedule reads;
    private final ProductionConnectionSurvey connectionSurvey;
    private final Map<String, JsonObject> connections = new java.util.LinkedHashMap<>();
    private final java.util.List<Link> remainingLinks = new java.util.ArrayList<>();
    private final java.util.Set<String> refreshedLinks = new java.util.HashSet<>();
    private Link activeLink;
    private int linkIndex;
    private boolean connectionsStarted;
    private long stageStarted = -1;
    private int acceptedNewFacts;
    private ProductionDesignCompiler.Compilation compilation;

    ProductionPreparation(LocalPlayer player, ProductionRunPlan plan, ProductionWork work) {
        this.player = player; this.plan = plan; this.work = work;
        evidence = new ProductionNativeEvidence(plan.manifest());
        evidence.bind(plan.dimension(), new Point(plan.anchor().getX(), plan.anchor().getY(), plan.anchor().getZ()));
        updateOperations();
        reads = new ProductionReadSchedule(plan);
        remainingLinks.addAll(plan.manifest().links());
        connectionSurvey = new ProductionConnectionSurvey(player, plan, work, resource -> {
            if (!plan.bound()) return evidence.resolve(resource);
            return new org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.Binding(
                    new org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.Check(
                            org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.Status.VERIFIED,
                            "frozen_native_identity", "The compiled resource identity remains pinned for this run"),
                    plan.resolvedResource(resource), plan.resourceIdentity(resource));
        });
    }

    void configured(String id, JsonObject response) { evidence.configured(id, response); }
    void observedConfiguration(String id, JsonObject response) { evidence.observeConfiguration(id, response); }
    void initialSupply(String source, Resource resource, long amount, JsonObject snapshot) {
        evidence.initialSupply(source, resource, amount, snapshot);
    }
    void supplied(String source, Resource resource, String requestId, JsonObject result) {
        evidence.confirmedSourceSupply(source, resource, requestId, result);
    }
    void refresh() {
        reads.requireSettled();
        connectionSurvey.reset();
        linkIndex = 0; reads = new ProductionReadSchedule(plan);
        connectionsStarted = false;
        stageStarted = -1; acceptedNewFacts = 0; activeLink = null;
        remainingLinks.clear(); remainingLinks.addAll(plan.manifest().links()); refreshedLinks.clear();
        compilation = null; connections.clear(); updateOperations();
    }
    private void updateOperations() {
        var supported = new java.util.LinkedHashSet<String>();
        for (var c : plan.manifest().configurations())
            if (org.maiwithu.maicraft.client.server.ServerAssistClient.supported(c.operation())
                    || org.maiwithu.maicraft.client.server.ServerAssistClient.renegotiating(c.operation())) supported.add(c.operation());
        evidence.supportedOperations(supported);
    }

    boolean tick() {
        long now = player.level().getGameTime();
        if (stageStarted < 0) stageStarted = now;
        if (now - stageStarted > MAX_STAGE_TICKS) throw new IllegalArgumentException("production_preparation_budget_exhausted: native facts did not settle within 3600 ticks");
        ProductionReadSchedule.Read read = reads.next(player.position());
        if (read != null) {
            if (!reads.pending() && !work.observe(read.position())) return false;
            var before = evidence.freshness(now);
            reads.submitted();
            JsonObject result = work.request(read.operation(), read.body(), false);
            if (result != null) { reads.complete(evidence, result); noteProgress(before); }
            return false;
        }
        evidence.advance(player.level().getGameTime());
        if (!connectionsStarted) {
            if (!bindingsReady()) {
                if (refreshExpiredFacts(false)) return false;
                compilation = ProductionDesignCompiler.compile(plan.authoredJson(), evidence); return true;
            }
            connectionsStarted = true;
        }
        if (!remainingLinks.isEmpty()) {
            if (activeLink == null) { connectionSurvey.reset(); activeLink = connectionSurvey.nearestLink(remainingLinks); }
            var before = evidence.freshness(now);
            JsonObject result = connectionSurvey.tick(activeLink);
            if (result != null) {
                evidence.observeLink(activeLink.id(), result); connections.put(activeLink.id(), result.deepCopy());
                remainingLinks.remove(activeLink); activeLink = null; linkIndex++; noteProgress(before);
            }
            return false;
        }
        evidence.advance(player.level().getGameTime());
        if (refreshExpiredFacts(true)) return false;
        compilation = ProductionDesignCompiler.compile(plan.authoredJson(), evidence);
        return true;
    }

    private boolean bindingsReady() {
        var verified = org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.Status.VERIFIED;
        return plan.manifest().nodes().stream().filter(node -> node.kind().equals("process")).allMatch(node -> evidence.bindRecipe(node).check().status() == verified)
                && plan.manifest().links().stream().allMatch(link -> evidence.resolve(link.resource()).check().status() == verified)
                && evidence.resolve(plan.manifest().target().resource()).check().status() == verified;
    }
    private boolean refreshExpiredFacts(boolean includeLinks) {
        var facts = evidence.freshness(player.level().getGameTime()).stream().filter(fact -> includeLinks || fact.kind() != ObservationKind.LINK).toList();
        if (facts.stream().anyMatch(fact -> fact.status() == FreshnessStatus.MISSING || fact.status() == FreshnessStatus.INVALID)) return false;
        boolean scheduled = false;
        for (var fact : facts) {
            if (fact.status() != FreshnessStatus.EXPIRED && fact.status() != FreshnessStatus.INVALIDATED) continue;
            if (fact.kind() == ObservationKind.LINK) {
                if (!refreshedLinks.add(fact.id())) continue;
                remainingLinks.add(plan.manifest().links().stream().filter(link -> link.id().equals(fact.id())).findFirst().orElseThrow());
                connections.remove(fact.id()); scheduled = true;
            } else scheduled |= reads.refresh(fact);
        }
        if (scheduled) connectionsStarted = false;
        return scheduled;
    }
    private void noteProgress(java.util.List<ObservationFreshness> before) {
        int accepted = 0;
        for (var fact : evidence.freshness(player.level().getGameTime())) {
            if (fact.status() != FreshnessStatus.FRESH || fact.tick() == null) continue;
            var previous = before.stream().filter(old -> old.kind() == fact.kind() && old.id().equals(fact.id())).findFirst().orElse(null);
            if (previous == null || previous.tick() == null || fact.tick() > previous.tick()) accepted++;
        }
        acceptedNewFacts += accepted;
        if (accepted > 0) work.extendDeadlineTo(Math.min(stageStarted + MAX_STAGE_TICKS, player.level().getGameTime() + 1_200));
    }
    org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.Check finalVerification() {
        evidence.advance(player.level().getGameTime()); return evidence.finalVerification();
    }
    ProductionDesignCompiler.Compilation compilation() { return compilation; }
    Map<String, Object> report() {
        var result = new java.util.LinkedHashMap<String, Object>(progress());
        if (compilation != null) result.put("plan", compilation.report());
        return Map.copyOf(result);
    }
    Map<String, Object> progress() {
        var result = new java.util.LinkedHashMap<String, Object>(reads.progress());
        result.put("status", compilation == null ? "observing" : "compiled"); result.put("connections", connections.size());
        result.put("connection_requests_completed", linkIndex); result.put("connection_requests_total", plan.manifest().links().size() + refreshedLinks.size());
        result.put("connection_refreshes_used", refreshedLinks.size()); result.put("accepted_new_facts", acceptedNewFacts);
        if (activeLink != null) { result.put("read_operation", "machine.connections"); result.put("read_subject", activeLink.id()); }
        result.put("oldest_fact_age_ticks", evidence.freshness(player.level().getGameTime()).stream().filter(fact -> fact.tick() != null)
                .mapToLong(ObservationFreshness::ageTicks).max().orElse(0));
        result.put("stage_ticks_remaining", stageStarted < 0 ? MAX_STAGE_TICKS : Math.max(0, stageStarted + MAX_STAGE_TICKS - player.level().getGameTime()));
        return Map.copyOf(result);
    }
}
