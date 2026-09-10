// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.craft;

import java.util.Comparator;
import java.util.Objects;

/**
 * 给普通合成路线排先后：先看材料齐不齐、工作台能否使用，再比较缺料、浪费和材料用量。
 * 材料已经齐全的工作台配方，可以排在还得继续找材料的背包配方前面。最后用配方名字固定同分顺序。
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

    /**
     * 材料已经齐全，并且工作面已就绪、能由内部准备或正在内部寻找时，可以直接交给执行任务。
     */
    public boolean dispatchable() {
        return missingMaterials == 0
                && (surface == Surface.READY || surface == Surface.PREPARABLE
                        || surface == Surface.SEARCHING);
    }

    /**
     * 先把路线分档：能做的排前面，还要补材料的排后面；工作面不可用或不支持的再往后排。
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
