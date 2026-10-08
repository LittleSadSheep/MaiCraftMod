// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 动手做配方的执行接缝：走到工作站跟前，打开界面，把原料（和烧炼要的燃料）放进去，
 * 取出做好的东西。合成、烧炼、石切台同走这一条路，差别只在配方与设施；
 * 现场动作（靠近、点开、按搬运计划放料取料、关界面）由游戏接口层用靠近与界面会话的
 * 公开接口实现，测试用替身。原料不够、设施没了或现在用不了时返回 empty。
 */
public interface RecipeRuns {

    /**
     * 为一条配方生成动作：做完时做好的东西已在背包里（做出来几件以重新清点为准）。
     *
     * @param recipe  要做的配方
     * @param station 记忆里这台设施的位置，走到跟前再动手
     * @param times   要做几次
     */
    Optional<Action> run(RecipeView recipe, WorldPosition station, int times);
}
