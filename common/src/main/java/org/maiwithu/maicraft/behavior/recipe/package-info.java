// SPDX-License-Identifier: GPL-3.0-only
/**
 * 查配方：玩家不知道一样东西怎么做、一台机器能做什么时，打开 EMI 或 JEI 看一眼；这里是"看一眼"的只读版本。
 *
 * <p>配方查看器（EMI、JEI 这类模组，以及最后退路的游戏配方表）实现 {@link org.maiwithu.maicraft.behavior.recipe.RecipeViewer}；
 * {@link org.maiwithu.maicraft.behavior.recipe.RecipeLookup} 一次只挑一个查看器回答，不合并。
 * 联动模组的查看器由联动登记表交来，游戏配方表在这里自带。
 *
 * <p>只读不做：不打开界面、不点格子、不试做配方。拿东西时真的去合成、烧炼用的是 {@code behavior.acquire} 自己读的配方。
 */
package org.maiwithu.maicraft.behavior.recipe;
