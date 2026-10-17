// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.Optional;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 打一下展示框（含发光展示框）把里面的东西打出来：看框里放着什么、走到够得着的地方、左键打一下。
 * 原版里框里有东西时打一下只弹出那样东西、框还挂着，框空着时再打才把框本身打下来，
 * 所以只在框里有东西时出手。生产实现接真实客户端，测试用替身。
 */
public interface HitsItemFrames {

    /** 框里此刻放着什么（物品 ID）；框空着为空串，那个实体已经不在或不是展示框为空。 */
    Optional<String> contents(int frame);

    /** 走到够得着、看得见这个展示框的站位；做完时角色已站好，到不了以问题失败。 */
    Action approach(int frame, Permissions permissions);

    /** 左键打一下（原生攻击实体一次，不开一场战斗）：框里空了或框没了算打成，打了没反应如实失败。 */
    Action hit(int frame);
}
