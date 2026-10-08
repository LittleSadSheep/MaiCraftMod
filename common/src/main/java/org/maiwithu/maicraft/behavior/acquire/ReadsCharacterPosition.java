// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 读角色位置的只读接缝：问价时告诉各来源"角色现在在哪"。实现在游戏接口层读角色本体；
 * 测试里摆一个固定位置。
 */
public interface ReadsCharacterPosition {

    /** 角色此刻所在的位置。 */
    WorldPosition currentPosition();
}
