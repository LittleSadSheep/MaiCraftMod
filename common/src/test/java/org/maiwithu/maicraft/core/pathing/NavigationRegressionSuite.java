// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing;

/** Regressions of production algorithms; live movement and server confirmations need a game session. */
public final class NavigationRegressionSuite {
    public static void main(String[] args) throws Exception {
        org.maiwithu.maicraft.entity.InputDriverTest.main(args);
        org.maiwithu.maicraft.core.pathing.util.SwimAirBudgetTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.SubmergedWaterTravelPolicyTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.NavigationHandoffTest.main(args);
        org.maiwithu.maicraft.core.task.mine.MiningBatchTest.main(args);
        org.maiwithu.maicraft.core.task.mine.NaturalTreeSourceTest.main(args);
        org.maiwithu.maicraft.core.task.mine.NoPathVerdictTest.main(args);
        org.maiwithu.maicraft.core.task.mine.MiningToolRequirementTest.main(args);
        org.maiwithu.maicraft.core.tools.perception.LocalFloorSenseTest.main(args);
        org.maiwithu.maicraft.core.blueprint.BlueprintImportTest.main(args);
        org.maiwithu.maicraft.intent.persistence.IntentStateStoreTest.main(args);
        org.maiwithu.maicraft.core.task.move.MoveToContractTest.main(args);
        baritone.pathing.calc.PathSearchRegressionTest.main(args);
        baritone.pathing.path.PathTickBudgetTest.main(args);
        baritone.pathing.movement.DoorPassageTest.main(args);
        baritone.pathing.movement.CollisionGeometryTest.main(args);
        org.maiwithu.maicraft.mcp.NearbyCollisionPerceptionTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.NavigationProgressTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.NavigationActionPolicyTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.WaterBucketFallTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudgetTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.FallLandingTest.main(args);
        org.maiwithu.maicraft.core.pathing.transport.TransportTargetsTest.main(args);
        org.maiwithu.maicraft.core.pathing.transport.TransportRuntimeTest.main(args);
        org.maiwithu.maicraft.core.pathing.transport.TransportIntentTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackFlightTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackNativeEvidenceTest.main(args);
        org.maiwithu.maicraft.core.integration.create.elevator.ElevatorGeometryTest.main(args);
        org.maiwithu.maicraft.core.integration.create.elevator.ElevatorSessionTest.main(args);
        org.maiwithu.maicraft.core.integration.create.elevator.ElevatorRemoteStagingTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingAssistTest.main(args);
        org.maiwithu.maicraft.client.actor.NativeConfirmationTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildTraversabilityVerifierTest.main(args);
        org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeightTest.main(args);
        org.maiwithu.maicraft.core.task.acquire.AcquisitionProtectionTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.NavigationPolicySnapshotTest.main(args);
        System.out.println("NavigationRegressionSuite: passed");
    }
}
