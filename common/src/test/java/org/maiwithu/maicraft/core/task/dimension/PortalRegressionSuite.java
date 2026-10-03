// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import org.maiwithu.maicraft.intent.PortalPreparationContractTest;

/** 不打开游戏窗口，测试传送门准备契约和原生动作边界。 */
public final class PortalRegressionSuite {
    public static void main(String[] args) throws Exception {
        NetherPortalFrameTest.main(args);
        // 浇筑模板先核对四种池岸方向，水流效果另由实机验收。
        NetherPortalCastingLayoutTest.main(args);
        PortalCastingSurveyTest.main(args);
        PortalCastingWorkflowTest.main(args);
        // 有池无水、有水无池以及已有水桶都要分别回报，不能只验收资源齐全的场景。
        PortalCastingPreparationTest.main(args);
        EndPortalFrameTest.main(args);
        PortalActivationTest.main(args);
        PortalPreparationSiteTest.main(args);
        PortalPolicyTest.main(args);
        PortalSurveyTest.main(args);
        PortalPreparationTaskTest.main(args);
        PortalPreparationContractTest.main(args);
        DimensionPreparationFallbackTest.main(args);
        // 高台旁先比较整扇门的底部入口，一列受阻后仍能尝试另一列。
        PortalEntranceTest.main(args);
        PortalPreparationSupplyTest.main(args);
        NetherPreparationWorkflowTest.main(args);
        // 里程碑聚合层是 reach_milestone 的唯一对外入口；child 卡点事实被聚合丢弃即 009 复发。
        org.maiwithu.maicraft.core.task.progression.ReachMilestoneEvidencePassThroughTest.main(args);
        System.out.println("PortalRegressionSuite: passed");
    }
}
