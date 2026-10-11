// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.light;

import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 随行补光开关的任务输入：开还是关、开的话阈值多少。
 *
 * @param enable       true 开，false 关
 * @param minimumLight 开时的阈值（1..13）；关的时候不用
 */
record FollowLightSettingInput(boolean enable, Integer minimumLight) implements TaskInput {

    @Override public String describe() {
        return enable ? "开随行补光（阈值 " + minimumLight + "）" : "关随行补光";
    }
}
