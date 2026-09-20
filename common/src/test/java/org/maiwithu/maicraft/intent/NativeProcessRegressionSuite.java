// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import org.maiwithu.maicraft.client.actor.CollectItemsIdentityTest;
import org.maiwithu.maicraft.client.actor.FluidPlacementTaskTest;
import org.maiwithu.maicraft.client.actor.ItemEntityReceiptsTest;
import org.maiwithu.maicraft.client.actor.NativeSelectedDropTest;
import org.maiwithu.maicraft.client.actor.TargetedDropEvidenceTest;
import org.maiwithu.maicraft.client.server.ServerRequestOwnersTest;
import org.maiwithu.maicraft.core.integration.machine.MachineFluidConstructionTest;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementReceiptTest;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessTaskTest;
import org.maiwithu.maicraft.core.integration.machine.process.NativeTransformRecipesTest;
import org.maiwithu.maicraft.core.integration.machine.process.WorldProcessBatchPlanTest;
import org.maiwithu.maicraft.core.integration.machine.process.WorldProcessCollectionTest;
import org.maiwithu.maicraft.core.integration.machine.process.WorldProcessDelayedReactionTest;
import org.maiwithu.maicraft.core.integration.machine.process.WorldProcessDropReceiptTest;
import org.maiwithu.maicraft.core.integration.machine.process.WorldProcessEventEvidenceTest;
import org.maiwithu.maicraft.core.integration.machine.process.WorldProcessFeedRegionTest;
import org.maiwithu.maicraft.core.integration.machine.process.WorldProcessSettlementTest;
import org.maiwithu.maicraft.core.integration.machine.process.WorldProcessSiteTest;
import org.maiwithu.maicraft.core.task.acquire.MaterialAcquisitionHandoffTest;
import org.maiwithu.maicraft.core.task.acquire.MaterialProcessPlanningTest;
import org.maiwithu.maicraft.mcp.SemanticAbilityAvailabilityTest;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibraryTest;
import org.maiwithu.maicraft.network.OptionalWorldTransformHookTest;
import org.maiwithu.maicraft.server.machine.ae2.Ae2TransformHookShapeTest;
import org.maiwithu.maicraft.server.machine.ae2.TransformProductionCaptureTest;

/** 先验证统一入口与包装边界，再复跑原附魔、v1网络和知识发现，避免新增机制破坏原有已验收路径。 */
public final class NativeProcessRegressionSuite {
    public static void main(String[] args) throws Exception {
        NativeProcessIntentTest.main(args);
        // 普通材料路线耗尽时只交接按需工艺知识，外部恢复仍沿用原目标、权限和库存核验。
        MaterialProcessPlanningTest.main(args);
        MaterialAcquisitionHandoffTest.main(args);
        MaterialPlanningRecoveryTest.main(args);
        // 调整材料预算或加工机制后，执行目标必须先写回存档，重启不能回到另一份尚可消费的旧意图。
        RetryIntentPersistenceTest.main(args);
        NativeProcessTaskTest.main(args);
        // 通用物资分配、原生Q、实际入池、加工事件和本人拾取各自保留证据，任何一层成功都不能代替后一层。
        NativeTransformRecipesTest.main(args);
        WorldProcessBatchPlanTest.main(args);
        WorldProcessDropReceiptTest.main(args);
        WorldProcessEventEvidenceTest.main(args);
        // 精确接收格、真实取物邻域和暂停后本人拾取都通过后，才允许继续投料或宣布本批已经收成。
        WorldProcessSiteTest.main(args);
        WorldProcessFeedRegionTest.main(args);
        WorldProcessDelayedReactionTest.main(args);
        WorldProcessSettlementTest.main(args);
        // 产物到格后等待真实拾取同步，沿用通用收取且只追本批UUID，不能因一帧延迟提前报整批失败。
        CollectItemsIdentityTest.main(args);
        WorldProcessCollectionTest.main(args);
        ServerRequestOwnersTest.main(args);
        NativeSelectedDropTest.main(args);
        ItemEntityReceiptsTest.main(args);
        TargetedDropEvidenceTest.main(args);
        // 同一蓝图先装固体围挡再用真实桶填源格，既有正确流体不被清空，材料和服务端方块回执必须同时确认。
        MachineFluidConstructionTest.main(args);
        FluidPlacementReceiptTest.main(args);
        FluidPlacementTaskTest.main(args);
        TransformProductionCaptureTest.main(args);
        OptionalWorldTransformHookTest.main(args);
        String ae2Jar = System.getProperty("maicraft.test.ae2Jar");
        if (ae2Jar != null) Ae2TransformHookShapeTest.main(new String[]{ae2Jar});
        EnchantRegressionSuite.main(args);
        MachineProductionContractTest.main(args);
        SemanticAbilityAvailabilityTest.main(args);
        KnowledgeLibraryTest.main(args);
        System.out.println("NativeProcessRegressionSuite: passed");
    }
}
