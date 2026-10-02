// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.MachineMenuRecoveryTest;
import org.maiwithu.maicraft.client.actor.CraftingGridPlacementTest;
import org.maiwithu.maicraft.core.integration.machine.MachineMenuObservationTest;
import org.maiwithu.maicraft.core.integration.machine.MachineDeclaredReplacementTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ServerSupplyTest;
import org.maiwithu.maicraft.core.pathing.baritone.PreserveProbeRecoveryTest;
import org.maiwithu.maicraft.core.task.build.BuildClearanceSurveyTest;
import org.maiwithu.maicraft.core.task.build.BuildClearanceExecutionTest;
import org.maiwithu.maicraft.core.task.build.BuildFailureEvidenceTest;
import org.maiwithu.maicraft.core.task.build.MachineModificationClearanceTest;
import org.maiwithu.maicraft.core.task.enchant.EnchantTransactionPauseTest;
import org.maiwithu.maicraft.core.task.enchant.EnchantWorkflowGuardTest;
import org.maiwithu.maicraft.core.task.stonecutter.StonecuttingProcessTest;

/** 一次任务中的机械恢复与失败事实交付回放；不把本地夹具当作真实服务器验收。 */
public final class FailureReceiptRegressionSuite {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        IntentAttentionEvidenceTest.main(args);
        // 缺口对应的资料和能力契约必须随失败到达，不能把原生未知消费变成可自动重试。
        RecoveryKnowledgeTest.main(args);
        MachineMenuRecoveryTest.main(args); MachineMenuObservationTest.main(args);
        CraftingGridPlacementTest.main(args);
        EnchantWorkflowGuardTest.main(args); EnchantTransactionPauseTest.main(args);
        StonecuttingProcessTest.main(args); Ae2ServerSupplyTest.main(args);
        PreserveProbeRecoveryTest.main(args);
        BuildClearanceSurveyTest.main(args); BuildFailureEvidenceTest.main(args);
        BuildClearanceExecutionTest.main(args);
        MachineModificationClearanceTest.main(args);
        MachineDeclaredReplacementTest.main(args);
        System.out.println("FailureReceiptRegressionSuite: passed");
    }
}
