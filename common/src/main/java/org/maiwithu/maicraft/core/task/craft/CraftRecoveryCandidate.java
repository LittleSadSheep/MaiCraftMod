// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.craft;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** 合成暂时做不了时交给取物规划的候选；配方身份和工作台要求直接传递，不从展示文字重新猜测。 */
public record CraftRecoveryCandidate(
        ResourceLocation outputItem,
        String recipeId,
        Map<String, Object> data,
        CraftPlanCost cost,
        boolean surfaceSupported,
        boolean surfaceReady,
        List<ResourceLocation> surfacePrerequisiteItems) {
    public CraftRecoveryCandidate {
        data = Map.copyOf(data);
        surfacePrerequisiteItems = List.copyOf(surfacePrerequisiteItems);
    }
}
