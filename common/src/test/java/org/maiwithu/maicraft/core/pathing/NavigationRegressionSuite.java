// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing;

/** Regressions of production algorithms; live movement and server confirmations need a game session. */
// 导航相关回归的集合入口，逐个调用既有测试 main；不是扫描目录自动发现所有测试。
// 按下面明确列出的顺序运行导航相关检查，任何断言失败就中断。新增测试文件不会自动加入；落地补料的菜单检查另由 GuiRegressionSuite 运行。
public final class NavigationRegressionSuite {
    // 从输入预算到路径、落地、飞行和交通依次检查；异常直接向外传给 Gradle，让 check 失败。
    public static void main(String[] args) throws Exception {
        org.maiwithu.maicraft.entity.InputDriverTest.main(args);
        org.maiwithu.maicraft.client.actor.PacketTickBudgetTest.main(args);
        org.maiwithu.maicraft.core.pathing.RegionalTerrainTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.RegionalFlightTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackGroundModeTest.main(args);
        org.maiwithu.maicraft.mcp.NavigationOverviewTest.main(args);
        org.maiwithu.maicraft.core.integration.create.ContraptionObstaclesTest.main(args);
        org.maiwithu.maicraft.core.integration.create.elevator.ElevatorFloorsTest.main(args);
        org.maiwithu.maicraft.intent.ElevatorTravelIntentTest.main(args);
        org.maiwithu.maicraft.core.pathing.util.SwimAirBudgetTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.SubmergedWaterTravelPolicyTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.NavigationHandoffTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.NavigationScaffoldDropGuardTest.main(args);
        org.maiwithu.maicraft.core.task.mine.MiningBatchTest.main(args);
        org.maiwithu.maicraft.core.task.mine.NaturalTreeSourceTest.main(args);
        org.maiwithu.maicraft.core.task.mine.NoPathVerdictTest.main(args);
        org.maiwithu.maicraft.core.task.mine.MiningToolRequirementTest.main(args);
        org.maiwithu.maicraft.core.tools.perception.LocalFloorSenseTest.main(args);
        org.maiwithu.maicraft.core.blueprint.BlueprintImportTest.main(args);
        org.maiwithu.maicraft.intent.persistence.IntentStateStoreTest.main(args);
        org.maiwithu.maicraft.core.task.move.MoveToContractTest.main(args);
        org.maiwithu.maicraft.core.task.move.MoveToTransportCompletionTest.main(args);
        baritone.pathing.calc.PathSearchRegressionTest.main(args);
        baritone.pathing.calc.HeightPolicyTest.main(args);
        baritone.pathing.path.PathTickBudgetTest.main(args);
        baritone.pathing.movement.DoorPassageTest.main(args);
        baritone.pathing.movement.DiagonalHazardTest.main(args);
        baritone.pathing.movement.CollisionGeometryTest.main(args);
        org.maiwithu.maicraft.mcp.NearbyCollisionPerceptionTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.NavigationProgressTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.GroundPathSmoothingTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.GroundMovementReplayTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.TravelJumpPhysicsTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.TravelRunwayTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.NavigationCameraCourseTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.NavigationActionPolicyTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.WaterBucketFallTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.LandingSurfaceRulesTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.NativeBucketLandingTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.WaterSurfaceExecutionTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.EmergencyWaterSelectionTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.AirRescueChoiceTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.WaterLandingReplayTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.SharedLandingExecutionTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.LandingSupplyCleanupTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.AutomaticFallAdmissionTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.FallDeparturePreparationTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.MissedLandingHandoffTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistDisplacementTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.PlannedWaterReflexTest.main(args);
        baritone.pathing.path.AssistedFallOwnershipTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudgetTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.FallLandingTest.main(args);
        org.maiwithu.maicraft.core.pathing.transport.TransportTargetsTest.main(args);
        org.maiwithu.maicraft.core.pathing.transport.TransportRuntimeTest.main(args);
        org.maiwithu.maicraft.core.pathing.transport.TransportIntentTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackFlightTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackSearchTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackDynamicsTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackCourseTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackClearancePolicyTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackMotionTest.main(args);
        org.maiwithu.maicraft.core.integration.physics.StructurePoseTest.main(args);
        org.maiwithu.maicraft.core.integration.physics.SableStructureBridgeTest.main(args);
        org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshotTest.main(args);
        org.maiwithu.maicraft.core.integration.physics.StructurePresentationTest.main(args);
        org.maiwithu.maicraft.core.integration.physics.ShipBoardingGeometryTest.main(args);
        org.maiwithu.maicraft.mcp.PhysicalStructurePerceptionTest.main(args);
        org.maiwithu.maicraft.mcp.PhysicalDeckSamplerTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackViewTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackFastDescentTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.JetpackNativeEvidenceTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.MovingFlightSessionTest.main(args);
        org.maiwithu.maicraft.core.integration.jetpack.FlightTraceTest.main(args);
        org.maiwithu.maicraft.core.integration.create.elevator.ElevatorGeometryTest.main(args);
        org.maiwithu.maicraft.core.integration.create.elevator.ElevatorCallLinksTest.main(args);
        org.maiwithu.maicraft.core.integration.create.elevator.ElevatorSessionTest.main(args);
        org.maiwithu.maicraft.core.integration.create.elevator.ElevatorExitReplanTest.main(args);
        org.maiwithu.maicraft.core.integration.create.elevator.ElevatorRemoteStagingTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingAssistTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.BoatCatchReplayTest.main(args);
        org.maiwithu.maicraft.client.actor.NativeConfirmationTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildTraversabilityVerifierTest.main(args);
        org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeightTest.main(args);
        org.maiwithu.maicraft.core.task.acquire.AcquisitionProtectionTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.NavigationPolicySnapshotTest.main(args);
        System.out.println("NavigationRegressionSuite: passed");
    }
}
