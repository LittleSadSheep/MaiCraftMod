// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;

/** Explicit portal permission is independent of route excavation and rare-item consumption. */
public record PortalPreparationPolicy(boolean enabled, boolean allowRareConsumables, boolean allowCombat,
                                      int maxStructureDistance, MaterialPolicy materialPolicy,
                                      List<Source> allowedSources, List<String> protectedLabels) {
    public static final PortalPreparationPolicy DISABLED = new PortalPreparationPolicy(
            false, false, false, 4096, MaterialPolicy.ORDINARY, List.of(), List.of());

    public PortalPreparationPolicy {
        maxStructureDistance = Math.clamp(maxStructureDistance, 128, 4096);
        materialPolicy = materialPolicy == null ? MaterialPolicy.ORDINARY : materialPolicy;
        allowedSources = allowedSources == null ? List.of() : List.copyOf(allowedSources);
        protectedLabels = protectedLabels == null ? List.of() : protectedLabels.stream().map(String::strip)
                .filter(s -> !s.isEmpty()).distinct().toList();
        if (protectedLabels.size() > 64) throw new IllegalArgumentException("protected_labels accepts at most 64 values");
    }

    public static PortalPreparationPolicy parse(JsonObject input) {
        return new PortalPreparationPolicy(flag(input, "prepare_portal"), flag(input, "allow_rare_consumables"),
                flag(input, "allow_combat"), input.has("max_search_distance") ? input.get("max_search_distance").getAsInt() : 4096,
                MaterialPolicy.parse(input.has("material_policy") ? input.get("material_policy").getAsString() : null),
                SemanticMaterialSupplyCoordinator.parseSources(strings(input, "allowed_sources")), strings(input, "protected_labels"));
    }
    private static boolean flag(JsonObject input, String key) { return input.has(key) && input.get(key).getAsBoolean(); }
    private static List<String> strings(JsonObject input, String key) {
        if (!input.has(key)) return List.of();
        List<String> values = new ArrayList<>();
        input.getAsJsonArray(key).forEach(value -> values.add(value.getAsString()));
        return values;
    }

    List<Source> sources(boolean mayAlterTerrain) {
        var sources = new ArrayList<>(SemanticMaterialSupplyCoordinator.resolveSources(materialPolicy, allowedSources));
        if (!mayAlterTerrain) sources.remove(Source.MINE);
        if (!allowCombat) sources.remove(Source.HUNT);
        else if (allowedSources.isEmpty() && materialPolicy != MaterialPolicy.INVENTORY_ONLY) sources.add(Source.HUNT);
        return List.copyOf(sources);
    }
}
