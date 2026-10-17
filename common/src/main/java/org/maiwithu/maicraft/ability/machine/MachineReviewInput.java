// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 审阅机器蓝图的任务输入：解析好的蓝图与它叫什么。
 *
 * @param blueprint 解析好的机器蓝图（相对锚点的偏移；审阅不落地，不用锚点）
 * @param what      蓝图来的一句话，例如「逐格清单」「图纸「厂房」」
 */
record MachineReviewInput(MachineBlueprint blueprint, String what) implements TaskInput {

    MachineReviewInput {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(what, "what");
    }

    @Override public String describe() {
        return "审阅机器蓝图：" + what;
    }
}
