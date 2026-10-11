// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.Objects;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/** 一次组装或拆回的任务输入：要做哪一种、在哪、指的哪个结构、许可。 */
record VehicleAssembleInput(VehicleAssembler.Request request) implements TaskInput {

    VehicleAssembleInput {
        Objects.requireNonNull(request, "request");
    }

    @Override public String describe() {
        return (request.assemble() ? "组装 " : "拆回 ") + request.near().toShortString();
    }
}
