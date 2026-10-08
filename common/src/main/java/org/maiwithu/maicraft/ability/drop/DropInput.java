// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.drop;

import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 丢东西的任务输入：丢哪种、丢几件。丢出去就捡不回来了，数量在这里已经是校验过的。
 *
 * @param itemId 要丢的物品注册 ID
 * @param count  要丢几件，1 到 999
 */
record DropInput(String itemId, int count) implements TaskInput {

    DropInput {
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("要丢的物品不能为空");
        if (count < 1 || count > 999) throw new IllegalArgumentException("要丢的数量超出范围：" + count);
    }

    @Override
    public String describe() {
        return "丢掉 " + count + " 件 " + itemId;
    }
}
