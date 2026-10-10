// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 机器查看的任务输入：看哪里、看多大范围。只读，不带许可（看不需要许可）。
 *
 * @param target 目标对象；不给就是脚下这一片
 * @param radius 没给档案时看多大范围，单位格
 */
record MachineInspectInput(Target target, int radius) implements TaskInput {

    MachineInspectInput {
        Objects.requireNonNull(radius, "radius");
    }

    @Override public String describe() {
        return "看一眼机器（" + radius + " 格范围）";
    }
}
