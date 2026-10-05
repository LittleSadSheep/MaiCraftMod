package org.maiwithu.maicraft.core.task.explore;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** 穿水判定：多数水样或连续深水任一成立即穿水；浅滩与短促渡河不受惩罚。 */
public final class WaterCrossingDecisionTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 既有口径：水域样本过半且至少两列判穿水。
        check(WaterCrossingProbe.isCrossing(10, 6, 0), "majority water samples indicate a crossing");
        check(!WaterCrossingProbe.isCrossing(10, 4, 0), "minority water samples stay a land route");
        // 缺陷口径：窄河在长路线里占不了样本多数，但连续两段深水同样判穿水，
        // 不再把路点设到河对岸让移动直接走进河心。
        check(WaterCrossingProbe.isCrossing(10, 2, 2),
                "a narrow river of contiguous deep water counts as a crossing despite the minority");
        // 浅滩与单段深水（约八格内）仍可短促渡过，不触发绕行。
        check(!WaterCrossingProbe.isCrossing(10, 3, 0), "shallow water never triggers the deep rule");
        check(!WaterCrossingProbe.isCrossing(8, 1, 1), "a single deep sample stays a short wade or swim");
        // 深水连段被浅滩或未加载列打断后重新起算。
        check(WaterCrossingProbe.isCrossing(12, 4, 3), "three contiguous deep samples indicate a crossing");
        check(!WaterCrossingProbe.isCrossing(12, 2, 1), "interrupted deep runs do not accumulate");
        // 全水样本当然穿水；零样本（全线未加载）不构成任何判定。
        check(WaterCrossingProbe.isCrossing(8, 8, 8), "all-water samples indicate a crossing");
        check(!WaterCrossingProbe.isCrossing(0, 0, 0), "no loaded samples decide nothing");
        System.out.println("WaterCrossingDecisionTest: passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
