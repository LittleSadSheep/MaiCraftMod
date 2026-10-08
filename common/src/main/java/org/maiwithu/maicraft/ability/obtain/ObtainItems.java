// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次拿东西的任务输入：要什么、再多拿几件、走哪几条途径、愿意走多远、在多大范围里找。
 * 参数在能力下决定时就换成了带类型的取值，任务不再碰原始 JSON。
 *
 * @param wanted            想要的东西（一种物品或一个标签）
 * @param count             这次要多拿几件，至少为 1
 * @param scope             这次拿东西的限定：途径、距离上限、搜索半径
 * @param permissions       这次任务的许可；来源备料与腾背包受同一份许可管
 * @param purpose           用途标签，写进结果与腾背包的备注，例如"按需要拿取"
 * @param describeText      给日志与面板的一句话，例如"再拿 8 个 minecraft:torch"
 */
record ObtainItems(WantedItem wanted, int count, ItemAcquisition.Scope scope,
        Permissions permissions, String purpose, String describeText) implements TaskInput {

    ObtainItems {
        if (wanted == null) throw new IllegalArgumentException("拿东西必须有想要的东西");
        if (count < 1) throw new IllegalArgumentException("要拿的数量至少为 1：" + count);
        if (scope == null) throw new IllegalArgumentException("拿东西必须带限定（不限也是一种限定）");
        if (permissions == null) throw new IllegalArgumentException("拿东西必须带这次的许可");
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("拿东西必须带用途标签");
        if (describeText == null || describeText.isBlank()) throw new IllegalArgumentException("任务输入必须有一句话描述");
    }

    @Override public String describe() {
        return describeText;
    }
}
