// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.drop;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 丢东西的任务输入：丢哪种、丢几件。丢出去就捡不回来了，数量在这里已经是校验过的。
 *
 * @param itemId 要丢的物品注册 ID
 * @param count  要丢几件，1 到 999
 * @param onto   朝哪一格扔（扔到传送带、工作盆上）；不指定为 null，随便找个开阔的方向丢
 * @param permissions 这次目标的许可：朝一格扔要走到旁边，走过去能动多少地形按它
 */
record DropInput(String itemId, int count, BlockPos onto, Permissions permissions) implements TaskInput {

    DropInput {
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("要丢的物品不能为空");
        if (count < 1 || count > 999) throw new IllegalArgumentException("要丢的数量超出范围：" + count);
        if (permissions == null) throw new IllegalArgumentException("丢东西必须带这次的许可");
    }

    @Override
    public String describe() {
        return onto == null ? "丢掉 " + count + " 件 " + itemId
                : "把 " + count + " 件 " + itemId + " 扔到 " + onto.toShortString();
    }
}
