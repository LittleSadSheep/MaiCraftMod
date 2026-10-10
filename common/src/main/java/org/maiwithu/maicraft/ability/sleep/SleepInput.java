// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 睡觉的任务输入：在哪个床区里找床。床区来自目标对象的解析，没给目标对象就在身边找。
 *
 * @param bedArea 床区中心；全范围找床时为 null
 */
record SleepInput(WorldPosition bedArea) implements TaskInput {

    @Override
    public String describe() {
        return bedArea == null ? "找床睡觉" : "在床区 (" + bedArea.x() + ", " + bedArea.y() + ", " + bedArea.z()
                + ") 附近找床睡觉";
    }
}
