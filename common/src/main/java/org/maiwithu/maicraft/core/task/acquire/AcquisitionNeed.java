// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** 一项待补齐的背包需求：记住原料来路、已试来源和已发生效果，供取物任务逐层返回。 */
final class AcquisitionNeed {
    // 这是一项尚未满足的需求；既可能是最终物品，也可能是为它准备的原料或工具。
    // 记住已试过的来源和经过哪些配方，避免木板转木头、再转回木板这类循环。
    final List<ResourceLocation> itemIds;
    final int requiredFinalCount;
    final int depth;
    final Set<ResourceLocation> lineageItems;
    final Set<String> lineageRecipes;
    /** 这组替代原料补齐后，可以继续制作的所有父配方。 */
    final Set<String> parentRecipeIds;
    final List<SemanticAcquireTaskRecord.Source> allowedSources;
    final Set<String> rejectedRecipes = new LinkedHashSet<>();
    /** 已经为其补过一次工作台的配方；同一摆台失败不能无限追加新工作台。 */
    final Set<String> surfaceRecoveryRecipes = new LinkedHashSet<>();
    final Map<SemanticAcquireTaskRecord.Source, Integer> sourceAttempts =
            new LinkedHashMap<>();
    final Set<ResourceLocation> rejectedTradeOutputs = new LinkedHashSet<>();
    final Set<String> exhaustedHuntSearchStates = new LinkedHashSet<>();
    final Set<SemanticAcquireTaskRecord.Source> exhaustedSources = new LinkedHashSet<>();
    /** 前置材料已开始产生效果后，保留选定配方，避免花了材料又无声切换路线。 */
    final Set<String> committedRecipeIds = new LinkedHashSet<>();
    boolean committedRecipeEffectsObserved;
    List<SemanticAcquireTaskRecord.Source> plannedSourceOrder;
    int huntSearchAttempts;
    boolean huntSearchExpandedView;
    boolean miningToolPrerequisitePushed;
    int preferredToolTierCap = 3;
    boolean stockOnlyTool;
    boolean toolPrerequisite;
    boolean efficientBatchStarted;
    boolean effectsObserved;
    final Set<BlockPos> visitedContainers = new LinkedHashSet<>();
    int containerAttempts;
    boolean decisionRequired;
    ResourceLocation preferredTradeOutput;
    int lastObservedCount = -1;

    AcquisitionNeed(
            List<ResourceLocation> itemIds,
            int requiredFinalCount,
            int depth,
            Set<ResourceLocation> lineageItems,
            Set<String> lineageRecipes,
            Set<String> parentRecipeIds,
            List<SemanticAcquireTaskRecord.Source> allowedSources) {
        this.itemIds = List.copyOf(itemIds);
        this.requiredFinalCount = requiredFinalCount;
        this.depth = depth;
        this.lineageItems = Set.copyOf(lineageItems);
        this.lineageRecipes = Set.copyOf(lineageRecipes);
        this.parentRecipeIds = parentRecipeIds == null
                ? Set.of() : Set.copyOf(parentRecipeIds);
        this.allowedSources = List.copyOf(allowedSources);
    }

    int attempts(SemanticAcquireTaskRecord.Source source) {
        return sourceAttempts.getOrDefault(source, 0);
    }

    void attempted(SemanticAcquireTaskRecord.Source source) {
        sourceAttempts.merge(source, 1, Integer::sum);
    }

    boolean canTry(SemanticAcquireTaskRecord.Source source) {
        // 只为仍允许、尚未用尽的来源继续观察和准备，避免已经放弃的工序反复扫描世界。
        return allowedSources.contains(source) && !exhaustedSources.contains(source);
    }
}
