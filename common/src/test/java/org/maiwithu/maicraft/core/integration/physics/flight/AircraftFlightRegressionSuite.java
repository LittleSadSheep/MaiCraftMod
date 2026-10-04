package org.maiwithu.maicraft.core.integration.physics.flight;

import org.maiwithu.maicraft.intent.AircraftFlightContractTest;
import org.maiwithu.maicraft.intent.AircraftTravelContractTest;
import org.maiwithu.maicraft.client.server.FlightOperationRegistrationTest;

/** 先验证控制和原生遥测规则，再验证公开目标与持久化声明；真实飞行另以游戏会话验收。 */
public final class AircraftFlightRegressionSuite {
    public static void main(String[] args) throws Exception {
        FlightFeedbackControllerTest.main(args);
        AircraftFlightContractTest.run();
        // 抵达附近的飞机落点不替代旅行的原始目标，公开契约须保留这个边界。
        AircraftTravelContractTest.run();
        AircraftProfileStoreTest.run();
        FlightOperationRegistrationTest.run();
        // 中止飞行也必须把未确认效果交还决策层，不能暗示可以直接重放起飞。
        FlightOutcomeReceiptTest.run();
        System.out.println("AircraftFlightRegressionSuite: passed");
    }
}
