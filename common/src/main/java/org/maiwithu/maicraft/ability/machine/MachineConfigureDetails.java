// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 改机器设置的结果细节：每项设置的改前值与改后读回的值。没改成的项出现在剩余说明里。
 *
 * @param applied 已改好并读回核对的项
 */
record MachineConfigureDetails(List<Applied> applied) implements ResultDetails {

    MachineConfigureDetails {
        applied = List.copyOf(applied);
    }

    /** 一项设置：键、改前读到的值（原来没有为 null）、改完读回的值。 */
    record Applied(String key, String before, String after) {
    }
}
