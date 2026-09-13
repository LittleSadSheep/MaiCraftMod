// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

/** Protocol, native resources and production invariants; live mod-world acceptance runs separately. */
public final class OptionalServerRegressionSuite {
    public static void main(String[] args) throws Exception {
        org.maiwithu.maicraft.network.ProtocolRegressionSuite.main(args);
        org.maiwithu.maicraft.network.OptionalServerPayloadTest.main(args);
        org.maiwithu.maicraft.client.server.ServerAssistanceRegressionSuite.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionSessionRolloverTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionSessionIdleExpiryTest.main(args);
        org.maiwithu.maicraft.client.server.MachineSnapshotEnrichmentTest.main(args);
        org.maiwithu.maicraft.client.server.ServerActorMutationGateTest.main(args);
        org.maiwithu.maicraft.mcp.SemanticAbilityAvailabilityTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2ServerSupplyTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2ServerCraftJobTest.main(args);
        org.maiwithu.maicraft.server.machine.ServerNativeRegressionTest.main(args);
        org.maiwithu.maicraft.server.machine.create.CreatePressInputInspectionTest.main(args);
        org.maiwithu.maicraft.server.machine.NativeInventoryRegressionTest.main(args);
        org.maiwithu.maicraft.server.machine.ProductionRetentionTest.main(args);
        org.maiwithu.maicraft.server.inventory.Ae2NativeCraftingCompletionTest.main(args);
        org.maiwithu.maicraft.server.machine.mekanism.MekanismResourceRegressionTest.main(args);
        org.maiwithu.maicraft.server.machine.connectivity.ConnectionInspectionTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.production.ProductionDesignCompilerTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidenceTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.production.ProductionPressInputObstructionTest.main(args);
        MachineProductionContractTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionEvidenceWindowTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionSupplyBudgetTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionSupplyStockTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionSupplyPacerTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionEventCursorTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionOutputMonitorTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionWatchLifecycleTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionRunPlanBindingTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionProcessingProgressTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionSupplyHintsTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionTransitBaselineTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionProgressWatchdogTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionIdleObservationTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionStartupTransitionTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionFlowBudgetTest.main(args);
        org.maiwithu.maicraft.core.task.build.PlacementSneakProjectionTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionConstructionProtectionTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionInteractionNavigationTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionObservationRangeTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionReadScheduleTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionConnectionSurveyTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.runtime.ProductionConnectionResponsesTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.discovery.MachineDiscoveryScannerTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.utility.MachineUtilityInputsTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.utility.MachineSurvivalMaterialsTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutUtilityInputsTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.catalog.UtilityInstallationCatalogTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.utility.UtilityConnectionPlannerTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.utility.UtilityExistingCableRouteTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.utility.UtilityConnectionTaskGuardTest.main(args);
        org.maiwithu.maicraft.core.integration.create.CreateExactUtilityEndpointTest.main(args);
        org.maiwithu.maicraft.core.integration.create.CreateEconomicEndpointBridgeTest.main(args);
        org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometryTest.main(args);
        org.maiwithu.maicraft.core.integration.create.transmission.KineticTransmissionAlternativesTest.main(args);
        org.maiwithu.maicraft.core.integration.create.transmission.KineticCogwheelGeometryTest.main(args);
        org.maiwithu.maicraft.core.integration.create.transmission.KineticMaterialCostsTest.main(args);
        org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteChoiceTest.main(args);
        org.maiwithu.maicraft.core.integration.create.transmission.KineticRpmBudgetTest.main(args);
        org.maiwithu.maicraft.core.integration.create.transmission.KineticClientHeightmapTest.main(args);
        org.maiwithu.maicraft.core.integration.create.transmission.KineticClearanceRevalidationTest.main(args);
        org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteContinuationTest.main(args);
        org.maiwithu.maicraft.core.integration.create.transmission.ChainConveyorContractTest.main(args);
        org.maiwithu.maicraft.server.machine.connectivity.ChainConveyorConnectionPathTest.main(args);
        org.maiwithu.maicraft.server.machine.watch.MachineWatchProgressTest.main(args);
        System.out.println("OptionalServerRegressionSuite: passed");
    }
}
