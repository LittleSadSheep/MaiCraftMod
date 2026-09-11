// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.Ingredient;

/** Ingredient alternatives form a flow problem: one supplied item cannot satisfy two overlapping tag inputs. */
final class ProductionRecipeBalance {
    private ProductionRecipeBalance() {}

    static boolean covers(List<Ingredient> ingredients, Map<Resource, Long> supply, long batches) {
        if (ingredients.size() > 128 || supply.size() > 256) throw new IllegalArgumentException("Recipe balance exceeds 128 ingredients or 256 distinct resources");
        List<Resource> resources = new ArrayList<>(supply.keySet());
        int source = 0, resourceStart = 1, ingredientStart = resourceStart + resources.size(), sink = ingredientStart + ingredients.size();
        long[][] capacity = new long[sink + 1][sink + 1];
        long demand = 0;
        var ids = new HashSet<String>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (!ids.add(ingredient.id())) throw new IllegalArgumentException("Duplicate recipe ingredient identity");
            long needed = Math.multiplyExact(ingredient.amount(), ingredient.consumed() ? batches : 1);
            demand = Math.addExact(demand, needed); capacity[ingredientStart + i][sink] = needed;
            for (int r = 0; r < resources.size(); r++) if (ingredient.alternatives().contains(resources.get(r)))
                capacity[resourceStart + r][ingredientStart + i] = needed;
        }
        for (int r = 0; r < resources.size(); r++) {
            long available = supply.get(resources.get(r));
            if (available < 0) throw new IllegalArgumentException("Negative available ingredient quantity");
            capacity[source][resourceStart + r] = available;
        }
        long delivered = 0;
        while (delivered < demand) {
            int[] parent = new int[sink + 1]; Arrays.fill(parent, -1); parent[source] = source;
            ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(source);
            while (!queue.isEmpty() && parent[sink] < 0) {
                int at = queue.remove();
                for (int next = 0; next <= sink; next++) if (parent[next] < 0 && capacity[at][next] > 0) {
                    parent[next] = at; queue.add(next);
                }
            }
            if (parent[sink] < 0) return false;
            long sent = demand - delivered;
            for (int at = sink; at != source; at = parent[at]) sent = Math.min(sent, capacity[parent[at]][at]);
            for (int at = sink; at != source; at = parent[at]) { capacity[parent[at]][at] -= sent; capacity[at][parent[at]] += sent; }
            delivered += sent;
        }
        return true;
    }
}
