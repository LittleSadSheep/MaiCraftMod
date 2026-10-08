// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 出行时的现场只读视图：解析目的地需要问的每一件事都在这里问，解析自己不读游戏对象。
 * 真正读角色位置与朝向的实现在游戏接口层，测试里用替身摆出场景。
 */
public interface TravelWorldView {

    /** 角色此刻所在的位置，带维度；维度为 null 表示当前维度。 */
    WorldPosition currentSpot();

    /** 角色此刻的水平朝向，只给东南西北之一；相对的前后左右由解析换算成绝对方向。 */
    Target.Toward currentFacing();
}
