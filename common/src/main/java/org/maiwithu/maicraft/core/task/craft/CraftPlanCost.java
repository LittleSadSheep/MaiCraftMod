// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.craft;

import java.util.Comparator;
import java.util.Objects;

/**
 * Stable, side-effect-free cost used to compare ordinary crafting routes.
 *
 * <p>A crafting surface is a prerequisite of the recipe, not an ingredient. Keeping the two
 * dimensions separate prevents a material-complete 3x3 recipe from losing to a superficially
 * smaller 2x2 conversion recipe whose ingredients still need recursive acquisition.</p>
 */
public record CraftPlanCost(
        int missingMaterials,
        Surface surface,
        int outputWaste,
        int ingredientUses,
        String stableId) {

    public enum Surface {
        READY,
        PREPARABLE,
        SEARCHING,
        PREREQUISITE,
        UNAVAILABLE,
        UNSUPPORTED
    }

    public static final Comparator<CraftPlanCost> ORDER = Comparator
            .comparingInt(CraftPlanCost::tier)
            .thenComparingInt(CraftPlanCost::missingMaterials)
            .thenComparingInt(CraftPlanCost::outputWaste)
            .thenComparingInt(CraftPlanCost::ingredientUses)
            .thenComparing(CraftPlanCost::stableId);

    public CraftPlanCost {
        if (missingMaterials < 0) throw new IllegalArgumentException("missingMaterials < 0");
        if (outputWaste < 0) throw new IllegalArgumentException("outputWaste < 0");
        if (ingredientUses < 0) throw new IllegalArgumentException("ingredientUses < 0");
        surface = Objects.requireNonNull(surface, "surface");
        stableId = Objects.requireNonNull(stableId, "stableId");
    }

    /** True when the Mod can start this recipe without asking for another semantic decision. */
    public boolean dispatchable() {
        return missingMaterials == 0
                && (surface == Surface.READY || surface == Surface.PREPARABLE
                        || surface == Surface.SEARCHING);
    }

    /**
     * Coarse route class. Material-complete routes always precede recursive material routes when
     * their surface is ready or internally preparable.
     */
    private int tier() {
        if (missingMaterials == 0 && surface == Surface.READY) return 0;
        if (missingMaterials == 0 && (surface == Surface.PREPARABLE || surface == Surface.SEARCHING)) return 1;
        if (missingMaterials == 0 && surface == Surface.PREREQUISITE) return 2;
        if (missingMaterials > 0 && surface == Surface.READY) return 3;
        if (missingMaterials > 0 && (surface == Surface.PREPARABLE || surface == Surface.SEARCHING)) return 4;
        if (missingMaterials > 0 && surface == Surface.PREREQUISITE) return 5;
        if (missingMaterials == 0 && surface == Surface.UNAVAILABLE) return 6;
        if (missingMaterials > 0 && surface == Surface.UNAVAILABLE) return 7;
        return 8;
    }
}
