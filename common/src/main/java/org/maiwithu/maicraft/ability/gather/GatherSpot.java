// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次采集的任务输入：在哪、是掉落物还是一格方块、预期掉什么。
 * 方块的种类到了现场再读——观察编号只是当时看到的，动手前以现场为准。
 *
 * @param at           要采的位置
 * @param drop         true 是地上的掉落物（走过去让原版结算拾取），false 是一格方块
 * @param declaredType 目标对象带来的方块 ID（配 position 目标用）；没有为 null
 * @param expectedItem 认为会掉出的东西，用于确认捡没捡到；没有为 null，按实际掉落记录
 * @param permissions  这次任务的许可；动哪一格由它说了算
 * @param describeText 给日志与面板的一句话，例如"采集 b7 的方块"
 */
record GatherSpot(WorldPosition at, boolean drop, String declaredType,
        String expectedItem, Permissions permissions, String describeText) implements TaskInput {

    GatherSpot {
        if (at == null) throw new IllegalArgumentException("采集必须有位置");
        if (permissions == null) throw new IllegalArgumentException("采集必须带这次的许可");
        if (describeText == null || describeText.isBlank()) {
            throw new IllegalArgumentException("任务输入必须有一句话描述");
        }
    }

    @Override public String describe() {
        return describeText;
    }
}
