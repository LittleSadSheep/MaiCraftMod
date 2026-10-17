// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.travel;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 回露天的任务输入：出行的目标写 here 而人在地下、屋里、崖檐下时，回到头顶见天的地面上。
 * 不可变；看一眼、走出去、挖楼梯的进度都在回露天任务里。
 *
 * @param maxSeconds  最多做多久（秒）；不超过 0 表示不设时限
 * @param permissions 这次的许可：能不能挖楼梯、走路能动多少地形
 */
record OpenSkyInput(int maxSeconds, Permissions permissions) implements TaskInput {

    @Override
    public String describe() {
        return "回到露天的地面上";
    }
}
