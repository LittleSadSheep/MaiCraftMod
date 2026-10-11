// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 组装能力的决定：没接上组装联动如实说不支持；s# 落成结构身份；坐标没给 y 按脚下。 */
class VehicleAssembleModuleTest {

    private static final VehicleAssembler NONE = request -> Optional.empty();

    private static VehicleAssembleModule module(List<VehicleAssembler> assemblers) {
        return new VehicleAssembleModule(assemblers, id -> Optional.of(WorldPosition.here(5, 70, 5)),
                id -> id.equals("s3") ? Optional.of("ship-uuid") : Optional.empty(), name -> Optional.empty(),
                () -> new BlockPos(0, 64, 0));
    }

    private static StepContext step(Target target, String params) {
        Goal goal = new Goal("maicraft:vehicle_assemble", null, target,
                module(List.of(NONE)).spec().paramSpecs().parse(JsonParser.parseString(params).getAsJsonObject()).params(),
                Permissions.DEFAULT, List.of(), null);
        return new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { throw new IllegalStateException("决定阶段不碰每刻上下文"); }
        };
    }

    @Test
    void 没接上组装联动如实说不支持() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                module(List.of()).decide(step(new Target.Position(1, 64, 1, null), "{}")));
        assertEquals(Problem.Kind.UNSUPPORTED, finish.result().problem().kind());
    }

    @Test
    void 结构的观察编号落成结构身份_拆回() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                module(List.of(NONE)).decide(step(new Target.Seen("s3"), "{\"operation\":\"disassemble\"}")));
        VehicleAssembler.Request request = assertInstanceOf(VehicleAssembleInput.class, run.input()).request();
        assertFalse(request.assemble());
        assertEquals("ship-uuid", request.structureId());
        assertEquals(new BlockPos(5, 70, 5), request.near());
    }

    @Test
    void 坐标没给高度按脚下() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                module(List.of(NONE)).decide(step(new Target.Position(9, null, 9, null), "{}")));
        assertEquals(new BlockPos(9, 64, 9), ((VehicleAssembleInput) run.input()).request().near());
    }
}
