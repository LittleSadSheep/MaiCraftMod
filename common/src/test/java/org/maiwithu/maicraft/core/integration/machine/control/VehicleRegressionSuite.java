package org.maiwithu.maicraft.core.integration.machine.control;

import org.maiwithu.maicraft.intent.VehicleMachineContractTest;

public final class VehicleRegressionSuite {
    public static void main(String[] args) {
        ControlCircuitTest.main(args);
        ControlReflectionTest.main(args);
        ControlSignalsTest.main(args);
        WirelessControlRulesTest.main(args);
        VehicleCircuitGuardTest.main(args);
        ControlInspectionReportTest.main(args);
        VehicleControlPlanTest.main(args);
        VehicleFeedbackPilotTest.main(args);
        VehicleMachineContractTest.main(args);
    }
}
