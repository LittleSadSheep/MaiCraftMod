// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;

/**
 * 读配方的只读接缝：某种东西有哪些做法。数据来自角色所在世界真实的配方管理器（含模组配方），
 * 实现在游戏接口层；这里不推演、不缓存配方之外的任何东西。
 */
public interface ReadsRecipes {

    /** 能做出想要的东西的全部配方；游戏里没有做法时给空列表。 */
    List<RecipeView> recipesProducing(WantedItem wanted);
}
