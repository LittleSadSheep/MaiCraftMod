// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.OptionalInt;

import org.maiwithu.maicraft.behavior.explore.ColumnSample;
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

    /**
     * 这一柱列地表能站的那一格（脚的高度）：这一片加载着才读得到，树叶不算地面；读不到为空。
     * 只给了 x/z 的目的地按它落到地面上，不在同一列的坑底、洞里算"到了"。
     */
    default OptionalInt surfaceFeet(int x, int z) {
        return OptionalInt.empty();
    }

    /**
     * 这一列的顶盖样本（忽略树叶的最高实体或液体遮挡物）：判断脚下露天没有、找露天落脚处用；
     * 没加载、读不到给未加载样本，既不判露天也不判封闭。
     */
    default ColumnSample column(int x, int z) {
        return ColumnSample.unloaded();
    }

    /** 人能不能站在这一格（脚在这格、头在上面一格、脚下踩得住）；没加载、读不到时答能，不凭空放宽目的地。 */
    default boolean standable(int x, int y, int z) {
        return true;
    }
}
