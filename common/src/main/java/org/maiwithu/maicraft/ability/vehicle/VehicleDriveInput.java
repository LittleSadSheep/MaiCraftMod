// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.Objects;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/** 一次试按的任务输入：开哪辆、逐条按什么、许可。 */
record VehicleDriveInput(VehicleDriver.TestRequest request) implements TaskInput {

    VehicleDriveInput {
        Objects.requireNonNull(request, "request");
    }

    @Override public String describe() {
        return "试按载具 " + request.near().toShortString() + " 的 " + request.inputs().size() + " 个输入";
    }
}
