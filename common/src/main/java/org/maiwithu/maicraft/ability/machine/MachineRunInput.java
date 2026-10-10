// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 用机器做东西的任务输入：用哪台机器、做什么、做几件、做完拿不拿、最多等多久。
 *
 * @param target     目标对象：一格机器方块的写法
 * @param item       要做出什么；不给就只开机
 * @param count      要做几件
 * @param collect    做完要不要把出口的东西拿进背包
 * @param maxSeconds 最多等多久（秒）
 * @param permissions 这次任务的许可
 */
record MachineRunInput(Target target, String item, int count, boolean collect, int maxSeconds,
                       Permissions permissions) implements TaskInput {

    MachineRunInput {
        Objects.requireNonNull(permissions, "permissions");
        if (count < 1) throw new IllegalArgumentException("要做几件至少是 1：" + count);
        if (maxSeconds < 1) throw new IllegalArgumentException("最多等多少秒至少是 1：" + maxSeconds);
    }

    @Override public String describe() {
        return item == null ? "开机：" + target : "用机器做 " + count + " 件 " + item;
    }
}
