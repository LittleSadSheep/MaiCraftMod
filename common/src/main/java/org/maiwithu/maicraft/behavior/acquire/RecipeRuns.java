// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.goal.Permissions;

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
     * @param times       要做几次
     * @param permissions 这次任务的许可：走到设施跟前时能动多少地形按它来
     */
    Optional<Action> run(WorkstationRecipe recipe, WorldPosition station, int times, Permissions permissions);

    /**
     * 在"刚记进世界记忆的那台设施"上做：附近本来没有设施、就地放了一个新的时，
     * 动手前才从世界记忆里读它的位置。实现从记忆里按方块类型找最近一台；
     * 还没接上就地摆放的执行时返回 empty，来源如实报告做不了。
     */
    default Optional<Action> runAtRememberedStation(WorkstationRecipe recipe, int times, Permissions permissions) {
        return Optional.empty();
    }

    /** 能不能在背包的 2×2 合成格里做：能的话摆得进 2×2 的合成配方不用工作台。 */
    default boolean craftsInInventory() {
        return false;
    }

    /**
     * 在背包的 2×2 合成格里做一条摆得进去的合成配方：打开背包界面、经配方簿摆料、取走产出，做够了关上。
     * 做完时做好的东西已在背包里（做出来几件以重新清点为准）；做不了时返回 empty。
     */
    default Optional<Action> runInInventory(WorkstationRecipe recipe, int times) {
        return Optional.empty();
    }
}
