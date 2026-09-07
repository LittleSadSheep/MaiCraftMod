// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

/** Actual visibility-policy checks and architecture guards; live UI clicks still need a game. */
public final class GuiRegressionSuite {
    public static void main(String[] args) throws Exception {
        MenuVisibilityTest.main(args);
        WindowControlTest.main(args);
        ControlProtocolTest.main(args);
        BodyControlInputTest.main(args);
        EquipRoutingTest.main(args);
        org.maiwithu.maicraft.intent.ExactInteractionTargetTest.main(args);
        org.maiwithu.maicraft.intent.ShipTravelContractTest.main(args);
        BucketInteractionRayTest.main(args);
        PreviewControlTest.main(args);
        org.maiwithu.maicraft.client.preview.PreviewSessionTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPreviewCancellationTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementStageTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildScaffoldLedgerTest.main(args);
        org.maiwithu.maicraft.core.task.build.MachineSealingTest.main(args);
        CompanionCancellationTest.main(args);
        org.maiwithu.maicraft.core.inventory.StockEvidenceTest.main(args);
        org.maiwithu.maicraft.core.integration.create.CreateStockObservationTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2ScreenAccessTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2WaterBucketFillTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2FixedTerminalDiscoveryTest.main(args);
        VisibleMenuSessionTest.main(args);
        LegacyInventoryBoundaryTest.main(args);
        GuiBoundaryAuditTest.main(args);
        System.out.println("GuiRegressionSuite: passed");
    }
}
