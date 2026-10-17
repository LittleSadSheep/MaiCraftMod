// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次驾驶的任务输入：试按（逐条按什么）或开到某处（去哪、操纵表），两者只给一个。
 *
 * @param test  试按的请求；开到某处时为 null
 * @param drive 开到某处的请求；试按时为 null
 */
record VehicleDriveInput(VehicleDriver.TestRequest test, VehicleDriver.DriveRequest drive) implements TaskInput {

    VehicleDriveInput {
        if ((test == null) == (drive == null)) throw new IllegalArgumentException("试按与开到某处要给且只给一个");
    }

    @Override public String describe() {
        if (test != null) return "试按载具 " + test.near().toShortString() + " 的 " + test.inputs().size() + " 个输入";
        return "开载具到 " + Math.round(drive.destination().x) + "," + Math.round(drive.destination().z);
    }
}
