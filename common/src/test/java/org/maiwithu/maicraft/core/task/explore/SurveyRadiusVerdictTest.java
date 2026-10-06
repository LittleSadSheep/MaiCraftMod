// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.explore;

/** 跑图半径收尾口径：到达半径即达成、真越界才按越界截停失败，不冒用寻路失败。 */
public final class SurveyRadiusVerdictTest {
    public static void main(String[] args) {
        int maxDistance = 64;
        double threshold = SemanticExploreCompanionTask.surveyRadiusReachThreshold(maxDistance);
        check(threshold == 56.0, "arrival margin leaves headroom before the hard bound");

        // 达成带内（含容差上限）按 radius_reached 收尾，不等航点行程走完。
        check(SemanticExploreCompanionTask.radiusVerdict(true, 0, maxDistance)
                == SemanticExploreCompanionTask.RadiusVerdict.WITHIN_RADIUS, "start position stays within radius");
        check(SemanticExploreCompanionTask.radiusVerdict(true, 55.9, maxDistance)
                == SemanticExploreCompanionTask.RadiusVerdict.WITHIN_RADIUS, "just below the threshold is not reached yet");
        check(SemanticExploreCompanionTask.radiusVerdict(true, 56, maxDistance)
                == SemanticExploreCompanionTask.RadiusVerdict.RADIUS_REACHED, "at the threshold the radius is reached");
        check(SemanticExploreCompanionTask.radiusVerdict(true, 72.0, maxDistance)
                == SemanticExploreCompanionTask.RadiusVerdict.RADIUS_REACHED, "within the safety tolerance still counts as reached");

        // 越过硬边界按越界截停，两种任务形态都不冒充达成，也不冒用 no_path。
        check(SemanticExploreCompanionTask.radiusVerdict(true, 72.5, maxDistance)
                == SemanticExploreCompanionTask.RadiusVerdict.RADIUS_BOUND_EXIT, "beyond the hard bound is a bound exit");
        check(SemanticExploreCompanionTask.radiusVerdict(false, 72.5, maxDistance)
                == SemanticExploreCompanionTask.RadiusVerdict.RADIUS_BOUND_EXIT, "biome exploration fails the same way");

        // 非跑图形态没有达成语义：带内推进不提前收尾，只有越界才失败。
        check(SemanticExploreCompanionTask.radiusVerdict(false, 60, maxDistance)
                == SemanticExploreCompanionTask.RadiusVerdict.WITHIN_RADIUS, "biome exploration has no early radius success");
        System.out.println("SurveyRadiusVerdictTest: passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
