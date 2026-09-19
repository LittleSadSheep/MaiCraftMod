// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

/** 先验证统一入口与包装边界，再复跑原附魔、v1网络和知识发现，避免新增机制破坏原有已验收路径。 */
public final class NativeProcessRegressionSuite {
    public static void main(String[] args) throws Exception {
        NativeProcessIntentTest.main(args);
        // 调整材料预算或加工机制后，执行目标必须先写回存档，重启不能回到另一份尚可消费的旧意图。
        RetryIntentPersistenceTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.process.NativeProcessTaskTest.main(args);
        // 通用物资分配、原生Q、实际入池、加工事件和本人拾取各自保留证据，任何一层成功都不能代替后一层。
        org.maiwithu.maicraft.core.integration.machine.process.NativeTransformRecipesTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.process.WorldProcessBatchPlanTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.process.WorldProcessDropReceiptTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.process.WorldProcessEventEvidenceTest.main(args);
        // 精确接收格、真实取物邻域和暂停后本人拾取都通过后，才允许继续投料或宣布本批已经收成。
        org.maiwithu.maicraft.core.integration.machine.process.WorldProcessSiteTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.process.WorldProcessFeedRegionTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.process.WorldProcessDelayedReactionTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.process.WorldProcessSettlementTest.main(args);
        org.maiwithu.maicraft.client.server.ServerRequestOwnersTest.main(args);
        org.maiwithu.maicraft.client.actor.NativeSelectedDropTest.main(args);
        org.maiwithu.maicraft.client.actor.ItemEntityReceiptsTest.main(args);
        org.maiwithu.maicraft.client.actor.TargetedDropEvidenceTest.main(args);
        // 同一蓝图先装固体围挡再用真实桶填源格，既有正确流体不被清空，材料和服务端方块回执必须同时确认。
        org.maiwithu.maicraft.core.integration.machine.MachineFluidConstructionTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementReceiptTest.main(args);
        org.maiwithu.maicraft.client.actor.FluidPlacementTaskTest.main(args);
        org.maiwithu.maicraft.server.machine.ae2.TransformProductionCaptureTest.main(args);
        org.maiwithu.maicraft.network.OptionalWorldTransformHookTest.main(args);
        String ae2Jar = System.getProperty("maicraft.test.ae2Jar");
        if (ae2Jar != null) org.maiwithu.maicraft.server.machine.ae2.Ae2TransformHookShapeTest.main(new String[]{ae2Jar});
        EnchantRegressionSuite.main(args);
        MachineProductionContractTest.main(args);
        org.maiwithu.maicraft.mcp.SemanticAbilityAvailabilityTest.main(args);
        org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibraryTest.main(args);
        System.out.println("NativeProcessRegressionSuite: passed");
    }
}
