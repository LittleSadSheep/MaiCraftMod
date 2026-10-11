// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.List;

import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 驾驶的结果细节：动作成没成（按了没有、松开没有、控制件交还没有）与载具怎么动分开写。
 *
 * @param vehicle      开的是哪辆（结构身份）
 * @param seat         怎么坐的
 * @param inputs       每条输入的经过与每 5 刻的样本
 * @param released     本次按下的键都松开了
 * @param disconnected 控制件交还了
 * @param note         经过一句话
 * @param controlMap   这次试按后的操纵表（推出来的、试过的、LLM 定的合在一起，带缺的必需角色）；没记为 null
 * @param drove        开到某处的经过（阶段、落点、行程、最大倾斜、开到没有、停稳没有）；试按为 null
 */
record VehicleDriveDetails(String vehicle, String seat, List<VehicleDriver.Tried> inputs, boolean released,
        boolean disconnected, String note, VehicleControls.ControlMap controlMap, VehicleDriver.Drove drove)
        implements ResultDetails {

    VehicleDriveDetails {
        inputs = List.copyOf(inputs);
    }

    static VehicleDriveDetails of(VehicleDriver.Report report, VehicleControls.ControlMap controlMap) {
        return new VehicleDriveDetails(report.structureId(), report.seat(), report.tried(), report.released(),
                report.disconnected(), report.note(), controlMap, report.drove());
    }
}
