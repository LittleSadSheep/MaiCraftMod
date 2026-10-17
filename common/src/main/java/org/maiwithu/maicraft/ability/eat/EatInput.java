// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.eat;

import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 吃东西的任务输入：吃哪种、吃几件。挑哪种吃是能力看现场决定的，任务照着办，不再自己挑。
 *
 * @param itemId 要吃的物品注册 ID
 * @param count  要吃几件，至少为 1
 */
record EatInput(String itemId, int count) implements TaskInput {

    EatInput {
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("要吃的物品不能为空");
        if (count < 1) throw new IllegalArgumentException("要吃的数量至少为 1：" + count);
    }

    @Override
    public String describe() {
        return "吃 " + count + " 件 " + itemId;
    }
}
