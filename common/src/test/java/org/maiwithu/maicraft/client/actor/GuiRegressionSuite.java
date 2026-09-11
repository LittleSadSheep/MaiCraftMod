// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

/** Actual visibility-policy checks and architecture guards; live UI clicks still need a game. */
public final class GuiRegressionSuite {
    public static void main(String[] args) throws Exception {
        org.maiwithu.maicraft.client.chat.ChatTypingTest.main(args);
        ChatSessionTest.main(args);
        org.maiwithu.maicraft.intent.ChatAbilityTest.main(args);
        MenuVisibilityTest.main(args);
        WindowControlTest.main(args);
        ControlProtocolTest.main(args);
        BlockUseConfirmationTest.main(args);
        BodyControlInputTest.main(args);
        BodyPostureObservationTest.main(args);
        BodyCameraSmoothingTest.main(args);
        EquipRoutingTest.main(args);
        org.maiwithu.maicraft.intent.ExactInteractionTargetTest.main(args);
        org.maiwithu.maicraft.intent.ShipTravelContractTest.main(args);
        BucketInteractionRayTest.main(args);
        PreviewControlTest.main(args);
        org.maiwithu.maicraft.client.preview.PreviewSessionTest.main(args);
        org.maiwithu.maicraft.client.preview.PreviewVisibilityTest.main(args);
        org.maiwithu.maicraft.client.preview.PreviewRefreshTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPreviewCancellationTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementStageTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildTemporarySupportPlanTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildSupportAccessTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildExecutionPacingTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementGestureTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildAimRetryTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPrecisionAimTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementDestinationTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementBudgetTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildTaskSearchBudgetTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementConfirmationTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildDoorStateRepairTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildFailureEvidenceTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildWorksitePlannerTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildStanceNavigationTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildNavigationRetryTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildLayerFrontierTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildRegionsTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildFootingTest.main(args);
        org.maiwithu.maicraft.core.task.build.CreativeBuildInventoryTest.main(args);
        org.maiwithu.maicraft.core.task.build.CreativeBuildRetentionTest.main(args);
        CreativeBuildSupplyMenuTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildScaffoldLedgerTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildTemporarySupportMaterialsTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildScaffoldCleanupTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildScaffoldCleanupGuardTest.main(args);
        org.maiwithu.maicraft.core.task.build.MachineSealingTest.main(args);
        CompanionCancellationTest.main(args);
        SleepSafetyTest.main(args);
        FishingBiteTest.main(args);
        org.maiwithu.maicraft.core.inventory.StockEvidenceTest.main(args);
        org.maiwithu.maicraft.core.integration.create.CreateStockObservationTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2ScreenAccessTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2WaterBucketFillTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2InPlaceSupplyTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2LandingWaterFallbackTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.LandingMaterialSupplyTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2FixedTerminalDiscoveryTest.main(args);
        VisibleMenuSessionTest.main(args);
        LegacyInventoryBoundaryTest.main(args);
        GuiBoundaryAuditTest.main(args);
        System.out.println("GuiRegressionSuite: passed");
    }
}
