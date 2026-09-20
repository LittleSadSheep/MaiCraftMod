// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.craft;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** 合成暂时做不了时交给取物规划的候选；配方身份和工作台要求直接传递，不从展示文字重新猜测。 */
public record CraftRecoveryCandidate(
        ResourceLocation outputItem,
        String recipeId,
        List<IngredientDemand> ingredients,
        CraftPlanCost cost,
        List<ResourceLocation> surfacePrerequisiteItems) {
    public CraftRecoveryCandidate {
        ingredients = List.copyOf(ingredients);
        Objects.requireNonNull(cost, "cost");
        surfacePrerequisiteItems = List.copyOf(surfacePrerequisiteItems);
    }

    /** 同一个配方格可接受哪些材料、总共要几份、当前还差几份；不会继承对外报告的截断。 */
    public record IngredientDemand(List<ResourceLocation> itemIds, int required, int missing) {
        public IngredientDemand {
            itemIds = List.copyOf(new LinkedHashSet<>(itemIds));
            if (required < 1 || missing < 0 || missing > required)
                throw new IllegalArgumentException("invalid crafting ingredient demand");
        }
    }

    public boolean surfaceSupported() {
        return cost.surface() != CraftPlanCost.Surface.UNSUPPORTED;
    }

    public boolean surfaceReady() {
        return cost.surface() == CraftPlanCost.Surface.READY;
    }
}
