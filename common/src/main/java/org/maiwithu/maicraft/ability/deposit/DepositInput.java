// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 存东西的任务输入：解析并核对过的参数，不可变。
 *
 * @param target      存进哪个容器，或在哪片地方存；不给就在附近挑
 * @param itemIds     存哪些（物品 ID 或 # 标签）；不点名时随身要留的东西以外的全存
 * @param count       一共存几件；null 表示全存
 * @param radius      自己挑容器时的范围（格）
 * @param permissions 这次任务的许可
 */
record DepositInput(Target target, List<String> itemIds, Integer count, long radius,
        Permissions permissions) implements TaskInput {

    DepositInput {
        Objects.requireNonNull(itemIds, "itemIds");
        itemIds = List.copyOf(itemIds);
        if (radius < 1) throw new IllegalArgumentException("范围至少是 1 格：" + radius);
    }

    @Override public String describe() {
        String what = itemIds.isEmpty() ? "随身要留的东西以外全部" : String.join("、", itemIds);
        String where = target == null ? "附近挑容器" : "指定的目标";
        String times = count == null ? "" : "，共 " + count + " 件";
        return "存进去：" + what + "，" + where + times;
    }
}
