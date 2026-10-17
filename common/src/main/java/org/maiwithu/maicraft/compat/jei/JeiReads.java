// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.jei;

import java.util.List;

import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;

/**
 * JEI 的模组读写接缝：JEI 的运行时交出来没有，以及按物品查它显示的配方。读写端把 JEI 的配方原样翻成项目的配方记录：
 * 类别、工作站（JEI 叫催化剂）、每个格子能放的东西、产出、背后游戏配方的原始定义。JEI 的格子不带标签，
 * 能放好几样的格子原样列出全部候选，要不要合成一个标签由联动入口决定。只在客户端线程上调用。
 */
public interface JeiReads {

    /** JEI 已经把运行时交出来了（进世界、配方加载完之后）。 */
    boolean runtimeReady();

    /** JEI 里能做出这件物品的配方。 */
    List<ShownRecipe> making(String itemId);

    /** JEI 里拿这件物品当原料的配方。 */
    List<ShownRecipe> using(String itemId);

    /** 这件物品是工作站的那些类别里的全部配方。 */
    List<ShownRecipe> atWorkstation(String itemId);
}
