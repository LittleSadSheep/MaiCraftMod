// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionDesignCompiler;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidence;

/** Resolves native facts incrementally, using the exact anchor and operation receipts of this task. */
final class ProductionPreparation {
    private final LocalPlayer player;
    private final ProductionRunPlan plan;
    private final ProductionWork work;
    private final ProductionNativeEvidence evidence;
    private final List<Node> processes;
    private final ProductionConnectionSurvey connectionSurvey;
    private final Map<String, JsonObject> connections = new java.util.LinkedHashMap<>();
    private int nodeIndex, portIndex, recipeIndex, configurationIndex, linkIndex;
    private ProductionDesignCompiler.Compilation compilation;

    ProductionPreparation(LocalPlayer player, ProductionRunPlan plan, ProductionWork work) {
        this.player = player; this.plan = plan; this.work = work;
        evidence = new ProductionNativeEvidence(plan.manifest());
        evidence.bind(plan.dimension(), new Point(plan.anchor().getX(), plan.anchor().getY(), plan.anchor().getZ()));
        updateOperations();
        processes = plan.manifest().nodes().stream().filter(node -> node.kind().equals("process")).toList();
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
        nodeIndex = portIndex = recipeIndex = configurationIndex = linkIndex = 0;
        compilation = null; connections.clear(); updateOperations();
        connectionSurvey.reset();
    }
    private void updateOperations() {
        var supported = new java.util.LinkedHashSet<String>();
        for (var c : plan.manifest().configurations())
            if (org.maiwithu.maicraft.client.server.ServerAssistClient.supported(c.operation())
                    || org.maiwithu.maicraft.client.server.ServerAssistClient.renegotiating(c.operation())) supported.add(c.operation());
        evidence.supportedOperations(supported);
    }

    boolean tick() {
        if (nodeIndex < plan.manifest().nodes().size()) {
            Node node = plan.manifest().nodes().get(nodeIndex);
            if (!work.observe(plan.at(node))) return false;
            JsonObject body = plan.snapshotBody(plan.at(node)); body.add("faces", new JsonArray());
            JsonObject result = work.request("machine.snapshot", body, false);
            if (result != null) { evidence.observeNode(node.id(), result); nodeIndex++; progress(); }
            return false;
        }
        if (portIndex < plan.manifest().ports().size()) {
            Port port = plan.manifest().ports().get(portIndex);
            if (!work.observe(plan.at(port.offset()))) return false;
            JsonObject body = plan.snapshotBody(plan.at(port.offset()));
            JsonArray faces = new JsonArray(); faces.add(port.face()); body.add("faces", faces);
            JsonObject result = work.request("machine.snapshot", body, false);
            if (result != null) { evidence.observePort(port.id(), result); portIndex++; progress(); }
            return false;
        }
        if (recipeIndex < processes.size()) {
            Node node = processes.get(recipeIndex);
            if (!work.observe(plan.at(node))) return false;
            JsonObject body = new JsonObject(); body.addProperty("recipe_id", node.recipeId());
            body.add("position", ProductionRunPlan.position(plan.at(node)));
            JsonObject result = work.request("machine.recipe", body, false);
            if (result != null) { evidence.observeRecipe(node.id(), result); recipeIndex++; progress(); }
            return false;
        }
        if (configurationIndex < plan.manifest().configurations().size()) {
            Configuration configuration = plan.manifest().configurations().get(configurationIndex);
            if (!work.observe(plan.at(plan.node(configuration.node())))) return false;
            JsonObject result = work.request("machine.configuration", plan.configurationBody(configuration), false);
            if (result != null) {
                evidence.observeConfiguration(configuration.id(), result); configurationIndex++; progress();
            }
            return false;
        }
        if (linkIndex < plan.manifest().links().size()) {
            Link link = plan.manifest().links().get(linkIndex);
            JsonObject result = connectionSurvey.tick(link);
            if (result != null) {
                evidence.observeLink(link.id(), result); connections.put(link.id(), result.deepCopy());
                linkIndex++; progress();
            }
            return false;
        }
        evidence.advance(player.level().getGameTime());
        compilation = ProductionDesignCompiler.compile(plan.authoredJson(), evidence);
        return true;
    }

    private void progress() { work.extendDeadlineTo(player.level().getGameTime() + 1_200); }
    ProductionDesignCompiler.Compilation compilation() { return compilation; }
    boolean topologyVerified() {
        return plan.manifest().links().stream().allMatch(link -> {
            JsonObject value = connections.get(link.id());
            return value != null && value.has("verified_connection")
                    && value.get("verified_connection").isJsonPrimitive()
                    && value.getAsJsonPrimitive("verified_connection").isBoolean()
                    && value.get("verified_connection").getAsBoolean();
        });
    }
    Map<String, Object> report() {
        return compilation == null ? Map.of("status", "observing", "nodes", nodeIndex, "ports", portIndex,
                "recipes", recipeIndex, "configurations", configurationIndex, "connections", linkIndex) : Map.of("plan", compilation.report());
    }
}
