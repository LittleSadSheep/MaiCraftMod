// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.travel.JourneyInput;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 驾驶能力的决定：参数逐条核对；不在车上先跑一趟出行再回来；走过去还够不着如实结束；在车上就交给驾驶办法。 */
class VehicleDriveModuleTest {

    private static final String TEST = "{\"vehicle\":\"s3\",\"operation\":\"test\",\"inputs\":[{\"control\":\"打字机:W\",\"ticks\":20}]}";

    /** 一个替身驾驶办法：boardAt 按给定的答。 */
    private static VehicleDriver driver(Optional<WorldPosition> board) {
        return new VehicleDriver() {
            @Override public Optional<WorldPosition> boardAt(String structureId) { return board; }
            @Override public Optional<Run> test(TestRequest request) { return Optional.empty(); }
        };
    }

    private static VehicleDriveModule module(List<VehicleDriver> drivers) {
        return new VehicleDriveModule(drivers, id -> Optional.of(WorldPosition.here(5, 70, 5)),
                id -> id.equals("s3") ? Optional.of("ship-uuid") : Optional.empty());
    }

    private static StepContext step(String params, List<TaskResult> earlier) {
        Goal goal = new Goal("maicraft:vehicle_drive", null, null,
                module(List.of()).spec().paramSpecs().parse(JsonParser.parseString(params).getAsJsonObject()).params(),
                Permissions.DEFAULT, List.of(), null);
        return new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { throw new IllegalStateException("决定阶段不碰每刻上下文"); }
            @Override public List<TaskResult> taskResults() { return earlier; }
        };
    }

    private static Problem.Kind failed(StepDecision decision) {
        return assertInstanceOf(StepDecision.Finish.class, decision).result().problem().kind();
    }

    @Test
    void 参数逐条核对() {
        VehicleDriveModule module = module(List.of(driver(Optional.empty())));
        assertEquals(Problem.Kind.UNSUPPORTED, failed(module(List.of()).decide(step(TEST, List.of()))));
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed(module.decide(step("{\"vehicle\":\"飞艇\",\"operation\":\"test\"}", List.of()))));
        assertEquals(Problem.Kind.TARGET_GONE, failed(module.decide(step("{\"vehicle\":\"s9\",\"operation\":\"test\"}", List.of()))));
        assertEquals(Problem.Kind.UNSUPPORTED, failed(module.decide(step("{\"vehicle\":\"s3\"}", List.of()))),
                "drive 还没做");
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed(module.decide(step("{\"vehicle\":\"s3\",\"operation\":\"test\"}", List.of()))));
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed(module.decide(step(
                "{\"vehicle\":\"s3\",\"operation\":\"test\",\"inputs\":[{\"control\":\"打字机:W\",\"ticks\":0}]}", List.of()))));
    }

    @Test
    void 在车上就交给驾驶办法试按() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                module(List.of(driver(Optional.empty()))).decide(step(TEST, List.of())));
        VehicleDriver.TestRequest request = assertInstanceOf(VehicleDriveInput.class, run.input()).request();
        assertEquals("ship-uuid", request.structureId());
        assertEquals(List.of(new VehicleDriver.Press("打字机:W", 20)), request.inputs());
    }

    @Test
    void 不在车上先跑一趟出行_回来还够不着如实结束() {
        VehicleDriveModule module = module(List.of(driver(Optional.of(WorldPosition.here(6, 71, 5)))));
        StepDecision.Run walk = assertInstanceOf(StepDecision.Run.class, module.decide(step(TEST, List.of())));
        assertInstanceOf(JourneyInput.class, walk.input());
        assertTrue(walk.decideAgain(), "走完回来再决定");
        assertEquals(Problem.Kind.UNREACHABLE, failed(module.decide(step(TEST, List.of(TaskResult.done("到了"))))));
    }
}
