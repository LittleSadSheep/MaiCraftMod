// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 一处亲眼看到的设施：箱子、工作台、熔炉、床这类功能方块，一个观察编号 b#。
 * 上次看到的内容在世界记忆里（开过的容器记着里面有什么），观察视图按编号去查，不在这里重复一份。
 */
public record FacilitySighting(
        String id, String blockType, WorldPosition position,
        String direction, String compass, int distance) {}
