package org.maiwithu.maicraft.core.integration.physics.flight;

import org.maiwithu.maicraft.intent.AircraftFlightContractTest;

/** 先验证控制和原生遥测规则，再验证公开目标与持久化声明；真实飞行另以游戏会话验收。 */
public final class AircraftFlightRegressionSuite {
    public static void main(String[] args) throws Exception {
        FlightFeedbackControllerTest.main(args);
        AircraftFlightContractTest.run();
        AircraftProfileStoreTest.run();
        System.out.println("AircraftFlightRegressionSuite: passed");
    }
}
