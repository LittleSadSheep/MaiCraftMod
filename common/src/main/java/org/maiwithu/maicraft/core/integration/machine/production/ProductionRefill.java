// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.Recipe;

/** A window budget need not fit a source buffer; admit one batch, while retaining the finite cumulative cap. */
final class ProductionRefill {
    private ProductionRefill() {}
    static long firstBatch(Link link, ProductionGraph graph, Map<String,Recipe> recipes, Function<Resource,Resource> resolve) {
        if (link.resource().medium().equals("kinetic")) return link.amount();
        Set<String> consumers = consumers(link,graph,resolve);
        long batches = consumers.stream().mapToLong(id -> graph.nodes.get(id).batches()).min().orElse(1);
        long quantity = link.amount()/batches + (link.amount()%batches == 0 ? 0 : 1);
        long catalysts = 0;
        for (String id : consumers) if (recipes.containsKey(id)) for (var ingredient : recipes.get(id).inputs())
            if (!ingredient.consumed() && ingredient.alternatives().contains(resolve.apply(link.resource()))) catalysts = Math.addExact(catalysts,ingredient.amount());
        return Math.min(link.amount(),Math.max(quantity,catalysts));
    }
    static Set<String> consumers(Link link, ProductionGraph graph, Function<Resource,Resource> resolve) {
        Set<String> result = new java.util.LinkedHashSet<>();
        consumers(graph.ports.get(link.to()).node(),resolve.apply(link.resource()),graph,resolve,result,new HashSet<>()); return result;
    }
    private static void consumers(String id, Resource resource, ProductionGraph graph, Function<Resource,Resource> resolve, Set<String> found, Set<String> seen) {
        if (!seen.add(id)) return; Node node = graph.nodes.get(id);
        if (node.kind().equals("process")) { found.add(id); return; }
        if (node.kind().equals("transport")) for (Link link : graph.outgoing.get(id)) if (resolve.apply(link.resource()).equals(resource))
            consumers(graph.ports.get(link.to()).node(),resource,graph,resolve,found,seen);
    }
}
