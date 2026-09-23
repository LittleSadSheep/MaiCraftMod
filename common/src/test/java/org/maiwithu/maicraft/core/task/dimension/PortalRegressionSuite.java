// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import org.maiwithu.maicraft.intent.PortalPreparationContractTest;

/** 不打开游戏窗口，测试传送门准备契约和原生动作边界。 */
public final class PortalRegressionSuite {
    public static void main(String[] args) throws Exception {
        NetherPortalFrameTest.main(args);
        EndPortalFrameTest.main(args);
        PortalActivationTest.main(args);
        PortalPreparationSiteTest.main(args);
        PortalPolicyTest.main(args);
        PortalSurveyTest.main(args);
        PortalPreparationTaskTest.main(args);
        PortalPreparationContractTest.main(args);
        DimensionPreparationFallbackTest.main(args);
        PortalPreparationSupplyTest.main(args);
        NetherPreparationWorkflowTest.main(args);
        System.out.println("PortalRegressionSuite: passed");
    }
}
